package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.OrdersCommandService;
import pe.buildshield.ordering.infrastructure.persistence.JdbcOrganizationProjections;
import pe.buildshield.ordering.shared.messaging.EventEnvelope;
import pe.buildshield.ordering.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code StockEventConsumer} del C4: consume los eventos de stock y los de obras, almacenes, materiales y asignaciones.
 *
 * <p>Tópico {@code buildshield.inventory}, respuesta de inventario a la saga de reserva de stock (plan §6):
 * <ul>
 *   <li>{@code StockReserved}: el pedido pasa de Aprobando a EnRevision.</li>
 *   <li>{@code StockReservationRejected}: el pedido vuelve a Registrado con {@code rejectionReason} = {@code reason}.</li>
 * </ul>
 * Si el pedido ya no está Aprobando el evento se ignora (se registra en el log). Del mismo tópico, las devoluciones de
 * saldos (US43):
 * <ul>
 *   <li>{@code MaterialReturnDispatched}: crea el despacho de retorno, en tránsito, y publica
 *       {@code ReturnDispatchCreated}.</li>
 *   <li>{@code MaterialReturnReceived}: cierra el despacho de retorno y publica {@code ReturnDispatchClosed}.</li>
 * </ul>
 * Una devolución anulada o rechazada no llega a {@code MaterialReturnDispatched}, así que no crea nada.
 *
 * <p>Tópico {@code buildshield.organization}, copias locales:
 * <ul>
 *   <li>{@code LocationRegistered} y {@code LocationUpdated}: {@code location_projection} (obras y almacenes).</li>
 *   <li>{@code MaterialRegistered} y {@code MaterialUpdated}: {@code material_projection} (SKU, unidad y
 *       tolerancia).</li>
 *   <li>{@code StaffAssigned} y {@code StaffUnassigned}: {@code assignment_projection} (qué obras y almacenes ve cada
 *       encargado; la desasignación borra la fila).</li>
 * </ul>
 *
 * Cada evento se procesa una sola vez ({@link IdempotentEventProcessor}); los demás tipos se ignoran.
 */
@Component
public class StockEventConsumer {

    static final String CONSUMER = "StockEventConsumer";
    static final String STOCK_RESERVED = "StockReserved";
    static final String STOCK_RESERVATION_REJECTED = "StockReservationRejected";
    static final String MATERIAL_RETURN_DISPATCHED = "MaterialReturnDispatched";
    static final String MATERIAL_RETURN_RECEIVED = "MaterialReturnReceived";
    static final String LOCATION_REGISTERED = "LocationRegistered";
    static final String LOCATION_UPDATED = "LocationUpdated";
    static final String MATERIAL_REGISTERED = "MaterialRegistered";
    static final String MATERIAL_UPDATED = "MaterialUpdated";
    static final String STAFF_ASSIGNED = "StaffAssigned";
    static final String STAFF_UNASSIGNED = "StaffUnassigned";

    private static final Logger log = LoggerFactory.getLogger(StockEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final OrdersCommandService orders;
    private final DispatchCommandService dispatches;
    private final JdbcOrganizationProjections projections;

    public StockEventConsumer(IdempotentEventProcessor processor, OrdersCommandService orders,
            DispatchCommandService dispatches, JdbcOrganizationProjections projections) {
        this.processor = processor;
        this.orders = orders;
        this.dispatches = dispatches;
        this.projections = projections;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.inventory:buildshield.inventory}")
    public void onInventoryEvent(ConsumerRecord<String, String> record) {
        // La sesión JPA toma la organización al abrir la transacción del procesador: se fija antes, con la del evento,
        // para que el pedido se lea con el filtro multiempresa correcto (@TenantId).
        EventEnvelope envelope = processor.parse(record.value());
        TenantContext.runAs(new TenantInfo(envelope.organizationId(),
                envelope.actorId() == null ? TenantContext.SYSTEM_USER_ID : envelope.actorId(),
                IdempotentEventProcessor.SYSTEM_ROLE), () -> processor.process(CONSUMER, record, this::handleInventory));
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.organization:buildshield.organization}")
    public void onOrganizationEvent(ConsumerRecord<String, String> record) {
        processor.process(CONSUMER, record, this::handleOrganization);
    }

    void handleInventory(EventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.eventType()) {
            case STOCK_RESERVED -> orders.confirmReservation(orderId(event));
            case STOCK_RESERVATION_REJECTED -> orders.rejectReservation(orderId(event), text(payload, "reason"));
            case MATERIAL_RETURN_DISPATCHED -> dispatches.openReturnDispatch(openReturn(event));
            case MATERIAL_RETURN_RECEIVED -> dispatches.closeReturnDispatch(uuid(payload, "returnId"));
            default -> log.debug("Evento {} ignorado por ordering", event.eventType());
        }
    }

    void handleOrganization(EventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.eventType()) {
            case LOCATION_REGISTERED, LOCATION_UPDATED -> projections.upsertLocation(event.organizationId(),
                    uuid(payload, "locationId"), payload.path("type").asText(), text(payload, "name"),
                    text(payload, "address"), text(payload, "district"), text(payload, "city"),
                    payload.path("active").asBoolean(true));
            case MATERIAL_REGISTERED, MATERIAL_UPDATED -> projections.upsertMaterial(event.organizationId(),
                    uuid(payload, "materialId"), text(payload, "sku"), text(payload, "name"), text(payload, "unit"),
                    payload.hasNonNull("tolerancePercent") ? payload.get("tolerancePercent").decimalValue()
                            : BigDecimal.ZERO,
                    payload.path("active").asBoolean(true));
            case STAFF_ASSIGNED -> projections.upsertAssignment(event.organizationId(),
                    uuid(payload, "assignmentId"), uuid(payload, "userId"), uuid(payload, "locationId"),
                    payload.path("locationType").asText());
            case STAFF_UNASSIGNED -> projections.removeAssignment(event.organizationId(),
                    uuid(payload, "assignmentId"));
            default -> log.debug("Evento {} ignorado por ordering", event.eventType());
        }
    }

    private static DispatchCommandService.OpenReturnDispatch openReturn(EventEnvelope event) {
        JsonNode payload = event.payload();
        List<DispatchCommandService.ReturnLine> lines = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            lines.add(new DispatchCommandService.ReturnLine(uuid(line, "materialId"),
                    new BigDecimal(line.path("quantity").asText())));
        }
        return new DispatchCommandService.OpenReturnDispatch(uuid(payload, "returnId"), uuid(payload, "worksiteId"),
                uuid(payload, "warehouseId"), event.occurredAt(), lines);
    }

    private static UUID orderId(EventEnvelope event) {
        JsonNode payload = event.payload();
        return UUID.fromString(payload.hasNonNull("orderId") ? payload.get("orderId").asText() : event.aggregateId());
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private static String text(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asText() : null;
    }
}
