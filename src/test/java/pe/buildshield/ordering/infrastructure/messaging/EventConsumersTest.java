package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pe.buildshield.ordering.application.OrderCommandService;
import pe.buildshield.ordering.infrastructure.persistence.JdbcOrganizationProjections;
import pe.buildshield.ordering.shared.messaging.EventEnvelope;
import pe.buildshield.ordering.shared.messaging.IdempotentEventProcessor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Cómo cada consumidor traduce los eventos de organization e inventory. */
class EventConsumersTest {

    private static final UUID ORG = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper();
    private final JdbcOrganizationProjections projections = mock(JdbcOrganizationProjections.class);
    private final OrderCommandService orders = mock(OrderCommandService.class);
    private final IdempotentEventProcessor processor = mock(IdempotentEventProcessor.class);
    private final OrganizationEventConsumer organization = new OrganizationEventConsumer(processor, projections);
    private final InventoryEventConsumer inventory = new InventoryEventConsumer(processor, orders);

    private EventEnvelope event(String type, UUID aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, java.time.Instant.now(), ORG, "X", aggregateId.toString(),
                null, 1, json.valueToTree(payload));
    }

    @Test
    void locations_materials_and_assignments_update_the_projections() {
        UUID location = UUID.randomUUID();
        organization.handle(event("LocationRegistered", location, Map.of("locationId", location.toString(),
                "type", "WORKSITE", "name", "Torre", "address", "Av. 1", "district", "Lince", "city", "Lima",
                "active", true)));
        verify(projections).upsertLocation(ORG, location, "WORKSITE", "Torre", "Av. 1", "Lince", "Lima", true);

        organization.handle(event("LocationUpdated", location, Map.of("locationId", location.toString(),
                "type", "WAREHOUSE", "name", "Central", "active", false)));
        verify(projections).upsertLocation(ORG, location, "WAREHOUSE", "Central", null, null, null, false);

        UUID material = UUID.randomUUID();
        organization.handle(event("MaterialRegistered", material, Map.of("materialId", material.toString(),
                "sku", "CEM-001", "name", "Cemento", "unit", "BAG", "tolerancePercent", 2.5, "active", true)));
        verify(projections).upsertMaterial(ORG, material, "CEM-001", "Cemento", "BAG", new BigDecimal("2.5"), true);
        organization.handle(event("MaterialUpdated", material, Map.of("materialId", material.toString(),
                "sku", "CEM-001", "name", "Cemento", "unit", "BAG")));
        verify(projections).upsertMaterial(ORG, material, "CEM-001", "Cemento", "BAG", BigDecimal.ZERO, true);

        UUID assignment = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Map<String, Object> payload = Map.of("assignmentId", assignment.toString(), "userId", user.toString(),
                "locationId", location.toString(), "locationType", "WORKSITE");
        organization.handle(event("StaffAssigned", assignment, payload));
        verify(projections).upsertAssignment(ORG, assignment, user, location, "WORKSITE", true);
        organization.handle(event("StaffUnassigned", assignment, payload));
        verify(projections).upsertAssignment(ORG, assignment, user, location, "WORKSITE", false);
    }

    @Test
    void other_organization_events_are_ignored() {
        organization.handle(event("OrganizationSomething", UUID.randomUUID(), Map.of()));
        verifyNoInteractions(projections);
    }

    @Test
    void stock_answers_drive_the_saga() {
        UUID order = UUID.randomUUID();
        inventory.handle(event("StockReserved", order, Map.of("orderId", order.toString())));
        verify(orders).confirmReservation(order);

        inventory.handle(event("StockReservationRejected", order, Map.of("orderId", order.toString(),
                "reason", "Stock insuficiente")));
        verify(orders).rejectReservation(order, "Stock insuficiente");

        UUID other = UUID.randomUUID();
        inventory.handle(event("StockReservationRejected", other, Map.of()));
        verify(orders).rejectReservation(other, null);

        inventory.handle(event("StockDeducted", order, Map.of()));
        verify(orders).confirmReservation(eq(order));
    }

    @Test
    void listeners_delegate_to_the_idempotent_processor() {
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("t", 0, 0, "k", "{}");
        org.mockito.Mockito.when(processor.parse("{}")).thenReturn(event("StockReserved", UUID.randomUUID(), Map.of()));
        organization.onMessage(record);
        inventory.onMessage(record);
        verify(processor, org.mockito.Mockito.times(2)).process(eq(record), any());
    }
}
