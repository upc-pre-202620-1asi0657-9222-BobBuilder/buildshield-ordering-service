package pe.buildshield.ordering.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.ordering.support.OrderingIntegrationTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las migraciones sobre datos reales de V1, en un esquema propio de la base de pruebas: nada se pierde en silencio y,
 * si una copia no coincide con su origen, la migración se detiene.
 */
@OrderingIntegrationTest
class OrderingErdMigrationIT {

    private static final String MANIFEST = "MAN-20261020-7KQ2M9XA";
    private static final Timestamp PLACED = ts("2026-10-20T05:00:00Z");
    private static final Timestamp LATER = ts("2026-10-21T05:00:00Z");

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private final String schema = "erd_migration_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID auditor = UUID.randomUUID();
    private final UUID worksite = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final UUID material = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID orderLine = UUID.randomUUID();
    private final UUID dispatch = UUID.randomUUID();
    private final UUID dispatchLine = UUID.randomUUID();
    private final UUID reception = UUID.randomUUID();
    private final UUID receptionLine = UUID.randomUUID();

    @BeforeEach
    void schemaAtVersion1() {
        flyway("1").migrate();
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void legacy_rows_are_kept_or_moved_to_their_place_in_the_erd() {
        seedDeliveredOrder();
        UUID incident = UUID.randomUUID();
        jdbc.update(sql("INSERT INTO %s.transit_incidents (id, organization_id, dispatch_id, type, observation, "
                + "reported_by, reported_at) VALUES (?, ?, ?, 'DELAY', 'Tráfico', ?, ?)"), incident, org, dispatch,
                requester, LATER);
        location(worksite, "WORKSITE", "Av. 1", "Lince", "Lima");
        location(warehouse, "WAREHOUSE", "Av. 2", "Callao", "Lima");
        UUID activeAssignment = assignment(true);
        UUID retiredAssignment = assignment(false);
        UUID event = UUID.randomUUID();
        jdbc.update(sql("INSERT INTO %s.outbox_events (id, event_type, aggregate_type, aggregate_id, organization_id, "
                + "actor_id, correlation_id, payload, occurred_at) VALUES (?, 'OrderRegistered', 'ORDER', ?, ?, ?, "
                + "'corr-1', '{\"orderId\":\"x\"}'::jsonb, ?)"), event, order.toString(), org, requester, PLACED);
        UUID processed = UUID.randomUUID();
        jdbc.update(sql("INSERT INTO %s.processed_events (event_id, processed_at) VALUES (?, ?)"), processed, PLACED);
        UUID key = UUID.randomUUID();
        byte[] body = "{\"id\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        idempotency(key, body, requester, "f".repeat(64), false);
        UUID legacyKey = UUID.randomUUID();
        idempotency(legacyKey, body, null, null, true);

        flyway("latest").migrate();

        Map<String, Object> orderRow = row("orders", order);
        assertThat(orderRow).containsEntry("created_by", requester).containsEntry("notes", "Losa")
                .containsEntry("decided_by", auditor).doesNotContainKeys("placed_at", "requested_by");
        assertThat(((Timestamp) orderRow.get("created_at")).toInstant()).isEqualTo(PLACED.toInstant());
        assertThat(orderRow.get("required_date")).isEqualTo(Date.valueOf(LocalDate.of(2026, 10, 20)));
        assertThat(row("order_lines", orderLine)).doesNotContainKeys("sku", "unit", "received_qty");
        assertThat(jdbc.queryForMap(sql("SELECT sku, unit, active FROM %s.material_projection WHERE material_id = ?"),
                material)).containsEntry("sku", "CEM-001").containsEntry("unit", "BAG").containsEntry("active", false);

        Map<String, Object> dispatchRow = row("dispatches", dispatch);
        assertThat(((Timestamp) dispatchRow.get("created_at")).toInstant()).isEqualTo(PLACED.toInstant());
        assertThat(dispatchRow).containsKey("departed_at").containsKey("cancellation_reason")
                .doesNotContainKeys("prepared_at", "received_at");
        assertThat(jdbc.queryForObject(sql("SELECT ticket_photo_url FROM %s.departure_weighings WHERE dispatch_id = ?"),
                String.class, dispatch)).isNull();

        Map<String, Object> receptionRow = row("receptions", reception);
        assertThat(receptionRow).containsEntry("partial_count", 1).containsKey("last_partial_at")
                .doesNotContainKeys("order_id", "warehouse_id", "worksite_id", "manifest_code");
        assertThat(((BigDecimal) row("reception_lines", receptionLine).get("stocked_qty"))).isEqualByComparingTo("48");
        assertThat(row("reception_lines", receptionLine))
                .doesNotContainKeys("order_line_id", "material_id", "dispatched_qty");
        assertThat(jdbc.queryForMap(sql("SELECT * FROM %s.arrival_weighings WHERE reception_id = ?"), reception))
                .containsEntry("tolerance_pct", null).doesNotContainKeys("departure_net_kg");

        assertThat(jdbc.queryForMap(sql("SELECT created_by, created_at, photo_url FROM %s.transit_incidents WHERE id = ?"),
                incident)).containsEntry("created_by", requester).containsEntry("photo_url", null);

        assertThat(address(worksite)).isEqualTo("Av. 1, Lince, Lima");
        assertThat(address(warehouse)).isEqualTo("Av. 2");
        assertThat(jdbc.queryForList(sql("SELECT assignment_id FROM %s.assignment_projection"), UUID.class))
                .contains(activeAssignment).doesNotContain(retiredAssignment);

        Map<String, Object> outbox = jdbc.queryForMap(sql("SELECT aggregate_id, payload::text AS payload, "
                + "published_at FROM %s.outbox_events WHERE id = ?"), event);
        assertThat(outbox).containsEntry("aggregate_id", order).containsEntry("published_at", null);
        assertThat((String) outbox.get("payload")).contains("\"aggregateType\": \"ORDER\"", "\"orderId\": \"x\"",
                "\"actorId\": \"" + requester + "\"", "\"eventId\": \"" + event + "\"");

        assertThat(jdbc.queryForList(sql("SELECT consumer FROM %s.processed_events WHERE event_id = ?"), String.class,
                processed)).containsExactly("StockEventConsumer");

        Map<String, Object> keyRow = jdbc.queryForMap(sql("SELECT endpoint, request_hash, status, "
                + "response_body->>'bodyBase64' AS body, response_body->>'userId' AS owner, "
                + "response_body->>'path' AS path, expires_at FROM %s.idempotency_keys WHERE key = ?"), key);
        assertThat(keyRow).containsEntry("endpoint", "POST /api/v1/orders/{id}/approve")
                .containsEntry("request_hash", "f".repeat(64)).containsEntry("status", "COMPLETED")
                .containsEntry("owner", requester.toString())
                .containsEntry("path", "/api/v1/orders/" + order + "/approve")
                .containsEntry("body", Base64.getEncoder().encodeToString(body));
        assertThat(((Timestamp) keyRow.get("expires_at")).toInstant()).isEqualTo(PLACED.toInstant().plusSeconds(86400));
        assertThat(jdbc.queryForMap(sql("SELECT request_hash, status, response_body->>'bodyBase64' AS body, "
                + "response_body->>'userId' AS owner FROM %s.idempotency_keys WHERE key = ?"), legacyKey))
                .containsEntry("request_hash", "0".repeat(64)).containsEntry("status", "EXPIRED")
                .containsEntry("body", null).containsEntry("owner", null);
    }

    @Test
    void version_3_clears_only_the_photo_marker_and_keeps_the_dates_completed_by_version_2() {
        seedDeliveredOrder();
        flyway("2").migrate();
        assertThat(jdbc.queryForObject(sql("SELECT ticket_photo_url FROM %s.departure_weighings WHERE dispatch_id = ?"),
                String.class, dispatch)).isEqualTo("sin-foto");
        UUID withPhoto = UUID.randomUUID();
        jdbc.update(sql("INSERT INTO %s.dispatches (id, organization_id, warehouse_id, worksite_id, type, status, "
                + "manifest_code, version, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, 'RETURN', "
                + "'PREPARED', 'MAN-20261020-8KQ2M9XA', 0, ?, ?, ?, ?)"), withPhoto, org, warehouse, worksite, PLACED,
                requester, PLACED, requester);
        jdbc.update(sql("INSERT INTO %s.departure_weighings (id, organization_id, dispatch_id, gross_kg, tare_kg, net_kg, "
                + "ticket_photo_url, weighed_at, weighed_by) VALUES (?, ?, ?, 2000, 500, 1500, "
                + "'https://evidencias/t.jpg', ?, ?)"), UUID.randomUUID(), org, withPhoto, PLACED, requester);

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject(sql("SELECT ticket_photo_url FROM %s.departure_weighings WHERE dispatch_id = ?"),
                String.class, dispatch)).isNull();
        assertThat(jdbc.queryForObject(sql("SELECT ticket_photo_url FROM %s.departure_weighings WHERE dispatch_id = ?"),
                String.class, withPhoto)).isEqualTo("https://evidencias/t.jpg");
        assertThat(row("orders", order).get("required_date")).isEqualTo(Date.valueOf(LocalDate.of(2026, 10, 20)));
    }

