package pe.buildshield.ordering.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchRepository;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.dispatch.InvalidDispatchTransitionException;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ResourceNotFoundException;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** US22 a US32: DispatchCommandService y DispatchQueryService. */
class DispatchServicesTest extends ServiceTestBase {

    @Test
    void dispatching_part_of_an_approved_order_prepares_a_partial_dispatch() {
        OrderView order = approved();

        DispatchView dispatch = dispatched(order, "20", null);

        assertThat(dispatch.status()).isEqualTo(DispatchStatus.PREPARED);
        assertThat(dispatch.type()).isEqualTo(DispatchType.PARTIAL);
        assertThat(dispatch.manifestCode()).matches("MAN-20261103-[A-Z0-9]{8}");
        assertThat(dispatch.incidentCount()).isZero();
        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        OrderingEvents.DispatchPrepared event = outbox.last(OrderingEvents.DISPATCH_PREPARED,
                OrderingEvents.DispatchPrepared.class);
        assertThat(event.dispatchId()).isEqualTo(dispatch.id());
        assertThat(event.orderId()).isEqualTo(order.id());
        assertThat(event.manifestCode()).isEqualTo(dispatch.manifestCode());
        assertThat(event.warehouseId()).isEqualTo(central);
        assertThat(event.worksiteId()).isEqualTo(torre);
        assertThat(event.type()).isEqualTo("PARTIAL");
        assertThat(event.lines()).singleElement().satisfies(line -> {
            assertThat(line.dispatchLineId()).isEqualTo(dispatch.lines().get(0).id());
            assertThat(line.orderLineId()).isEqualTo(line(order, cement));
            assertThat(line.materialId()).isEqualTo(cement);
            assertThat(line.quantity()).isEqualByComparingTo("20");
        });

        DispatchView rest = dispatched(order, "30", "120");
        assertThat(rest.type()).isEqualTo(DispatchType.COMPLETE);
        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.FULFILLED);
    }

    @Test
    void only_approved_orders_with_pending_material_are_dispatched() {
        OrderView order = placed();
        asRosa();
        orderCommands.approve(order.id());
        assertThatThrownBy(() -> dispatched(order, "1", null)).as("aún reservando")
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", DispatchCommandService.ORDER_NOT_DISPATCHABLE);
        orderCommands.confirmReservation(order.id());

        assertThatThrownBy(() -> dispatched(order, "51", null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "DISPATCH_EXCEEDS_PENDING");
        asRosa();
        assertThatThrownBy(() -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(order.id(),
                List.of(new DispatchCommandService.LineRequest(UUID.randomUUID(), qty("1"))))))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "ORDER_LINE_NOT_FOUND");
        assertThatThrownBy(() -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(order.id(), null)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "DISPATCH_WITHOUT_LINES");
        UUID cementLine = line(order, cement);
        assertThatThrownBy(() -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(order.id(), List.of(
                new DispatchCommandService.LineRequest(cementLine, qty("1")),
                new DispatchCommandService.LineRequest(cementLine, qty("2"))))))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "DUPLICATE_ORDER_LINE");
        asMaria();
        assertThatThrownBy(() -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(order.id(),
                List.of(new DispatchCommandService.LineRequest(cementLine, qty("1"))))))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "ORDER_NOT_FOUND");
        assertThat(dispatches.stored).isEmpty();
    }

    @Test
    void carrier_weighing_and_departure_publish_their_events() {
        DispatchView dispatch = dispatched(approved(), "30", null);
        asRosa();

        DispatchView withCarrier = dispatchCommands.assignCarrier(dispatch.id(), "Transportes Rímac SAC",
                "20555666777", "abc-123");
        assertThat(withCarrier.carrier().plate()).isEqualTo("ABC-123");
        OrderingEvents.DispatchCarrierAssigned carrier = outbox.last(OrderingEvents.DISPATCH_CARRIER_ASSIGNED,
                OrderingEvents.DispatchCarrierAssigned.class);
        assertThat(carrier.carrierName()).isEqualTo("Transportes Rímac SAC");
        assertThat(carrier.carrierDocument()).isEqualTo("20555666777");
        assertThat(carrier.plate()).isEqualTo("ABC-123");
        assertThat(carrier.manifestCode()).isEqualTo(dispatch.manifestCode());

        DispatchView weighed = dispatchCommands.recordDepartureWeighing(dispatch.id(), qty("2550.5"), qty("1050.5"),
                "https://evidencias/t.jpg");
        assertThat(weighed.departureWeighing().netKg()).isEqualByComparingTo("1500");
        assertThat(weighed.departureWeighing().weighedBy()).isEqualTo(ROSA);
        OrderingEvents.DispatchDepartureWeighed weighing = outbox.last(OrderingEvents.DISPATCH_DEPARTURE_WEIGHED,
                OrderingEvents.DispatchDepartureWeighed.class);
        assertThat(weighing.grossKg()).isEqualByComparingTo("2550.5");
        assertThat(weighing.tareKg()).isEqualByComparingTo("1050.5");
        assertThat(weighing.netKg()).isEqualByComparingTo("1500");
        assertThat(weighing.ticketPhotoUrl()).isEqualTo("https://evidencias/t.jpg");
        assertThatThrownBy(() -> dispatchCommands.recordDepartureWeighing(dispatch.id(), qty("10"), qty("1"), null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Dispatch.DEPARTURE_WEIGHING_ALREADY_RECORDED);

        clock.setTo(NOW.plusSeconds(3600));
        DispatchView inTransit = dispatchCommands.depart(dispatch.id());
        assertThat(inTransit.status()).isEqualTo(DispatchStatus.IN_TRANSIT);
        OrderingEvents.DispatchDeparted departed = outbox.last(OrderingEvents.DISPATCH_DEPARTED,
                OrderingEvents.DispatchDeparted.class);
        assertThat(departed.departedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(departed.lines()).singleElement().satisfies(line -> assertThat(line.quantity())
                .isEqualByComparingTo("30"));
        assertThat(departed.warehouseId()).isEqualTo(central);
    }

    @Test
    void a_concurrent_duplicate_departure_weighing_detected_by_the_database_is_a_conflict() {
        DispatchRepository failing = mock(DispatchRepository.class);
        DispatchView dispatch = dispatched(approved(), "30", null);
        when(failing.findById(dispatch.id())).thenReturn(dispatches.findById(dispatch.id()));
        when(failing.save(any())).thenThrow(new DataIntegrityViolationException("x",
                new SQLException("duplicate key value violates unique constraint \"uk_departure_weighings_dispatch\"")));
        DispatchCommandService service = new DispatchCommandService(failing, incidents, orders, access, evidence,
                outbox, clock, new Random(1));
        asRosa();
        assertThatThrownBy(() -> service.recordDepartureWeighing(dispatch.id(), qty("10"), qty("1"), null))
                .isInstanceOf(ConflictException.class);

        DispatchRepository other = mock(DispatchRepository.class);
        when(other.findById(dispatch.id())).thenReturn(dispatches.findById(dispatch.id()));
        when(other.save(any())).thenThrow(new DataIntegrityViolationException("x", new SQLException("otro")));
        DispatchCommandService second = new DispatchCommandService(other, incidents, orders, access, evidence,
                outbox, clock, new Random(1));
        assertThatThrownBy(() -> second.recordDepartureWeighing(dispatch.id(), qty("10"), qty("1"), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancelling_a_prepared_dispatch_returns_its_quantities_to_the_order() {
        OrderView order = approved();
        DispatchView first = dispatched(order, "20", null);
        DispatchView second = dispatched(order, "30", "120");
        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.FULFILLED);
        asRosa();
        clock.setTo(NOW.plusSeconds(60));

        DispatchView cancelled = dispatchCommands.cancel(second.id(), "El camión no llegó");

        assertThat(cancelled.status()).isEqualTo(DispatchStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(cancelled.cancellationReason()).isEqualTo("El camión no llegó");
        OrderView afterSecond = orderQueries.get(order.id());
        assertThat(afterSecond.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(afterSecond.lines()).filteredOn(line -> line.materialId().equals(cement)).singleElement()
                .satisfies(line -> assertThat(line.pending()).isEqualByComparingTo("30"));
        OrderingEvents.DispatchCancelled event = outbox.last(OrderingEvents.DISPATCH_CANCELLED,
                OrderingEvents.DispatchCancelled.class);
        assertThat(event.dispatchId()).isEqualTo(second.id());
        assertThat(event.reason()).isEqualTo("El camión no llegó");
        assertThat(event.cancelledAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(event.manifestCode()).isEqualTo(second.manifestCode());

        dispatchCommands.cancel(first.id(), "Se reprograma");
        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.IN_REVIEW);
        assertThatThrownBy(() -> dispatchCommands.cancel(first.id(), "Otra vez"))
                .isInstanceOf(InvalidDispatchTransitionException.class);
    }

    @Test
    void only_prepared_dispatches_of_the_own_warehouse_are_cancelled() {
        DispatchView dispatch = departed(dispatched(approved(), "20", null));
        asRosa();
        assertThatThrownBy(() -> dispatchCommands.cancel(dispatch.id(), "Tarde")).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DISPATCH_TRANSITION");
        asMaria();
        assertThatThrownBy(() -> dispatchCommands.cancel(dispatch.id(), "Tarde"))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "DISPATCH_NOT_FOUND");
        asJorge();
        assertThatThrownBy(() -> dispatchCommands.cancel(dispatch.id(), "Tarde"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void incidents_are_reported_in_transit_and_counted() {
        DispatchView dispatch = departed(dispatched(approved(), "20", null));
        asRosa();
        clock.setTo(NOW.plusSeconds(600));
        IncidentView delay = dispatchCommands.reportIncident(dispatch.id(), IncidentType.DELAY, "Tráfico en la vía");
        clock.setTo(NOW.plusSeconds(300));
        asAdmin();
        IncidentView failure = dispatchCommands.reportIncident(dispatch.id(), IncidentType.VEHICLE_FAILURE, "Llanta");

        assertThat(delay.type()).isEqualTo(IncidentType.DELAY);
        assertThat(delay.reportedBy()).isEqualTo(ROSA);
        OrderingEvents.TransitIncidentReported event = outbox.last(OrderingEvents.TRANSIT_INCIDENT_REPORTED,
                OrderingEvents.TransitIncidentReported.class);
        assertThat(event.incidentId()).isEqualTo(failure.id());
        assertThat(event.type()).isEqualTo("VEHICLE_FAILURE");
        assertThat(event.typeLabel()).isEqualTo("Falla del vehículo");
        assertThat(event.observation()).isEqualTo("Llanta");
        assertThat(event.dispatchId()).isEqualTo(dispatch.id());
        assertThat(event.orderId()).isEqualTo(dispatch.orderId());
        assertThat(event.reportedAt()).isEqualTo(NOW.plusSeconds(300));

        asJorge();
        assertThat(dispatchQueries.incidents(dispatch.id())).extracting(IncidentView::id)
                .containsExactly(failure.id(), delay.id());
        assertThat(dispatchQueries.get(dispatch.id()).incidentCount()).isEqualTo(2);
        assertThat(dispatchQueries.list(null, DispatchStatus.IN_TRANSIT)).singleElement()
                .satisfies(view -> assertThat(view.incidentCount()).isEqualTo(2));
        asLucia();
        assertThatThrownBy(() -> dispatchQueries.incidents(dispatch.id())).isInstanceOf(ResourceNotFoundException.class);
        asMaria();
        assertThatThrownBy(() -> dispatchCommands.reportIncident(dispatch.id(), IncidentType.OTHER, "Algo"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void incidents_only_in_transit() {
        DispatchView dispatch = dispatched(approved(), "20", null);
        asRosa();
        assertThatThrownBy(() -> dispatchCommands.reportIncident(dispatch.id(), IncidentType.DELAY, "Tráfico"))
                .isInstanceOf(InvalidDispatchTransitionException.class);
    }

    @Test
    void dispatches_are_found_by_manifest_code_with_the_same_visibility() {
        DispatchView dispatch = departed(dispatched(approved(), "20", null));

        asJorge();
        assertThat(dispatchQueries.byManifest(" " + dispatch.manifestCode().toLowerCase() + " ").id())
                .isEqualTo(dispatch.id());
        asRosa();
        assertThat(dispatchQueries.byManifest(dispatch.manifestCode()).id()).isEqualTo(dispatch.id());
        asAdmin();
        assertThat(dispatchQueries.byManifest(dispatch.manifestCode()).id()).isEqualTo(dispatch.id());
        asLucia();
        assertThatThrownBy(() -> dispatchQueries.byManifest(dispatch.manifestCode()))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "DISPATCH_NOT_FOUND");
        asMaria();
        assertThatThrownBy(() -> dispatchQueries.byManifest(dispatch.manifestCode()))
                .isInstanceOf(ResourceNotFoundException.class);
        asAdmin();
        assertThatThrownBy(() -> dispatchQueries.byManifest("MAN-20261103-AAAAAAAA"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> dispatchQueries.byManifest(null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_filters_by_order_and_status_and_by_visibility() {
        OrderView order = approved();
        DispatchView first = dispatched(order, "20", null);
        DispatchView second = departed(dispatched(order, "10", null));

        asJorge();
        assertThat(dispatchQueries.list(order.id(), null)).extracting(DispatchView::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(dispatchQueries.list(null, DispatchStatus.IN_TRANSIT)).extracting(DispatchView::id)
                .containsExactly(second.id());
        assertThat(dispatchQueries.list(UUID.randomUUID(), null)).isEmpty();
        asLucia();
        assertThat(dispatchQueries.list(null, null)).isEmpty();
        assertThatThrownBy(() -> dispatchQueries.get(first.id())).isInstanceOf(ResourceNotFoundException.class);
        asAdmin();
        assertThat(dispatchQueries.list(null, null)).hasSize(2);
    }

    @Test
    void manifest_has_sites_materials_and_the_qr() {
        OrderView order = approved();
        DispatchView dispatch = dispatched(order, "30", "120");
        asJorge();

        ManifestView manifest = dispatchQueries.manifest(dispatch.id());

        assertThat(manifest.manifestCode()).isEqualTo(dispatch.manifestCode());
        assertThat(manifest.qrContent()).isEqualTo(dispatch.manifestCode());
        assertThat(Base64.getDecoder().decode(manifest.qrCodePngBase64())).containsExactly(1, 2, 3);
        assertThat(manifest.worksite().name()).isEqualTo("Torre Norte");
        assertThat(manifest.worksite().address()).isEqualTo("Av. Principal 100, Miraflores, Lima");
        assertThat(manifest.warehouse().address()).isEqualTo("Av. Argentina 2500, Callao");
        assertThat(manifest.orderId()).isEqualTo(order.id());
        assertThat(manifest.orderPlacedAt()).isEqualTo(NOW);
        assertThat(manifest.lines()).extracting(ManifestView.Line::sku).containsExactlyInAnyOrder("CEM-001", "FIE-012");

        directory.materials.remove(steel);
        assertThat(dispatchQueries.manifest(dispatch.id()).lines()).anySatisfy(line -> assertThat(line.sku()).isNull());
        directory.locations.remove(torre);
        assertThatThrownBy(() -> dispatchQueries.manifest(dispatch.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void manifest_without_warehouse_copy_is_404() {
        DispatchView dispatch = dispatched(approved(), "30", null);
        directory.locations.remove(central);
        asAdmin();
        assertThatThrownBy(() -> dispatchQueries.manifest(dispatch.id())).isInstanceOf(ResourceNotFoundException.class);
    }
}
