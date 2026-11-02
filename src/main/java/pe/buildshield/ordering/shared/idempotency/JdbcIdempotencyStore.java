package pe.buildshield.ordering.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tabla {@code idempotency_keys} del ERD: {@code key}, {@code organization_id}, {@code endpoint}, {@code request_hash},
 * {@code status}, {@code response_body} y {@code expires_at}. {@code response_body} es un JSON con la respuesta a repetir
 * y con lo que la tabla no tiene como columna: la ruta exacta, el dueño de la clave y el estado HTTP. Al vencer se retira
 * la respuesta y se conserva lo demás. El advisory lock dura la transacción; la identidad de una operación nunca se
 * elimina.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    static final int ENDPOINT_LENGTH = 80;
    /** Huella de un registro antiguo que no la tenía: nunca coincide con una solicitud. */
    static final String NO_HASH = "0".repeat(64);
    private static final String COMPLETED = "COMPLETED";
    private static final String EXPIRED = "EXPIRED";
    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLock(UUID organizationId, UUID key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                lockId(organizationId, key)));
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID organizationId, UUID key) {
        return jdbc.query("SELECT * FROM idempotency_keys WHERE organization_id = ? AND key = ?",
                JdbcIdempotencyStore::map, organizationId, key).stream().findFirst();
    }

    @Override
    public void save(IdempotencyRecord r) {
        jdbc.update("""
                INSERT INTO idempotency_keys (key, organization_id, endpoint, request_hash, status, response_body, expires_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, r.key(), r.organizationId(), endpoint(r.requestMethod(), r.requestPath()),
                r.requestFingerprint() == null ? NO_HASH : r.requestFingerprint(),
                r.responseExpired() ? EXPIRED : COMPLETED, responseJson(r),
                Timestamp.from(r.createdAt().plus(IdempotencyKeyFilter.RETENTION)));
    }

    /** Retira solo las respuestas; conserva las marcas que impiden duplicaciones tardías. */
    @Override
    public int retireResponsesCreatedBefore(Instant threshold) {
        return jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'EXPIRED',
                    response_body = response_body - 'bodyBase64'::text - 'contentType'::text - 'location'::text
                WHERE expires_at < ? AND status = 'COMPLETED'
                """, Timestamp.from(threshold.plus(IdempotencyKeyFilter.RETENTION)));
    }

    /** Operación protegida: método y ruta, con los identificadores como {@code {id}}. La ruta exacta va en el JSON. */
    static String endpoint(String method, String path) {
        String endpoint = method + " " + UUID_SEGMENT.matcher(path).replaceAll("{id}");
        return endpoint.length() > ENDPOINT_LENGTH ? endpoint.substring(0, ENDPOINT_LENGTH) : endpoint;
    }

    private static String responseJson(IdempotencyRecord r) {
        ObjectNode json = JSON.createObjectNode();
        json.put("method", r.requestMethod());
        json.put("path", r.requestPath());
        json.put("status", r.responseStatus());
        if (r.userId() != null) {
            json.put("userId", r.userId().toString());
        }
        if (r.role() != null) {
            json.put("role", r.role());
        }
        if (r.responseContentType() != null) {
            json.put("contentType", r.responseContentType());
        }
        if (r.responseLocation() != null) {
            json.put("location", r.responseLocation());
        }
        if (r.responseBody() != null) {
            json.put("bodyBase64", Base64.getEncoder().encodeToString(r.responseBody()));
        }
        return json.toString();
    }

    private static IdempotencyRecord map(ResultSet rs, int row) throws SQLException {
        JsonNode response = read(rs.getString("response_body"));
        String hash = rs.getString("request_hash");
        String body = text(response, "bodyBase64");
        String user = text(response, "userId");
        return new IdempotencyRecord(rs.getObject("organization_id", UUID.class), rs.getObject("key", UUID.class),
                text(response, "method"), text(response, "path"), response.path("status").asInt(),
                text(response, "contentType"), body == null ? null : Base64.getDecoder().decode(body),
                rs.getTimestamp("expires_at").toInstant().minus(IdempotencyKeyFilter.RETENTION),
                user == null ? null : UUID.fromString(user), text(response, "role"),
                NO_HASH.equals(hash) ? null : hash, text(response, "location"),
                EXPIRED.equals(rs.getString("status")));
    }

    private static JsonNode read(String json) {
        if (json == null) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("idempotency_keys.response_body no es JSON", ex);
        }
    }

    private static String text(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    static long lockId(UUID organizationId, UUID key) {
        long hash = 1125899906842597L;
        for (long part : new long[] {organizationId.getMostSignificantBits(), organizationId.getLeastSignificantBits(),
                key.getMostSignificantBits(), key.getLeastSignificantBits()}) {
            hash = 31 * hash + part;
        }
        return hash;
    }
}
