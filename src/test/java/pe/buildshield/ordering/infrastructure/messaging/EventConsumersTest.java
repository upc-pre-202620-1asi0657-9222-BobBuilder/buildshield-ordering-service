package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.OrdersCommandService;
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

/** Cómo el consumidor traduce los eventos de organization e inventory. */
class EventConsumersTest {

    private static final UUID ORG = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper();
    private final JdbcOrganizationProjections projections = mock(JdbcOrganizationProjections.class);
    private final OrdersCommandService orders = mock(OrdersCommandService.class);
    private final DispatchCommandService dispatches = mock(DispatchCommandService.class);
    private final IdempotentEventProcessor processor = mock(IdempotentEventProcessor.class);
    private final StockEventConsumer consumer = new StockEventConsumer(processor, orders, dispatches, projections);

    private EventEnvelope event(String type, UUID aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, java.time.Instant.now(), ORG, "X", aggregateId.toString(),
                null, 1, json.valueToTree(payload));
    }

    @Test
    void locations_materials_and_assignments_update_the_projections() {
        UUID location = UUID.randomUUID();
        consumer.handleOrganization(event("LocationRegistered", location, Map.of("locationId", location.toString(),
                "type", "WORKSITE", "name", "Torre", "address", "Av. 1", "district", "Lince", "city", "Lima",
                "active", true)));
        verify(projections).upsertLocation(ORG, location, "WORKSITE", "Torre", "Av. 1", "Lince", "Lima", true);

        consumer.handleOrganization(event("LocationUpdated", location, Map.of("locationId", location.toString(),
                "type", "WAREHOUSE", "name", "Central", "active", false)));
        verify(projections).upsertLocation(ORG, location, "WAREHOUSE", "Central", null, null, null, false);

        UUID material = UUID.randomUUID();
        consumer.handleOrganization(event("MaterialRegistered", material, Map.of("materialId", material.toString(),
                "sku", "CEM-001", "name", "Cemento", "unit", "BAG", "tolerancePercent", 2.5, "active", true)));
        verify(projections).upsertMaterial(ORG, material, "CEM-001", "Cemento", "BAG", new BigDecimal("2.5"), true);
        consumer.handleOrganization(event("MaterialUpdated", material, Map.of("materialId", material.toString(),
                "sku", "CEM-001", "name", "Cemento", "unit", "BAG")));
        verify(projections).upsertMaterial(ORG, material, "CEM-001", "Cemento", "BAG", BigDecimal.ZERO, true);

        UUID assignment = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Map<String, Object> payload = Map.of("assignmentId", assignment.toString(), "userId", user.toString(),
                "locationId", location.toString(), "locationType", "WORKSITE");
        consumer.handleOrganization(event("StaffAssigned", assignment, payload));
        verify(projections).upsertAssignment(ORG, assignment, user, location, "WORKSITE");
        consumer.handleOrganization(event("StaffUnassigned", assignment, payload));
        verify(projections).removeAssignment(ORG, assignment);
    }

    @Test
    void other_organization_events_are_ignored() {
        consumer.handleOrganization(event("OrganizationSomething", UUID.randomUUID(), Map.of()));
        verifyNoInteractions(projections);
    }

    @Test
    void stock_answers_drive_the_saga() {
        UUID order = UUID.randomUUID();
        consumer.handleInventory(event("StockReserved", order, Map.of("orderId", order.toString())));
        verify(orders).confirmReservation(order);

        consumer.handleInventory(event("StockReservationRejected", order, Map.of("orderId", order.toString(),
                "reason", "Stock insuficiente")));
        verify(orders).rejectReservation(order, "Stock insuficiente");

        UUID other = UUID.randomUUID();
        consumer.handleInventory(event("StockReservationRejected", other, Map.of()));
        verify(orders).rejectReservation(other, null);

        consumer.handleInventory(event("StockDeducted", order, Map.of()));
        verify(orders).confirmReservation(eq(order));
    }

    @Test
    void a_return_in_transit_opens_its_dispatch_and_a_received_one_closes_it() {
        UUID returnId = UUID.randomUUID();
        UUID worksite = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        EventEnvelope dispatched = event("MaterialReturnDispatched", returnId, Map.of("returnId", returnId.toString(),
                "worksiteId", worksite.toString(), "warehouseId", warehouse.toString(), "status", "IN_TRANSIT",
                "lines", java.util.List.of(Map.of("materialId", material.toString(), "quantity", 12.5))));

        consumer.handleInventory(dispatched);
        verify(dispatches).openReturnDispatch(new DispatchCommandService.OpenReturnDispatch(returnId, worksite, warehouse,
                dispatched.occurredAt(), java.util.List.of(
                        new DispatchCommandService.ReturnLine(material, new BigDecimal("12.5")))));

        consumer.handleInventory(event("MaterialReturnReceived", returnId, Map.of("returnId", returnId.toString())));
        verify(dispatches).closeReturnDispatch(returnId);
    }

    @Test
    void the_other_return_events_create_nothing() {
        UUID returnId = UUID.randomUUID();
        for (String type : java.util.List.of("MaterialReturnRequested", "MaterialReturnApproved",
                "MaterialReturnRejected", "MaterialReturnCancelled")) {
            consumer.handleInventory(event(type, returnId, Map.of("returnId", returnId.toString())));
        }
        verifyNoInteractions(dispatches);
    }

    @Test
    void listeners_delegate_to_the_idempotent_processor() {
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("t", 0, 0, "k", "{}");
        org.mockito.Mockito.when(processor.parse("{}")).thenReturn(event("StockReserved", UUID.randomUUID(), Map.of()));
        consumer.onOrganizationEvent(record);
        consumer.onInventoryEvent(record);
        verify(processor, org.mockito.Mockito.times(2)).process(any(), eq(record), any());
    }
}