    @Test
    void a_copy_that_differs_from_its_source_stops_the_migration() {
        seedDeliveredOrder();
        jdbc.update(sql("UPDATE %s.order_lines SET received_qty = received_qty - 1 WHERE id = ?"), orderLine);

        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("order_lines.received_qty=1");
        assertThat(jdbc.queryForObject(sql("SELECT received_qty FROM %s.order_lines WHERE id = ?"), BigDecimal.class,
                orderLine)).isEqualByComparingTo("47");
    }

    private void seedDeliveredOrder() {
        jdbc.update(sql("INSERT INTO %s.orders (id, organization_id, worksite_id, warehouse_id, requested_by, notes, "
                + "required_date, placed_at, status, decided_by, decided_at, version, created_at, created_by, updated_at, "
                + "updated_by) VALUES (?, ?, ?, ?, ?, 'Losa', NULL, ?, 'CLOSED', ?, ?, 3, ?, ?, ?, ?)"),
                order, org, worksite, warehouse, requester, PLACED, auditor, LATER, LATER, auditor, LATER, auditor);
        jdbc.update(sql("INSERT INTO %s.order_lines (id, organization_id, order_id, material_id, sku, unit, "
                + "requested_qty, dispatched_qty, cancelled_qty, received_qty) "
                + "VALUES (?, ?, ?, ?, 'CEM-001', 'BAG', 50, 50, 0, 48)"), orderLine, org, order, material);
        jdbc.update(sql("INSERT INTO %s.dispatches (id, organization_id, order_id, warehouse_id, worksite_id, type, "
                + "status, manifest_code, carrier_name, carrier_document, plate, prepared_at, departed_at, received_at, "
                + "version, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 'COMPLETE', "
                + "'RECEIVED', ?, 'Rímac', '20555666777', 'ABC-123', ?, ?, ?, 2, ?, ?, ?, ?)"),
                dispatch, org, order, warehouse, worksite, MANIFEST, PLACED, LATER, LATER, LATER, auditor, LATER, auditor);
        jdbc.update(sql("INSERT INTO %s.dispatch_lines (id, organization_id, dispatch_id, order_line_id, material_id, "
                + "quantity) VALUES (?, ?, ?, ?, ?, 50)"), dispatchLine, org, dispatch, orderLine, material);
        jdbc.update(sql("INSERT INTO %s.departure_weighings (id, organization_id, dispatch_id, gross_kg, tare_kg, "
                + "net_kg, ticket_photo_url, weighed_at, weighed_by) VALUES (?, ?, ?, 2000, 500, 1500, NULL, ?, ?)"),
                UUID.randomUUID(), org, dispatch, PLACED, requester);
        jdbc.update(sql("INSERT INTO %s.receptions (id, organization_id, dispatch_id, order_id, warehouse_id, "
                + "worksite_id, manifest_code, status, partial_count, last_partial_at, confirmed_by, confirmed_at, "
                + "version, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', 1, "
                + "?, ?, ?, 2, ?, ?, ?, ?)"), reception, org, dispatch, order, warehouse, worksite, MANIFEST, LATER,
                requester, LATER, LATER, requester, LATER, requester);
        jdbc.update(sql("INSERT INTO %s.reception_lines (id, organization_id, reception_id, dispatch_line_id, "
                + "order_line_id, material_id, dispatched_qty, received_qty, stocked_qty, shrinkage_pct) "
                + "VALUES (?, ?, ?, ?, ?, ?, 50, 48, 48, 4)"), receptionLine, org, reception, dispatchLine, orderLine,
                material);
        jdbc.update(sql("INSERT INTO %s.arrival_weighings (id, organization_id, reception_id, gross_kg, tare_kg, net_kg, "
                + "departure_net_kg, tolerance_pct, weighed_at, weighed_by) VALUES (?, ?, ?, 1900, 500, 1400, 1500, 2.5, "
                + "?, ?)"), UUID.randomUUID(), org, reception, LATER, requester);
    }

