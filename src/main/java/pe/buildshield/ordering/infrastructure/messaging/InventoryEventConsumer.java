package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.OrderCommandService;
import pe.buildshield.ordering.shared.messaging.EventEnvelope;
import pe.buildshield.ordering.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;

import java.util.UUID;

/**
 * Consume el tópico {@code buildshield.inventory}: la respuesta de inventario a la saga de reserva de stock (plan §6).
 *
 * <ul>
 *   <li>{@code StockReserved}: el pedido pasa de Aprobando a EnRevision.</li>
 *   <li>{@code StockReservationRejected}: el pedido vuelve a Registrado con {@code rejectionReason} = {@code reason}.</li>
 * </ul>
 *
 * Si el pedido ya no está Aprobando el evento se ignora (se registra en el log). Cada evento se procesa una sola vez
 * ({@link IdempotentEventProcessor}); los demás tipos ({@code StockDeducted}, {@code StockReceived}, …) se ignoran.
 */
@Component
public class InventoryEventConsumer {

    static final String STOCK_RESERVED = "StockReserved";
    static final String STOCK_RESERVATION_REJECTED = "StockReservationRejected";

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final OrderCommandService orders;

    public InventoryEventConsumer(IdempotentEventProcessor processor, OrderCommandService orders) {
        this.processor = processor;
        this.orders = orders;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.inventory:buildshield.inventory}")
    public void onMessage(ConsumerRecord<String, String> record) {
        // La sesión JPA toma la organización al abrir la transacción del procesador: se fija antes, con la del evento,
        // para que el pedido se lea con el filtro multiempresa correcto (@TenantId).
        EventEnvelope envelope = processor.parse(record.value());
        TenantContext.runAs(new TenantInfo(envelope.organizationId(),
                envelope.actorId() == null ? TenantContext.SYSTEM_USER_ID : envelope.actorId(),
                IdempotentEventProcessor.SYSTEM_ROLE), () -> processor.process(record, this::handle));
    }

    void handle(EventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.eventType()) {
            case STOCK_RESERVED -> orders.confirmReservation(orderId(event));
            case STOCK_RESERVATION_REJECTED -> orders.rejectReservation(orderId(event),
                    payload.hasNonNull("reason") ? payload.get("reason").asText() : null);
            default -> log.debug("Evento {} ignorado por ordering", event.eventType());
        }
    }

    private static UUID orderId(EventEnvelope event) {
        JsonNode payload = event.payload();
        return UUID.fromString(payload.hasNonNull("orderId") ? payload.get("orderId").asText() : event.aggregateId());
    }
}
