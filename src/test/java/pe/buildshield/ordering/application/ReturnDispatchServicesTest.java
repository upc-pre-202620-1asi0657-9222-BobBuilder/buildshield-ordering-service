package pe.buildshield.ordering.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Despachos de retorno: nacen y se cierran con los eventos de la devolución de inventario, sin tocar pedidos. */
class ReturnDispatchServicesTest extends ServiceTestBase {

    private static final Instant DEPARTED = NOW.minusSeconds(60);

    private final UUID returnId = UUID.randomUUID();

    private void asEvent() {
        TenantContext.set(new TenantInfo(ORG, JORGE, "SYSTEM"));
    }

    private DispatchCommandService.OpenReturnDispatch command() {
        return new DispatchCommandService.OpenReturnDispatch(returnId, torre, central, DEPARTED,
                List.of(new DispatchCommandService.ReturnLine(cement, qty("12.5")),
                        new DispatchCommandService.ReturnLine(steel, qty("3"))));
    }

    private UUID opened() {
        asEvent();
        dispatchCommands.openReturnDispatch(command());
        return Dispatch.returnDispatchId(returnId);
    }

    @Test
    void a_return_in_transit_creates_a_return_dispatch_in_transit_without_order() {
        UUID id = opened();

        asRosa();
        DispatchView dispatch = dispatchQueries.get(id);
        assertThat(dispatch.type()).isEqualTo(DispatchType.RETURN);
        assertThat(dispatch.status()).isEqualTo(DispatchStatus.IN_TRANSIT);
        assertThat(dispatch.orderId()).isNull();
        assertThat(dispatch.warehouseId()).isEqualTo(central);
        assertThat(dispatch.worksiteId()).isEqualTo(torre);
        assertThat(dispatch.departedAt()).isEqualTo(DEPARTED);
        assertThat(dispatch.carrier()).isNull();
        assertThat(dispatch.departureWeighing()).isNull();
        assertThat(dispatch.manifestCode()).startsWith("MAN-20261103-");
        assertThat(dispatch.lines()).extracting(DispatchView.LineView::orderLineId).containsOnlyNulls();
        assertThat(dispatch.lines()).extracting(DispatchView.LineView::materialId).containsExactly(cement, steel);
        assertThat(orders.stored).isEmpty();

        OrderingEvents.ReturnDispatchCreated event = outbox.last(OrderingEvents.RETURN_DISPATCH_CREATED,
                OrderingEvents.ReturnDispatchCreated.class);
        assertThat(event.returnId()).isEqualTo(returnId);
        assertThat(event.dispatchId()).isEqualTo(id);
        assertThat(event.manifestCode()).isEqualTo(dispatch.manifestCode());
        assertThat(event.status()).isEqualTo("IN_TRANSIT");
        assertThat(event.departedAt()).isEqualTo(DEPARTED);
        assertThat(event.lines()).hasSize(2);
        assertThat(outbox.types()).containsExactly(OrderingEvents.RETURN_DISPATCH_CREATED);
    }

    @Test
    void the_event_time_is_optional() {
        asEvent();
        dispatchCommands.openReturnDispatch(new DispatchCommandService.OpenReturnDispatch(returnId, torre, central, null,
                command().lines()));

        asRosa();
        assertThat(dispatchQueries.get(Dispatch.returnDispatchId(returnId)).departedAt()).isEqualTo(NOW);
    }

    @Test
    void one_return_has_one_dispatch_even_with_another_event() {
        opened();
        asEvent();
        dispatchCommands.openReturnDispatch(command());

        assertThat(dispatches.stored).hasSize(1);
        assertThat(outbox.types()).containsExactly(OrderingEvents.RETURN_DISPATCH_CREATED);
    }

    @Test
    void a_received_return_closes_the_dispatch_without_reception_or_stock_events() {
        UUID id = opened();
        clock.advance(java.time.Duration.ofHours(1));

        asEvent();
        dispatchCommands.closeReturnDispatch(returnId);
        dispatchCommands.closeReturnDispatch(returnId);

        asRosa();
        assertThat(dispatchQueries.get(id).status()).isEqualTo(DispatchStatus.RECEIVED);
        assertThat(receptions.stored).isEmpty();
        assertThat(orders.stored).isEmpty();
        assertThat(outbox.types()).containsExactly(OrderingEvents.RETURN_DISPATCH_CREATED,
                OrderingEvents.RETURN_DISPATCH_CLOSED);
        OrderingEvents.ReturnDispatchClosed event = outbox.last(OrderingEvents.RETURN_DISPATCH_CLOSED,
                OrderingEvents.ReturnDispatchClosed.class);
        assertThat(event.returnId()).isEqualTo(returnId);
        assertThat(event.dispatchId()).isEqualTo(id);
        assertThat(event.status()).isEqualTo("RECEIVED");
        assertThat(event.receivedAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void closing_a_return_without_dispatch_changes_nothing() {
        asEvent();
        dispatchCommands.closeReturnDispatch(returnId);

        assertThat(dispatches.stored).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void a_return_dispatch_opens_no_reception() {
        UUID id = opened();

        asJorge();
        assertThatThrownBy(() -> receptionCommands.start(id, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ReceptionCommandService.RETURN_DISPATCH_NOT_RECEIVABLE);
        assertThat(receptions.stored).isEmpty();
    }

    @Test
    void a_return_dispatch_accepts_incidents_but_no_preparation_steps() {
        UUID id = opened();

        asRosa();
        dispatchCommands.reportIncident(id, pe.buildshield.ordering.domain.model.dispatch.IncidentType.DELAY, "Tráfico",
                null);
        assertThat(dispatchQueries.incidents(id)).hasSize(1);
        assertThatThrownBy(() -> dispatchCommands.depart(id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> dispatchCommands.cancel(id, "No se devuelve")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void the_events_have_the_contract_fields() {
        opened();
        asEvent();
        dispatchCommands.closeReturnDispatch(returnId);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode created = json.valueToTree(outbox.last(OrderingEvents.RETURN_DISPATCH_CREATED,
                OrderingEvents.ReturnDispatchCreated.class));
        JsonNode closed = json.valueToTree(outbox.last(OrderingEvents.RETURN_DISPATCH_CLOSED,
                OrderingEvents.ReturnDispatchClosed.class));
        assertThat(names(created)).containsExactly("returnId", "dispatchId", "manifestCode", "warehouseId",
                "warehouseName", "worksiteId", "worksiteName", "status", "departedAt", "lines");
        assertThat(names(created.path("lines").get(0))).containsExactly("dispatchLineId", "orderLineId", "materialId",
                "quantity");
        assertThat(names(closed)).containsExactly("returnId", "dispatchId", "manifestCode", "warehouseId",
                "warehouseName", "worksiteId", "worksiteName", "status", "receivedAt");
        assertThat(created.path("worksiteName").asText()).isEqualTo("Torre Norte");
        assertThat(closed.path("warehouseName").asText()).isEqualTo("Almacén Central");
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
