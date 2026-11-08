package pe.buildshield.ordering.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.DispatchQueryService;
import pe.buildshield.ordering.application.DispatchView;
import pe.buildshield.ordering.application.OrdersCommandService;
import pe.buildshield.ordering.application.OrdersQueryService;
import pe.buildshield.ordering.application.OrderView;
import pe.buildshield.ordering.application.ReceptionCommandService;
import pe.buildshield.ordering.application.ReceptionQueryService;
import pe.buildshield.ordering.application.ReceptionView;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;
import pe.buildshield.ordering.shared.idempotency.IdempotencyRecord;
import pe.buildshield.ordering.shared.idempotency.JdbcIdempotencyStore;
import pe.buildshield.ordering.shared.messaging.EventEnvelope;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;
import pe.buildshield.ordering.support.OrderingIntegrationTest;
import pe.buildshield.ordering.support.TestDatabase;
import pe.buildshield.ordering.support.TestProjections;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El esquema coincide con el ERD ampliado por el equipo y lo que salió de las tablas se sigue respondiendo igual:
 * derivado con JOIN o de las copias locales.
 */
@OrderingIntegrationTest
class OrderingErdAlignmentIT {

    /** Columnas de cada tabla en el ERD ampliado. */
    private static final Map<String, Set<String>> ERD = Map.ofEntries(
            Map.entry("orders", Set.of("id", "organization_id", "worksite_id", "warehouse_id", "status", "required_date",
                    "rejection_reason", "notes", "decided_by", "decided_at", "created_by", "created_at", "version",
                    "updated_at", "updated_by")),
            Map.entry("order_lines", Set.of("id", "organization_id", "order_id", "material_id", "requested_qty",
                    "dispatched_qty", "cancelled_qty")),
            Map.entry("dispatches", Set.of("id", "organization_id", "order_id", "warehouse_id", "worksite_id", "type",
                    "status", "manifest_code", "carrier_name", "carrier_document", "plate", "departed_at", "cancelled_at",
                    "cancelled_by", "cancellation_reason", "created_at", "version", "created_by", "updated_at",
                    "updated_by")),
            Map.entry("dispatch_lines", Set.of("id", "organization_id", "dispatch_id", "order_line_id", "material_id",
                    "quantity")),
            Map.entry("departure_weighings", Set.of("id", "organization_id", "dispatch_id", "gross_kg", "tare_kg",
                    "net_kg", "ticket_photo_url", "weighed_by", "weighed_at")),
            Map.entry("transit_incidents", Set.of("id", "organization_id", "dispatch_id", "type", "observation",
                    "photo_url", "created_by", "created_at")),
            Map.entry("receptions", Set.of("id", "organization_id", "dispatch_id", "status", "partial_count",
                    "last_partial_at", "confirmed_by", "confirmed_at", "version", "created_at", "created_by",
                    "updated_at", "updated_by")),
            Map.entry("reception_lines", Set.of("id", "organization_id", "reception_id", "dispatch_line_id",
                    "received_qty", "stocked_qty", "shrinkage_pct")),
            Map.entry("arrival_weighings", Set.of("id", "organization_id", "reception_id", "gross_kg", "tare_kg",
                    "net_kg", "tolerance_pct", "ticket_photo_url", "weighed_by", "weighed_at")),
            Map.entry("location_projection", Set.of("location_id", "organization_id", "type", "name", "address",
                    "active", "updated_at")),
            Map.entry("material_projection", Set.of("material_id", "organization_id", "sku", "name", "unit",
                    "tolerance_percent", "active", "updated_at")),
            Map.entry("assignment_projection", Set.of("assignment_id", "organization_id", "user_id", "location_id",
                    "location_type", "updated_at")),
            Map.entry("outbox_events", Set.of("id", "organization_id", "aggregate_id", "event_type", "correlation_id",
                    "payload", "occurred_at", "published_at")),
            Map.entry("processed_events", Set.of("event_id", "consumer", "processed_at")),
            Map.entry("idempotency_keys", Set.of("key", "organization_id", "endpoint", "request_hash", "status",
                    "response_body", "expires_at")));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    OrdersCommandService orderCommands;

    @Autowired
    OrdersQueryService orderQueries;

    @Autowired
    DispatchCommandService dispatchCommands;

    @Autowired
    DispatchQueryService dispatchQueries;

