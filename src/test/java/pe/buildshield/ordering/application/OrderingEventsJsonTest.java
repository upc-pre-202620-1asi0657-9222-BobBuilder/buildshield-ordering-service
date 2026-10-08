package pe.buildshield.ordering.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de los payloads de {@code buildshield.ordering} (notas-etapa-b1 §2): nombres exactos de los campos, como
 * los serializa el outbox (Jackson de Spring: fechas ISO-8601, BigDecimal como número).
 */
class OrderingEventsJsonTest extends ServiceTestBase {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void every_event_has_the_contract_fields() {
        OrderView order = placed();
        asRosa();
        orderCommands.approve(order.id());
        orderCommands.confirmReservation(order.id());
        OrderView approved = orderQueries.get(order.id());
        DispatchView cancelled = dispatched(approved, "5", null);
        asRosa();
        dispatchCommands.cancel(cancelled.id(), "Se reprograma");
        DispatchView dispatch = departed(dispatched(approved, "50", "120"));
        asRosa();
        dispatchCommands.reportIncident(dispatch.id(), pe.buildshield.ordering.domain.model.dispatch.IncidentType.DELAY,
                "Tráfico");
        ReceptionView reception = opened(dispatch);
        receptionCommands.recordArrivalWeighing(reception.id(), qty("2530"), qty("1050.5"), null);
        receptionCommands.recordLine(reception.id(), receptionLine(reception, cement), qty("20"));
        receptionCommands.receivePartial(reception.id());
        receptionCommands.recordLine(reception.id(), receptionLine(reception, cement), qty("49"));
        receptionCommands.recordLine(reception.id(), receptionLine(reception, steel), qty("120"));
        receptionCommands.confirm(reception.id());
        OrderView duplicate = placed();
        asRosa();
        orderCommands.reject(duplicate.id(), "Duplicado");

        assertFields("OrderRegistered", "orderId", "worksiteId", "warehouseId", "requestedBy", "requiredDate", "notes",
                "lines");
        assertLineFields("OrderRegistered", "orderLineId", "materialId", "sku", "unit", "quantity");
        assertFields("OrderApproved", "orderId", "warehouseId", "worksiteId", "approvedBy", "lines");
        assertLineFields("OrderApproved", "orderLineId", "materialId", "sku", "unit", "quantity");
        assertFields("OrderRejected", "orderId", "warehouseId", "worksiteId", "reason");
        assertFields("OrderClosed", "orderId", "warehouseId", "worksiteId", "reason");
        assertFields("DispatchPrepared", "dispatchId", "orderId", "manifestCode", "warehouseId", "worksiteId", "type",
                "lines");
        assertLineFields("DispatchPrepared", "dispatchLineId", "orderLineId", "materialId", "quantity");
        assertFields("DispatchCarrierAssigned", "dispatchId", "orderId", "manifestCode", "warehouseId", "worksiteId",
                "carrierName", "carrierDocument", "plate");
        assertFields("DispatchDepartureWeighed", "dispatchId", "orderId", "manifestCode", "warehouseId", "worksiteId",
                "grossKg", "tareKg", "netKg", "ticketPhotoUrl");
        assertFields("DispatchDeparted", "dispatchId", "orderId", "manifestCode", "warehouseId", "worksiteId",
                "departedAt", "lines");
        assertLineFields("DispatchDeparted", "dispatchLineId", "orderLineId", "materialId", "quantity");
        assertFields("DispatchCancelled", "dispatchId", "orderId", "manifestCode", "warehouseId", "worksiteId",
                "reason", "cancelledAt");
        assertFields("TransitIncidentReported", "dispatchId", "incidentId", "orderId", "manifestCode", "warehouseId",
                "worksiteId", "type", "typeLabel", "observation", "reportedAt");
        assertFields("ReceptionOpened", "receptionId", "dispatchId", "orderId", "manifestCode", "warehouseId",
                "worksiteId");
        assertFields("ArrivalWeighed", "receptionId", "dispatchId", "orderId", "manifestCode", "warehouseId",
                "worksiteId", "grossKg", "tareKg", "netKg", "departureNetKg", "differenceKg", "differencePercent",
                "tolerancePercent", "withinTolerance");
        assertFields("ReceptionPartiallyReceived", "receptionId", "dispatchId", "orderId", "manifestCode",
                "warehouseId", "worksiteId", "partialCount", "lines");
        assertLineFields("ReceptionPartiallyReceived", "receptionLineId", "orderLineId", "materialId",
                "receivedQuantity", "deltaQuantity");
        assertFields("ReceptionConfirmed", "receptionId", "dispatchId", "orderId", "manifestCode", "worksiteId",
                "warehouseId", "confirmedBy", "confirmedAt", "lines");
        assertLineFields("ReceptionConfirmed", "receptionLineId", "dispatchLineId", "orderLineId", "materialId", "sku",
                "materialName", "unit", "requested", "dispatched", "received", "deltaQuantity", "shrinkagePercent",
                "tolerancePercent", "withinTolerance");

        JsonNode confirmed = payload("ReceptionConfirmed");
        assertThat(confirmed.path("confirmedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(confirmed.path("lines").get(0).path("deltaQuantity").isNumber()).isTrue();
        assertThat(payload("OrderRegistered").path("requiredDate").isNull()).isTrue();
        assertThat(UUID.fromString(payload("OrderClosed").path("orderId").asText())).isEqualTo(order.id());
    }

    private JsonNode payload(String eventType) {
        Object payload = outbox.events.stream().filter(event -> event.eventType().equals(eventType))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError(eventType + " no publicado: "
                        + outbox.types())).payload();
        return json.valueToTree(payload);
    }

    private void assertFields(String eventType, String... fields) {
        List<String> names = new ArrayList<>();
        payload(eventType).fieldNames().forEachRemaining(names::add);
        assertThat(names).as(eventType).containsExactly(fields);
    }

    private void assertLineFields(String eventType, String... fields) {
        List<String> names = new ArrayList<>();
        payload(eventType).path("lines").get(0).fieldNames().forEachRemaining(names::add);
        assertThat(names).as(eventType + ".lines").containsExactly(fields);
    }
}
