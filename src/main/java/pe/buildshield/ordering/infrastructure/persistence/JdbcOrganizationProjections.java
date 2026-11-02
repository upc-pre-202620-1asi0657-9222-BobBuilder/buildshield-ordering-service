package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.OrganizationDirectory;
import pe.buildshield.ordering.shared.tenant.TenantContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Copias locales de organization ({@code location_projection}, {@code material_projection} y
 * {@code assignment_projection}), alimentadas por {@code OrganizationEventConsumer}. Son tablas JDBC, fuera del outbox
 * y sin {@code @TenantId}: las lecturas filtran a mano por la organización del {@code TenantContext}.
 */
@Component
public class JdbcOrganizationProjections implements OrganizationDirectory {

    private static final String UPSERT_LOCATION = """
            INSERT INTO location_projection (location_id, organization_id, type, name, address, active, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (location_id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name,
                address = EXCLUDED.address, active = EXCLUDED.active, updated_at = EXCLUDED.updated_at""";

    private static final String UPSERT_MATERIAL = """
            INSERT INTO material_projection
                (material_id, organization_id, sku, name, unit, tolerance_percent, active, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (material_id) DO UPDATE SET sku = EXCLUDED.sku, name = EXCLUDED.name, unit = EXCLUDED.unit,
                tolerance_percent = EXCLUDED.tolerance_percent, active = EXCLUDED.active,
                updated_at = EXCLUDED.updated_at""";

    private static final String UPSERT_ASSIGNMENT = """
            INSERT INTO assignment_projection
                (assignment_id, organization_id, user_id, location_id, location_type, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (assignment_id) DO UPDATE SET user_id = EXCLUDED.user_id, location_id = EXCLUDED.location_id,
                location_type = EXCLUDED.location_type, updated_at = EXCLUDED.updated_at""";

    private static final int ADDRESS_LENGTH = 200;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    JdbcOrganizationProjections(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    // ---------- Lecturas (OrganizationDirectory) ----------

    @Override
    public Optional<Location> findLocation(UUID locationId) {
        return jdbc.query("""
                SELECT location_id, type, name, address, active FROM location_projection
                WHERE location_id = ? AND organization_id = ?""",
                (rs, row) -> new Location(rs.getObject("location_id", UUID.class), rs.getString("type"),
                        rs.getString("name"), rs.getString("address"), rs.getBoolean("active")), locationId,
                organization()).stream().findFirst();
    }

    @Override
    public Optional<Material> findMaterial(UUID materialId) {
        return findMaterials(Set.of(materialId)).values().stream().findFirst();
    }

    @Override
    public Map<UUID, Material> findMaterials(Collection<UUID> materialIds) {
        Map<UUID, Material> found = new HashMap<>();
        if (materialIds.isEmpty()) {
            return found;
        }
        named.query("""
                SELECT material_id, sku, name, unit, tolerance_percent, active FROM material_projection
                WHERE material_id IN (:ids) AND organization_id = :organization""",
                new MapSqlParameterSource("ids", new HashSet<>(materialIds)).addValue("organization", organization()),
                rs -> {
                    UUID id = rs.getObject("material_id", UUID.class);
                    found.put(id, new Material(id, rs.getString("sku"), rs.getString("name"), rs.getString("unit"),
                            rs.getBigDecimal("tolerance_percent"), rs.getBoolean("active")));
                });
        return found;
    }

    @Override
    public boolean isAssigned(UUID userId, UUID locationId) {
        if (userId == null || locationId == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM assignment_projection
                               WHERE user_id = ? AND location_id = ? AND organization_id = ?)""",
                Boolean.class, userId, locationId, organization()));
    }

    @Override
    public Set<UUID> assignedSites(UUID userId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT location_id FROM assignment_projection WHERE user_id = ? AND organization_id = ?""",
                UUID.class, userId, organization()));
    }

    // ---------- Escrituras (eventos de buildshield.organization) ----------

    /**
     * {@code LocationRegistered} y {@code LocationUpdated}. De una obra se guarda la dirección completa, con distrito y
     * ciudad, que es la que muestra el manifiesto; de un almacén, solo su dirección.
     */
    public void upsertLocation(UUID organizationId, UUID locationId, String type, String name, String address,
            String district, String city, boolean active) {
        String stored = WORKSITE.equals(type) ? fullAddress(address, district, city) : address;
        jdbc.update(UPSERT_LOCATION, locationId, organizationId, type, name, stored, active, now());
    }

    /** {@code MaterialRegistered} y {@code MaterialUpdated}. */
    public void upsertMaterial(UUID organizationId, UUID materialId, String sku, String name, String unit,
            BigDecimal tolerancePercent, boolean active) {
        jdbc.update(UPSERT_MATERIAL, materialId, organizationId, sku, name, unit,
                tolerancePercent == null ? BigDecimal.ZERO : tolerancePercent, active, now());
    }

    /** {@code StaffAssigned}. */
    public void upsertAssignment(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId,
            String locationType) {
        jdbc.update(UPSERT_ASSIGNMENT, assignmentId, organizationId, userId, locationId, locationType, now());
    }

    /** {@code StaffUnassigned}: una asignación retirada ya no da acceso, así que se borra. */
    public void removeAssignment(UUID organizationId, UUID assignmentId) {
        jdbc.update("DELETE FROM assignment_projection WHERE assignment_id = ? AND organization_id = ?", assignmentId,
                organizationId);
    }

    private static String fullAddress(String address, String district, String city) {
        String joined = Stream.of(address, district, city).filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(", "));
        if (joined.isEmpty()) {
            return null;
        }
        return joined.length() > ADDRESS_LENGTH ? joined.substring(0, ADDRESS_LENGTH) : joined;
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static UUID organization() {
        return TenantContext.require().organizationId();
    }
}