    private void location(UUID id, String type, String address, String district, String city) {
        jdbc.update(sql("INSERT INTO %s.location_projection (location_id, organization_id, type, name, address, district, "
                + "city, active, updated_at) VALUES (?, ?, ?, 'Sitio', ?, ?, ?, true, ?)"), id, org, type, address,
                district, city, PLACED);
    }

    private UUID assignment(boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update(sql("INSERT INTO %s.assignment_projection (assignment_id, organization_id, user_id, location_id, "
                + "location_type, active, updated_at) VALUES (?, ?, ?, ?, 'WORKSITE', ?, ?)"), id, org, requester, worksite,
                active, PLACED);
        return id;
    }

    private void idempotency(UUID key, byte[] body, UUID user, String fingerprint, boolean expired) {
        jdbc.update(sql("INSERT INTO %s.idempotency_keys (organization_id, idempotency_key, request_method, request_path, "
                + "response_status, response_content_type, response_body, created_at, user_id, actor_role, "
                + "request_fingerprint, response_location, response_expired) VALUES (?, ?, 'POST', ?, 200, "
                + "'application/json', ?, ?, ?, 'SITE_MANAGER', ?, NULL, ?)"), org, key,
                "/api/v1/orders/" + order + "/approve", body, PLACED, user, fingerprint, expired);
    }

    private Map<String, Object> row(String table, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql("SELECT * FROM %s." + table + " WHERE id = ?"), id);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private String address(UUID location) {
        return jdbc.queryForObject(sql("SELECT address FROM %s.location_projection WHERE location_id = ?"), String.class,
                location);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration")
                .target(target).load();
    }

    private String sql(String template) {
        return template.formatted(schema);
    }

    private static Timestamp ts(String instant) {
        return Timestamp.from(Instant.parse(instant));
    }
}