    @Autowired
    ReceptionCommandService receptionCommands;

    @Autowired
    ReceptionQueryService receptionQueries;

    private final UUID org = UUID.randomUUID();
    private final TenantInfo rosa = new TenantInfo(org, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private final TenantInfo jorge = new TenantInfo(org, UUID.randomUUID(), "SITE_MANAGER");
    private UUID torre;
    private UUID central;
    private UUID cement;
    private UUID steel;

    @BeforeEach
    void organization() {
        TestDatabase.reset(jdbc);
        torre = TestProjections.location(jdbc, org, "WORKSITE", "Torre Norte", true);
        central = TestProjections.location(jdbc, org, "WAREHOUSE", "Almacén Central", true);
        cement = TestProjections.material(jdbc, org, "CEM-001", "Cemento", "BAG", "2.5");
        steel = TestProjections.material(jdbc, org, "FIE-012", "Fierro", "UNIT", "1");
        TestProjections.assign(jdbc, org, rosa.userId(), central, "WAREHOUSE");
        TestProjections.assign(jdbc, org, jorge.userId(), torre, "WORKSITE");
    }

    private <T> T as(TenantInfo who, Supplier<T> action) {
        return TenantContext.callAs(who, action);
    }

    @Test
    void the_schema_has_the_columns_and_foreign_keys_of_the_erd() {
        Map<String, Set<String>> actual = new HashMap<>();
        jdbc.query("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name <> 'flyway_schema_history'", rs -> {
                    actual.computeIfAbsent(rs.getString(1), ignored -> new HashSet<>()).add(rs.getString(2));
                });
        assertThat(actual.keySet()).isEqualTo(ERD.keySet());
        ERD.forEach((table, columns) -> assertThat(actual.get(table)).as(table).isEqualTo(columns));

        assertThat(jdbc.queryForList("""
                SELECT conrelid::regclass::text || '.' || a.attname || '>' || confrelid::regclass::text
                FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace""", String.class))
                .containsExactlyInAnyOrder("order_lines.order_id>orders", "dispatches.order_id>orders",
                        "dispatch_lines.dispatch_id>dispatches", "dispatch_lines.order_line_id>order_lines",
                        "departure_weighings.dispatch_id>dispatches", "transit_incidents.dispatch_id>dispatches",
                        "receptions.dispatch_id>dispatches", "reception_lines.reception_id>receptions",
                        "reception_lines.dispatch_line_id>dispatch_lines", "arrival_weighings.reception_id>receptions");

        assertThat(nullable("dispatches", "order_id")).isTrue();
        assertThat(nullable("dispatch_lines", "order_line_id")).isTrue();
        assertThat(nullable("departure_weighings", "ticket_photo_url")).isTrue();
        assertThat(nullable("orders", "required_date")).isTrue();
        assertThat(nullable("arrival_weighings", "tolerance_pct")).isTrue();
    }

    @Test
    void what_left_the_tables_is_still_answered() {
        OrderView order = approved(LocalDate.of(2026, 11, 10));
        assertThat(order.requiredDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(order.requestedBy()).isEqualTo(jorge.userId());
        assertThat(order.placedAt()).isNotNull();
        assertThat(order.lines()).extracting(OrderView.LineView::sku).containsExactly("CEM-001", "FIE-012");
        assertThat(order.lines()).extracting(OrderView.LineView::unit).containsExactly("BAG", "UNIT");

        DispatchView dispatch = departed(order, null);
        assertThat(dispatch.preparedAt()).isNotNull();

        ReceptionView reception = as(jorge, () -> receptionCommands.start(dispatch.id(), null));
        assertThat(reception.orderId()).isEqualTo(order.id());
        assertThat(reception.worksiteId()).isEqualTo(torre);
        assertThat(reception.warehouseId()).isEqualTo(central);
        assertThat(reception.lines()).allSatisfy(line -> assertThat(line.orderLineId()).isNotNull());
        ReceptionView.LineView first = reception.lines().get(0);
        assertThat(first.dispatchedQty()).isEqualByComparingTo("50");
        assertThat(first.materialId()).isEqualTo(cement);

        as(jorge, () -> receptionCommands.recordArrivalWeighing(reception.id(), new BigDecimal("1900"),
                new BigDecimal("500"), null));
        ReceptionView.ArrivalWeighingView weighing = as(jorge, () -> receptionQueries.get(reception.id()))
                .arrivalWeighing();
        assertThat(weighing.departureNetKg()).isEqualByComparingTo("1500");
        assertThat(weighing.tolerancePercent()).isEqualByComparingTo("2.5");
        assertThat(weighing.withinTolerance()).isFalse();

        as(jorge, () -> receptionCommands.recordLine(reception.id(), first.id(), new BigDecimal("20")));
        as(jorge, () -> receptionCommands.receivePartial(reception.id()));
        assertThat(as(jorge, () -> orderQueries.get(order.id())).lines().get(0).received()).isEqualByComparingTo("20");

        as(jorge, () -> receptionCommands.recordLine(reception.id(), first.id(), new BigDecimal("49")));
        ReceptionView confirmed = as(jorge, () -> receptionCommands.confirm(reception.id()));
        assertThat(confirmed.status()).isEqualTo(ReceptionStatus.CONFIRMED);
        OrderView after = as(jorge, () -> orderQueries.get(order.id()));
        assertThat(after.lines().get(0).received()).isEqualByComparingTo("49");
        DispatchView received = as(rosa, () -> dispatchQueries.get(dispatch.id()));
        assertThat(received.status()).isEqualTo(DispatchStatus.RECEIVED);
        assertThat(received.receivedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM receptions", Timestamp.class).toInstant())
                .isEqualTo(received.receivedAt());
    }

    @Test
    void an_order_without_required_date_stores_and_publishes_null() throws Exception {
        OrderView order = as(jorge, () -> orderCommands.place(new OrdersCommandService.PlaceOrder(torre, central, null,
                null, List.of(new OrdersCommandService.LineRequest(cement, BigDecimal.TEN)))));

        assertThat(order.requiredDate()).isNull();
        assertThat(jdbc.queryForObject("SELECT required_date FROM orders", LocalDate.class)).isNull();
        assertThat(as(jorge, () -> orderQueries.get(order.id())).requiredDate()).isNull();
        EventEnvelope event = json.readValue(jdbc.queryForObject("SELECT payload::text FROM outbox_events", String.class),
                EventEnvelope.class);
        assertThat(event.eventType()).isEqualTo("OrderRegistered");
        assertThat(event.payload().has("requiredDate")).isTrue();
        assertThat(event.payload().get("requiredDate").isNull()).isTrue();
    }

    @Test
    void a_departure_weighing_without_photo_stores_null() {
        DispatchView dispatch = departed(approved(null), null);

        assertThat(jdbc.queryForObject("SELECT ticket_photo_url FROM departure_weighings", String.class)).isNull();
        assertThat(as(rosa, () -> dispatchQueries.get(dispatch.id())).departureWeighing().ticketPhotoUrl()).isNull();
    }

    @Test
    void a_later_tolerance_change_does_not_change_an_earlier_weighing() {
        ReceptionView reception = weighedReception();
        assertThat(jdbc.queryForObject("SELECT tolerance_pct FROM arrival_weighings", BigDecimal.class))
                .isEqualByComparingTo("2.5");

        setCementTolerance("0.5");
        assertThatWeighingIs(reception, "2.5", true);

        setCementTolerance("2.5");
        ReceptionView narrow = weighedReception(new BigDecimal("1900"));
        assertThatWeighingIs(narrow, "2.5", false);
        setCementTolerance("50");
        assertThatWeighingIs(narrow, "2.5", false);
    }

    @Test
    void a_weighing_older_than_the_column_uses_the_current_tolerance_and_is_not_rewritten() {
        ReceptionView reception = weighedReception();
        jdbc.update("UPDATE arrival_weighings SET tolerance_pct = NULL");

        assertThatWeighingIs(reception, "2.5", true);
        setCementTolerance("0.5");
        assertThatWeighingIs(reception, "0.5", false);

        as(jorge, () -> receptionCommands.recordLine(reception.id(), reception.lines().get(0).id(), new BigDecimal("20")));
        as(jorge, () -> receptionCommands.receivePartial(reception.id()));
        assertThat(jdbc.queryForObject("SELECT tolerance_pct FROM arrival_weighings", BigDecimal.class)).isNull();
    }

    @Test
    void a_return_dispatch_has_no_order_and_opens_no_reception() {
        UUID dispatch = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO dispatches (id, organization_id, order_id, warehouse_id, worksite_id, type, status,
                    manifest_code, carrier_name, carrier_document, plate, departed_at, version, created_at, created_by,
                    updated_at, updated_by)
                VALUES (?, ?, NULL, ?, ?, 'RETURN', 'IN_TRANSIT', 'MAN-20261102-7KQ2M9XA', 'Rímac', '20555666777',
                    'ABC-123', ?, 0, ?, ?, ?, ?)""", dispatch, org, central, torre, now, now, rosa.userId(), now,
                rosa.userId());
        jdbc.update("INSERT INTO dispatch_lines (id, organization_id, dispatch_id, order_line_id, material_id, quantity) "
                + "VALUES (?, ?, ?, NULL, ?, 5)", line, org, dispatch, cement);

        DispatchView view = as(rosa, () -> dispatchQueries.get(dispatch));
        assertThat(view.orderId()).isNull();
        assertThat(view.type()).isEqualTo(DispatchType.RETURN);
        assertThat(view.lines().get(0).orderLineId()).isNull();
        assertThat(as(rosa, () -> dispatchQueries.list(UUID.randomUUID(), null))).isEmpty();

        assertThatThrownBy(() -> as(jorge, () -> receptionCommands.start(dispatch, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("retorno");
    }

    @Test
    void a_prepared_return_dispatch_is_cancelled_without_touching_orders() {
        UUID dispatch = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO dispatches (id, organization_id, order_id, warehouse_id, worksite_id, type, status,
                    manifest_code, version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, NULL, ?, ?, 'RETURN', 'PREPARED', 'MAN-20261102-8KQ2M9XA', 0, ?, ?, ?, ?)""", dispatch, org,
                central, torre, now, rosa.userId(), now, rosa.userId());
        jdbc.update("INSERT INTO dispatch_lines (id, organization_id, dispatch_id, order_line_id, material_id, quantity) "
                + "VALUES (?, ?, ?, NULL, ?, 5)", UUID.randomUUID(), org, dispatch, cement);

        DispatchView cancelled = as(rosa, () -> dispatchCommands.cancel(dispatch, "Ya no se devuelve"));

        assertThat(cancelled.status()).isEqualTo(DispatchStatus.CANCELLED);
        assertThat(cancelled.orderId()).isNull();
        assertThat(as(rosa, () -> dispatchQueries.get(dispatch)).cancellationReason()).isEqualTo("Ya no se devuelve");
    }

    @Test
    void the_outbox_payload_is_the_full_envelope() throws Exception {
        OrderView order = as(jorge, () -> orderCommands.place(new OrdersCommandService.PlaceOrder(torre, central, "Losa",
                LocalDate.of(2026, 11, 10), List.of(new OrdersCommandService.LineRequest(cement, BigDecimal.TEN)))));

        Map<String, Object> row = jdbc.queryForMap("SELECT id, aggregate_id, event_type, payload::text AS payload "
                + "FROM outbox_events");
        assertThat(row).containsEntry("aggregate_id", order.id()).containsEntry("event_type", "OrderRegistered");
        EventEnvelope envelope = json.readValue((String) row.get("payload"), EventEnvelope.class);
        assertThat(envelope.eventId()).isEqualTo(row.get("id"));
        assertThat(envelope.aggregateType()).isEqualTo("ORDER");
        assertThat(envelope.actorId()).isEqualTo(jorge.userId());
        JsonNode payload = envelope.payload();
        assertThat(payload.path("orderId").asText()).isEqualTo(order.id().toString());
        assertThat(payload.path("notes").asText()).isEqualTo("Losa");
    }

    @Test
    void the_idempotency_store_keeps_everything_it_needs_in_the_erd_columns() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(jdbc);
        UUID key = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String path = "/api/v1/receptions/" + UUID.randomUUID() + "/lines/" + UUID.randomUUID();
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        Instant created = Instant.parse("2026-10-20T05:00:00Z");
        store.save(new IdempotencyRecord(org, key, "PUT", path, 200, "application/json", body, created, user,
                "SITE_MANAGER", "a".repeat(64), "/api/v1/x", false));

        assertThat(jdbc.queryForObject("SELECT endpoint FROM idempotency_keys WHERE key = ?", String.class, key))
                .isEqualTo("PUT /api/v1/receptions/{id}/lines/{id}");
        IdempotencyRecord found = store.find(org, key).orElseThrow();
        assertThat(found.requestMethod()).isEqualTo("PUT");
        assertThat(found.requestPath()).isEqualTo(path);
        assertThat(found.responseStatus()).isEqualTo(200);
        assertThat(found.responseContentType()).isEqualTo("application/json");
        assertThat(found.responseBody()).isEqualTo(body);
        assertThat(found.createdAt()).isEqualTo(created);
        assertThat(found.userId()).isEqualTo(user);
        assertThat(found.role()).isEqualTo("SITE_MANAGER");
        assertThat(found.requestFingerprint()).isEqualTo("a".repeat(64));
        assertThat(found.responseLocation()).isEqualTo("/api/v1/x");
        assertThat(found.responseExpired()).isFalse();
        assertThat(store.find(UUID.randomUUID(), key)).isEmpty();

        assertThat(store.retireResponsesCreatedBefore(created)).isZero();
        assertThat(store.retireResponsesCreatedBefore(created.plusSeconds(1))).isEqualTo(1);
        IdempotencyRecord retired = store.find(org, key).orElseThrow();
        assertThat(retired.responseExpired()).isTrue();
        assertThat(retired.responseBody()).isNull();
        assertThat(retired.responseContentType()).isNull();
        assertThat(retired.responseLocation()).isNull();
        assertThat(retired.userId()).isEqualTo(user);
        assertThat(retired.requestPath()).isEqualTo(path);

        UUID legacy = UUID.randomUUID();
        store.save(new IdempotencyRecord(org, legacy, "POST", "/api/v1/orders", 201, null, null, created));
        assertThat(store.find(org, legacy).orElseThrow().requestFingerprint()).isNull();
    }

    /** Salida de 1500 kg y llegada de 1480 kg (1,33 %), con cemento: tolerancia de 2,5 %. */
    private ReceptionView weighedReception() {
        return weighedReception(new BigDecimal("1980"));
    }

    private ReceptionView weighedReception(BigDecimal grossKg) {
        DispatchView dispatch = departed(approved(null), null);
        ReceptionView reception = as(jorge, () -> receptionCommands.start(dispatch.id(), null));
        as(jorge, () -> receptionCommands.recordArrivalWeighing(reception.id(), grossKg, new BigDecimal("500"), null));
        return reception;
    }

    private void setCementTolerance(String percent) {
        jdbc.update("UPDATE material_projection SET tolerance_percent = ? WHERE material_id = ?",
                new BigDecimal(percent), cement);
    }

    private void assertThatWeighingIs(ReceptionView reception, String tolerance, boolean within) {
        ReceptionView.ArrivalWeighingView weighing = as(jorge, () -> receptionQueries.get(reception.id()))
                .arrivalWeighing();
        assertThat(weighing.tolerancePercent()).isEqualByComparingTo(tolerance);
        assertThat(weighing.withinTolerance()).isEqualTo(within);
    }

    private boolean nullable(String table, String column) {
        return "YES".equals(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?", String.class, table, column));
    }

    private OrderView approved(LocalDate requiredDate) {
        OrderView order = as(jorge, () -> orderCommands.place(new OrdersCommandService.PlaceOrder(torre, central, null,
                requiredDate, List.of(new OrdersCommandService.LineRequest(steel, new BigDecimal("120")),
                new OrdersCommandService.LineRequest(cement, new BigDecimal("50"))))));
        as(rosa, () -> orderCommands.approve(order.id()));
        as(new TenantInfo(org, TenantContext.SYSTEM_USER_ID, "SYSTEM"), () -> orderCommands.confirmReservation(order.id()));
        return as(rosa, () -> orderQueries.get(order.id()));
    }

    private DispatchView departed(OrderView order, String photo) {
        DispatchView dispatch = as(rosa, () -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(
                order.id(), List.of(new DispatchCommandService.LineRequest(order.lines().get(0).id(), new BigDecimal("50"))))));
        as(rosa, () -> dispatchCommands.assignCarrier(dispatch.id(), "Transportes Rímac SAC", "20555666777", "ABC-123"));
        as(rosa, () -> dispatchCommands.recordDepartureWeighing(dispatch.id(), new BigDecimal("2000"),
                new BigDecimal("500"), photo));
        return as(rosa, () -> dispatchCommands.depart(dispatch.id()));
    }
}
