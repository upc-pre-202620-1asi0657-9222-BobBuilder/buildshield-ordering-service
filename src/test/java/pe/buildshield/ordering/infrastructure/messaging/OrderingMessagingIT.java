package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.DispatchView;
import pe.buildshield.ordering.application.OrderCommandService;
import pe.buildshield.ordering.application.OrderQueryService;
import pe.buildshield.ordering.application.OrderView;
import pe.buildshield.ordering.application.ReceptionCommandService;
import pe.buildshield.ordering.application.ReceptionView;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.shared.messaging.Topics;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;
import pe.buildshield.ordering.support.OrderingIntegrationTest;
import pe.buildshield.ordering.support.TestDatabase;
import pe.buildshield.ordering.support.TestEvents;
import pe.buildshield.ordering.support.TestProjections;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Mensajería de ordering contra Kafka embebido y PostgreSQL real: lo que el outbox publica en
 * {@code buildshield.ordering} (sobre y payload del contrato), la saga de reserva de stock con las respuestas de
 * {@code buildshield.inventory}, el consumo idempotente y las copias locales de {@code buildshield.organization}.
 */
@OrderingIntegrationTest
class OrderingMessagingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    OrderCommandService orderCommands;

    @Autowired
    OrderQueryService orderQueries;

    @Autowired
    DispatchCommandService dispatchCommands;

    @Autowired
    ReceptionCommandService receptionCommands;

    @Autowired
    OrderingOutboxPublisher publisher;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private UUID org;
    private TenantInfo rosa;
    private TenantInfo jorge;
    private UUID torre;
    private UUID central;
    private UUID cement;
    private UUID steel;

    @BeforeEach
    void organization() {
        TestDatabase.reset(jdbc);
        org = UUID.randomUUID();
        rosa = new TenantInfo(org, UUID.randomUUID(), "WAREHOUSE_MANAGER");
        jorge = new TenantInfo(org, UUID.randomUUID(), "SITE_MANAGER");
        torre = TestProjections.location(jdbc, org, "WORKSITE", "Torre Norte", true);
        central = TestProjections.location(jdbc, org, "WAREHOUSE", "Almacén Central", true);
        cement = TestProjections.material(jdbc, org, "CEM-001", "Cemento Portland tipo I", "BAG", "2.5");
        steel = TestProjections.material(jdbc, org, "FIE-012", "Fierro corrugado", "UNIT", "1");
        TestProjections.assign(jdbc, org, rosa.userId(), central, "WAREHOUSE");
        TestProjections.assign(jdbc, org, jorge.userId(), torre, "WORKSITE");
    }

    private <T> T as(TenantInfo who, Supplier<T> action) {
        return TenantContext.callAs(who, action);
    }

    private OrderView place() {
        return as(jorge, () -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, null, null,
                List.of(new OrderCommandService.LineRequest(cement, new BigDecimal("50")),
                        new OrderCommandService.LineRequest(steel, new BigDecimal("120"))))));
    }

    private OrderStatus statusOf(UUID orderId) {
        return as(rosa, () -> orderQueries.get(orderId)).status();
    }

    @Test
    void the_stock_reservation_saga_runs_through_kafka() throws Exception {
        OrderView reserved = place();
        OrderView rejected = place();
        as(rosa, () -> orderCommands.approve(reserved.id()));
        as(rosa, () -> orderCommands.approve(rejected.id()));
        assertThat(statusOf(reserved.id())).isEqualTo(OrderStatus.APPROVAL_PENDING);

        publisher.publishPending();
        List<JsonNode> approvals = readOrderingTopic(event -> event.path("eventType").asText().equals("OrderApproved"), 2);
        JsonNode approval = approvals.stream().filter(event -> event.path("aggregateId").asText()
                .equals(reserved.id().toString())).findFirst().orElseThrow();
        assertThat(approval.at("/payload/warehouseId").asText()).isEqualTo(central.toString());
        assertThat(approval.at("/payload/approvedBy").asText()).isEqualTo(rosa.userId().toString());
        assertThat(approval.at("/payload/lines")).hasSize(2);

        kafka.send(Topics.INVENTORY, reserved.id().toString(), TestEvents.stockReserved(json, UUID.randomUUID(), org,
                reserved.id(), central, List.of(Map.of("orderLineId", reserved.lines().get(0).id(), "materialId", cement,
                        "quantity", 50))));
        kafka.send(Topics.INVENTORY, rejected.id().toString(), TestEvents.stockRejected(json, UUID.randomUUID(), org,
                rejected.id(), central, "Stock insuficiente en el almacén para CEM-001 (hay 10, se pidió 50)",
                List.of(Map.of("materialId", cement, "requested", 50, "available", 10))));

        await().atMost(TIMEOUT).until(() -> statusOf(reserved.id()) == OrderStatus.IN_REVIEW);
        await().atMost(TIMEOUT).until(() -> statusOf(rejected.id()) == OrderStatus.REGISTERED);
        OrderView back = as(rosa, () -> orderQueries.get(rejected.id()));
        assertThat(back.rejectionReason()).isEqualTo("Stock insuficiente en el almacén para CEM-001 (hay 10, se pidió 50)");
        assertThat(back.decidedBy()).isNull();
    }

    @Test
    void a_duplicate_event_is_processed_once() {
        OrderView order = place();
        as(rosa, () -> orderCommands.approve(order.id()));
        UUID eventId = UUID.randomUUID();
        String rejection = TestEvents.stockRejected(json, eventId, org, order.id(), central, "Sin stock", List.of());

        kafka.send(Topics.INVENTORY, order.id().toString(), rejection);
        await().atMost(TIMEOUT).until(() -> statusOf(order.id()) == OrderStatus.REGISTERED);
        as(rosa, () -> orderCommands.approve(order.id()));
        kafka.send(Topics.INVENTORY, order.id().toString(), rejection);
        UUID marker = UUID.randomUUID();
        kafka.send(Topics.INVENTORY, order.id().toString(), TestEvents.stockReserved(json, marker, org, order.id(),
                central, List.of()));

        await().atMost(TIMEOUT).until(() -> processed(marker));
        assertThat(statusOf(order.id())).as("el duplicado no la devolvió a Registrado")
                .isEqualTo(OrderStatus.IN_REVIEW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void events_reach_the_ordering_topic_with_the_envelope_and_the_contract_payload() throws Exception {
        OrderView order = place();
        as(rosa, () -> orderCommands.approve(order.id()));
        as(new TenantInfo(org, TenantContext.SYSTEM_USER_ID, "SYSTEM"), () -> orderCommands.confirmReservation(order.id()));
        OrderView approved = as(rosa, () -> orderQueries.get(order.id()));
        DispatchView dispatch = as(rosa, () -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(
                order.id(), approved.lines().stream().map(line -> new DispatchCommandService.LineRequest(line.id(),
                        line.requested())).toList())));
        as(rosa, () -> dispatchCommands.assignCarrier(dispatch.id(), "Transportes Rímac SAC", "20555666777", "ABC-123"));
        as(rosa, () -> dispatchCommands.recordDepartureWeighing(dispatch.id(), new BigDecimal("2550.5"),
                new BigDecimal("1050.5"), null));
        as(rosa, () -> dispatchCommands.depart(dispatch.id()));
        as(rosa, () -> dispatchCommands.reportIncident(dispatch.id(), IncidentType.DELAY, "Tráfico"));
        ReceptionView reception = as(jorge, () -> receptionCommands.start(dispatch.id(), null));
        as(jorge, () -> receptionCommands.recordArrivalWeighing(reception.id(), new BigDecimal("2530"),
                new BigDecimal("1050.5"), null));
        UUID cementLine = reception.lines().stream().filter(line -> line.materialId().equals(cement)).findFirst()
                .orElseThrow().id();
        UUID steelLine = reception.lines().stream().filter(line -> line.materialId().equals(steel)).findFirst()
                .orElseThrow().id();
        as(jorge, () -> receptionCommands.recordLine(reception.id(), cementLine, new BigDecimal("20")));
        as(jorge, () -> receptionCommands.receivePartial(reception.id()));
        as(jorge, () -> receptionCommands.recordLine(reception.id(), cementLine, new BigDecimal("49")));
        as(jorge, () -> receptionCommands.recordLine(reception.id(), steelLine, new BigDecimal("110")));
        as(jorge, () -> receptionCommands.confirm(reception.id()));

        int published = 0;
        for (int pass = 0; pass < 3; pass++) {
            published += publisher.publishPending();
        }
        assertThat(published).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class))
                .isZero();

        List<JsonNode> events = readOrderingTopic(event -> true, 12);
        assertThat(events).extracting(event -> event.path("eventType").asText()).containsExactlyInAnyOrder(
                "OrderRegistered", "OrderApproved", "DispatchPrepared", "DispatchCarrierAssigned",
                "DispatchDepartureWeighed", "DispatchDeparted", "TransitIncidentReported", "ReceptionOpened",
                "ArrivalWeighed", "ReceptionPartiallyReceived", "ReceptionConfirmed", "OrderClosed");
        for (JsonNode event : events) {
            List<String> envelope = new ArrayList<>();
            event.fieldNames().forEachRemaining(envelope::add);
            assertThat(envelope).containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "organizationId",
                    "aggregateType", "aggregateId", "actorId", "version", "payload");
            assertThat(event.path("version").asInt()).isEqualTo(1);
        }
        JsonNode departed = find(events, "DispatchDeparted");
        assertThat(departed.path("aggregateType").asText()).isEqualTo("DISPATCH");
        assertThat(departed.path("aggregateId").asText()).isEqualTo(dispatch.id().toString());
        assertThat(departed.path("actorId").asText()).isEqualTo(rosa.userId().toString());
        assertThat(departed.at("/payload/manifestCode").asText()).isEqualTo(dispatch.manifestCode());
        assertThat(departed.at("/payload/lines")).hasSize(2);

        JsonNode partial = find(events, "ReceptionPartiallyReceived");
        assertThat(partial.at("/payload/partialCount").asInt()).isEqualTo(1);
        assertThat(partial.at("/payload/lines")).singleElement().satisfies(line -> {
            assertThat(line.path("receptionLineId").asText()).isEqualTo(cementLine.toString());
            assertThat(line.path("receivedQuantity").decimalValue()).isEqualByComparingTo("20");
            assertThat(line.path("deltaQuantity").decimalValue()).isEqualByComparingTo("20");
        });

        JsonNode confirmed = find(events, "ReceptionConfirmed");
        assertThat(confirmed.path("aggregateType").asText()).isEqualTo("RECEPTION");
        assertThat(confirmed.path("actorId").asText()).isEqualTo(jorge.userId().toString());
        JsonNode payload = confirmed.path("payload");
        List<String> fields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        // jsonb no conserva el orden de las claves: se comparan los nombres.
        assertThat(fields).containsExactlyInAnyOrder("receptionId", "dispatchId", "orderId", "manifestCode", "worksiteId",
                "warehouseId", "confirmedBy", "confirmedAt", "lines");
        assertThat(payload.path("worksiteId").asText()).isEqualTo(torre.toString());
        assertThat(payload.path("warehouseId").asText()).isEqualTo(central.toString());
        assertThat(payload.path("confirmedBy").asText()).isEqualTo(jorge.userId().toString());
        for (JsonNode line : payload.path("lines")) {
            List<String> lineFields = new ArrayList<>();
            line.fieldNames().forEachRemaining(lineFields::add);
            assertThat(lineFields).containsExactlyInAnyOrder("receptionLineId", "dispatchLineId", "orderLineId", "materialId",
                    "sku", "materialName", "unit", "requested", "dispatched", "received", "deltaQuantity",
                    "shrinkagePercent", "tolerancePercent", "withinTolerance");
        }
        JsonNode cementConfirmed = lineOf(payload, cement);
        assertThat(cementConfirmed.path("sku").asText()).isEqualTo("CEM-001");
        assertThat(cementConfirmed.path("materialName").asText()).isEqualTo("Cemento Portland tipo I");
        assertThat(cementConfirmed.path("unit").asText()).isEqualTo("BAG");
        assertThat(cementConfirmed.path("requested").decimalValue()).isEqualByComparingTo("50");
        assertThat(cementConfirmed.path("dispatched").decimalValue()).isEqualByComparingTo("50");
        assertThat(cementConfirmed.path("received").decimalValue()).isEqualByComparingTo("49");
        assertThat(cementConfirmed.path("deltaQuantity").decimalValue()).isEqualByComparingTo("29");
        assertThat(cementConfirmed.path("shrinkagePercent").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(cementConfirmed.path("tolerancePercent").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(cementConfirmed.path("withinTolerance").asBoolean()).isTrue();
        JsonNode steelConfirmed = lineOf(payload, steel);
        assertThat(steelConfirmed.path("deltaQuantity").decimalValue()).isEqualByComparingTo("110");
        assertThat(steelConfirmed.path("shrinkagePercent").decimalValue()).isEqualByComparingTo("8.33");
        assertThat(steelConfirmed.path("withinTolerance").asBoolean()).isFalse();

        JsonNode closed = find(events, "OrderClosed");
        assertThat(closed.at("/payload/reason").asText()).isEqualTo("Recepción completa");
        JsonNode weighed = find(events, "ArrivalWeighed");
        assertThat(weighed.at("/payload/differencePercent").decimalValue()).isEqualByComparingTo("1.37");
        assertThat(weighed.at("/payload/withinTolerance").asBoolean()).as("menor tolerancia 1 %").isFalse();
    }

    private static JsonNode find(List<JsonNode> events, String type) {
        return events.stream().filter(event -> event.path("eventType").asText().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError(type));
    }

    private static JsonNode lineOf(JsonNode payload, UUID material) {
        for (JsonNode line : payload.path("lines")) {
            if (line.path("materialId").asText().equals(material.toString())) {
                return line;
            }
        }
        throw new AssertionError("línea de " + material);
    }

    @Test
    void organization_events_populate_the_projections() {
        UUID otherOrg = UUID.randomUUID();
        UUID worksite = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        kafka.send(Topics.ORGANIZATION, worksite.toString(), TestEvents.location(json, "LocationRegistered", otherOrg,
                worksite, "WORKSITE", "Torre Sur", true));
        kafka.send(Topics.ORGANIZATION, worksite.toString(), TestEvents.location(json, "LocationUpdated", otherOrg,
                worksite, "WORKSITE", "Torre Sur II", false));
        kafka.send(Topics.ORGANIZATION, material.toString(), TestEvents.material(json, "MaterialRegistered", otherOrg,
                material, "ARE-001", "Arena gruesa", "M3", new BigDecimal("3.5"), true));
        kafka.send(Topics.ORGANIZATION, assignment.toString(), TestEvents.assignment(json, "StaffAssigned", otherOrg,
                assignment, user, worksite, "WORKSITE"));

        await().atMost(TIMEOUT).until(() -> count("SELECT count(*) FROM assignment_projection WHERE assignment_id = ? "
                + "AND active", assignment) == 1);
        await().atMost(TIMEOUT).until(() -> count("SELECT count(*) FROM location_projection WHERE location_id = ? "
                + "AND name = 'Torre Sur II' AND NOT active", worksite) == 1);
        assertThat(jdbc.queryForMap("SELECT organization_id, type, district, city FROM location_projection "
                + "WHERE location_id = ?", worksite))
                .containsEntry("organization_id", otherOrg).containsEntry("type", "WORKSITE")
                .containsEntry("district", "Miraflores").containsEntry("city", "Lima");
        await().atMost(TIMEOUT).until(() -> count("SELECT count(*) FROM material_projection WHERE material_id = ?",
                material) == 1);
        assertThat(jdbc.queryForObject("SELECT tolerance_percent FROM material_projection WHERE material_id = ?",
                BigDecimal.class, material)).isEqualByComparingTo("3.5");

        kafka.send(Topics.ORGANIZATION, assignment.toString(), TestEvents.assignment(json, "StaffUnassigned", otherOrg,
                assignment, user, worksite, "WORKSITE"));
        await().atMost(TIMEOUT).until(() -> count("SELECT count(*) FROM assignment_projection WHERE assignment_id = ? "
                + "AND NOT active", assignment) == 1);
        assertThat(TenantContext.callAs(new TenantInfo(otherOrg, user, "SITE_MANAGER"),
                () -> jdbc.queryForObject("SELECT count(*) FROM assignment_projection WHERE user_id = ? AND active",
                        Long.class, user))).isZero();
    }

    private boolean processed(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ?)", Boolean.class, eventId));
    }

    private long count(String sql, UUID id) {
        Long count = jdbc.queryForObject(sql, Long.class, id);
        return count == null ? 0 : count;
    }

    private List<JsonNode> readOrderingTopic(java.util.function.Predicate<JsonNode> filter, int expected)
            throws Exception {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "ordering-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<JsonNode> found = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of(Topics.ORDERING));
            long deadline = System.currentTimeMillis() + 20_000;
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (envelope.path("organizationId").asText().equals(org.toString()) && filter.test(envelope)) {
                        assertThat(record.key()).isEqualTo(envelope.path("aggregateId").asText());
                        found.add(envelope);
                    }
                }
            }
        }
        assertThat(found).hasSize(expected);
        return found;
    }
}
