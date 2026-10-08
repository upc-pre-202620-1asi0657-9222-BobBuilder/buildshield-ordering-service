package pe.buildshield.ordering.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionRepository;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ResourceNotFoundException;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** US34, US35, US37, US38 y US29: ReceptionCommandService y ReceptionQueryService. */
class ReceptionServicesTest extends ServiceTestBase {

    private OrderView order;
    private DispatchView dispatch;

    /** Pedido de 50 cemento y 120 fierro; despacho en tránsito de 30 cemento. */
    private ReceptionView openThirtyOfCement() {
        order = approved();
        dispatch = departed(dispatched(order, "30", null));
        return opened(dispatch);
    }

    @Test
    void opens_a_reception_by_dispatch_id_and_publishes_reception_opened() {
        ReceptionView reception = openThirtyOfCement();

        assertThat(reception.status()).isEqualTo(ReceptionStatus.IN_PROGRESS);
        assertThat(reception.partialCount()).isZero();
        assertThat(reception.arrivalWeighing()).isNull();
        assertThat(reception.lines()).singleElement().satisfies(line -> {
            assertThat(line.dispatchedQty()).isEqualByComparingTo("30");
            assertThat(line.receivedQty()).isNull();
            assertThat(line.stockedQty()).isZero();
        });
        OrderingEvents.ReceptionOpened event = outbox.last(OrderingEvents.RECEPTION_OPENED,
                OrderingEvents.ReceptionOpened.class);
        assertThat(event).isEqualTo(new OrderingEvents.ReceptionOpened(reception.id(), dispatch.id(), order.id(),
                dispatch.manifestCode(), central, torre));
    }

    @Test
    void opens_a_reception_by_manifest_code() {
        order = approved();
        dispatch = departed(dispatched(order, "30", null));
        asJorge();

        ReceptionView reception = receptionCommands.start(null, dispatch.manifestCode().toLowerCase());

        assertThat(reception.dispatchId()).isEqualTo(dispatch.id());
    }

    @Test
    void the_dispatch_must_be_indicated_exactly_once() {
        asJorge();
        assertThatThrownBy(() -> receptionCommands.start(null, null)).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", ReceptionCommandService.INVALID_RECEPTION_REQUEST);
        assertThatThrownBy(() -> receptionCommands.start(null, " ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> receptionCommands.start(UUID.randomUUID(), "MAN-20261103-AAAAAAAA"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void only_the_destination_worksite_opens_a_dispatch_in_transit_once() {
        order = approved();
        dispatch = dispatched(order, "30", null);
        asJorge();
        assertThatThrownBy(() -> receptionCommands.start(dispatch.id(), null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ReceptionCommandService.DISPATCH_NOT_IN_TRANSIT);
        departed(dispatch);
        asLucia();
        assertThatThrownBy(() -> receptionCommands.start(dispatch.id(), null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "DISPATCH_NOT_FOUND");
        assertThatThrownBy(() -> receptionCommands.start(null, dispatch.manifestCode()))
                .isInstanceOf(ResourceNotFoundException.class);
        asRosa();
        assertThatThrownBy(() -> receptionCommands.start(dispatch.id(), null))
                .as("el almacén no recibe").isInstanceOf(ResourceNotFoundException.class);
        ReceptionView first = opened(dispatch);
        assertThatThrownBy(() -> receptionCommands.start(dispatch.id(), null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ReceptionCommandService.RECEPTION_ALREADY_EXISTS)
                .satisfies(ex -> assertThat(((ConflictException) ex).getDetails()).singleElement()
                        .satisfies(detail -> assertThat(detail.message()).isEqualTo(first.id().toString())));
    }

    @Test
    void a_concurrent_duplicate_reception_detected_by_the_database_is_a_conflict() {
        order = approved();
        dispatch = departed(dispatched(order, "30", null));
        ReceptionRepository failing = mock(ReceptionRepository.class);
        when(failing.findIdByDispatchId(any())).thenReturn(Optional.empty());
        when(failing.save(any())).thenThrow(new DataIntegrityViolationException("x",
                new SQLException("duplicate key value violates unique constraint \"uk_receptions_dispatch\"")));
        ReceptionCommandService service = new ReceptionCommandService(failing, dispatches, orders, directory, access,
                evidence, outbox, clock);
        asJorge();
        assertThatThrownBy(() -> service.start(dispatch.id(), null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ReceptionCommandService.RECEPTION_ALREADY_EXISTS);

        ReceptionRepository other = mock(ReceptionRepository.class);
        when(other.findIdByDispatchId(any())).thenReturn(Optional.empty());
        when(other.save(any())).thenThrow(new DataIntegrityViolationException("x", new SQLException("otro")));
        ReceptionCommandService second = new ReceptionCommandService(other, dispatches, orders, directory, access,
                evidence, outbox, clock);
        assertThatThrownBy(() -> second.start(dispatch.id(), null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void comparison_shows_requested_dispatched_received_and_the_tolerance() {
        ReceptionView reception = openThirtyOfCement();
        UUID line = receptionLine(reception, cement);

        ComparisonView incomplete = receptionQueries.comparison(reception.id());
        assertThat(incomplete.complete()).isFalse();
        assertThat(incomplete.lines()).singleElement().satisfies(item -> assertThat(item.withinTolerance()).isNull());

        receptionCommands.recordLine(reception.id(), line, qty("29.5"));
        ComparisonView comparison = receptionQueries.comparison(reception.id());

        assertThat(comparison.complete()).isTrue();
        assertThat(comparison.withinTolerance()).isTrue();
        assertThat(comparison.lines()).singleElement().satisfies(item -> {
            assertThat(item.requested()).isEqualByComparingTo("50");
            assertThat(item.dispatched()).isEqualByComparingTo("30");
            assertThat(item.received()).isEqualByComparingTo("29.5");
            assertThat(item.difference()).isEqualByComparingTo("0.5");
            assertThat(item.shrinkagePercent()).isEqualByComparingTo("1.67");
            assertThat(item.tolerancePercent()).isEqualByComparingTo("2.5");
            assertThat(item.sku()).isEqualTo("CEM-001");
            assertThat(item.materialName()).isEqualTo("Cemento Portland tipo I");
        });
        directory.materials.remove(cement);
        assertThat(receptionQueries.comparison(reception.id()).withinTolerance()).as("tolerancia 0").isFalse();
    }

    @Test
    void the_origin_warehouse_sees_the_reception_but_another_worksite_does_not() {
        ReceptionView reception = openThirtyOfCement();
        asRosa();
        assertThat(receptionQueries.get(reception.id()).id()).isEqualTo(reception.id());
        assertThatThrownBy(() -> receptionCommands.confirm(reception.id())).isInstanceOf(ResourceNotFoundException.class);
        asLucia();
        assertThatThrownBy(() -> receptionQueries.get(reception.id())).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RECEPTION_NOT_FOUND");
        asAdmin();
        assertThat(receptionQueries.comparison(reception.id()).receptionId()).isEqualTo(reception.id());
        orders.stored.clear();
        assertThatThrownBy(() -> receptionQueries.comparison(reception.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirmation_registers_the_received_marks_the_dispatch_and_publishes_reception_confirmed() {
        ReceptionView reception = openThirtyOfCement();
        receptionCommands.recordLine(reception.id(), receptionLine(reception, cement), qty("29.5"));
        clock.setTo(NOW.plusSeconds(7200));

        ReceptionView confirmed = receptionCommands.confirm(reception.id());

        assertThat(confirmed.status()).isEqualTo(ReceptionStatus.CONFIRMED);
        assertThat(confirmed.confirmedBy()).isEqualTo(JORGE);
        assertThat(dispatches.findById(dispatch.id()).orElseThrow().status()).isEqualTo(DispatchStatus.RECEIVED);
        OrderView afterwards = orderQueries.get(order.id());
        assertThat(afterwards.status()).as("aún queda pendiente").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(afterwards.lines()).filteredOn(line -> line.materialId().equals(cement)).singleElement()
                .satisfies(line -> assertThat(line.received()).isEqualByComparingTo("29.5"));
        assertThat(outbox.types()).doesNotContain(OrderingEvents.ORDER_CLOSED);

        OrderingEvents.ReceptionConfirmed event = outbox.last(OrderingEvents.RECEPTION_CONFIRMED,
                OrderingEvents.ReceptionConfirmed.class);
        assertThat(event.receptionId()).isEqualTo(reception.id());
        assertThat(event.dispatchId()).isEqualTo(dispatch.id());
        assertThat(event.orderId()).isEqualTo(order.id());
        assertThat(event.manifestCode()).isEqualTo(dispatch.manifestCode());
        assertThat(event.worksiteId()).isEqualTo(torre);
        assertThat(event.warehouseId()).isEqualTo(central);
        assertThat(event.confirmedBy()).isEqualTo(JORGE);
        assertThat(event.confirmedAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(event.lines()).singleElement().satisfies(line -> {
            assertThat(line.receptionLineId()).isEqualTo(receptionLine(reception, cement));
            assertThat(line.dispatchLineId()).isEqualTo(dispatch.lines().get(0).id());
            assertThat(line.orderLineId()).isEqualTo(line(order, cement));
            assertThat(line.materialId()).isEqualTo(cement);
            assertThat(line.sku()).isEqualTo("CEM-001");
            assertThat(line.materialName()).isEqualTo("Cemento Portland tipo I");
            assertThat(line.unit()).isEqualTo("BAG");
            assertThat(line.requested()).isEqualByComparingTo("50");
            assertThat(line.dispatched()).isEqualByComparingTo("30");
            assertThat(line.received()).isEqualByComparingTo("29.5");
            assertThat(line.deltaQuantity()).isEqualByComparingTo("29.5");
            assertThat(line.shrinkagePercent()).isEqualByComparingTo("1.67");
            assertThat(line.tolerancePercent()).isEqualByComparingTo("2.5");
            assertThat(line.withinTolerance()).isTrue();
        });

        assertThatThrownBy(() -> receptionCommands.confirm(reception.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_ALREADY_CONFIRMED);
    }

    @Test
    void confirmation_needs_every_line() {
        ReceptionView reception = openThirtyOfCement();
        assertThatThrownBy(() -> receptionCommands.confirm(reception.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_INCOMPLETE);
    }

    @Test
    void the_last_reception_of_a_fulfilled_order_closes_it_and_publishes_order_closed() {
        order = approved();
        DispatchView first = departed(dispatched(order, "20", "120"));
        DispatchView cancelled = dispatched(order, "10", null);
        asRosa();
        dispatchCommands.cancel(cancelled.id(), "Se reprograma");
        DispatchView second = departed(dispatched(order, "30", null));
        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.FULFILLED);

        ReceptionView r1 = opened(first);
        receptionCommands.recordLine(r1.id(), receptionLine(r1, cement), qty("20"));
        receptionCommands.recordLine(r1.id(), receptionLine(r1, steel), qty("110"));
        receptionCommands.confirm(r1.id());
        assertThat(orderQueries.get(order.id()).status()).as("falta el segundo despacho").isEqualTo(OrderStatus.FULFILLED);
        OrderingEvents.ReceptionConfirmed firstEvent = outbox.last(OrderingEvents.RECEPTION_CONFIRMED,
                OrderingEvents.ReceptionConfirmed.class);
        assertThat(firstEvent.lines()).filteredOn(line -> line.materialId().equals(steel)).singleElement()
                .satisfies(line -> {
                    assertThat(line.shrinkagePercent()).isEqualByComparingTo("8.33");
                    assertThat(line.withinTolerance()).isFalse();
                });

        ReceptionView r2 = opened(second);
        receptionCommands.recordLine(r2.id(), receptionLine(r2, cement), qty("30"));
        receptionCommands.confirm(r2.id());

        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.CLOSED);
        OrderingEvents.OrderFinished closed = outbox.last(OrderingEvents.ORDER_CLOSED, OrderingEvents.OrderFinished.class);
        assertThat(closed).isEqualTo(new OrderingEvents.OrderFinished(order.id(), central, torre,
                OrderingEvents.RECEPTION_COMPLETE));
        assertThat(outbox.events.get(outbox.events.size() - 1).aggregateType()).isEqualTo("ORDER");
    }

    @Test
    void confirming_with_nothing_received_still_closes_a_complete_order() {
        order = approved();
        dispatch = departed(dispatched(order, "50", "120"));
        ReceptionView reception = opened(dispatch);
        receptionCommands.recordLine(reception.id(), receptionLine(reception, cement), qty("0"));
        receptionCommands.recordLine(reception.id(), receptionLine(reception, steel), qty("0"));
        directory.materials.clear();

        receptionCommands.confirm(reception.id());

        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.CLOSED);
        OrderingEvents.ReceptionConfirmed event = outbox.last(OrderingEvents.RECEPTION_CONFIRMED,
                OrderingEvents.ReceptionConfirmed.class);
        assertThat(event.lines()).allSatisfy(line -> {
            assertThat(line.deltaQuantity()).isZero();
            assertThat(line.tolerancePercent()).as("material desconocido: 0").isZero();
            assertThat(line.materialName()).as("sin copia: el SKU del pedido").isEqualTo(line.sku());
            assertThat(line.withinTolerance()).isFalse();
        });
    }

    @Test
    void partial_receptions_add_only_the_difference_and_confirmation_adds_the_rest() {
        ReceptionView reception = openThirtyOfCement();
        UUID line = receptionLine(reception, cement);
        receptionCommands.recordLine(reception.id(), line, qty("10"));
        clock.setTo(NOW.plusSeconds(100));

        ReceptionView partial = receptionCommands.receivePartial(reception.id());

        assertThat(partial.status()).isEqualTo(ReceptionStatus.PARTIALLY_RECEIVED);
        assertThat(partial.partialCount()).isEqualTo(1);
        assertThat(partial.lastPartialAt()).isEqualTo(NOW.plusSeconds(100));
        assertThat(dispatches.findById(dispatch.id()).orElseThrow().status()).isEqualTo(DispatchStatus.IN_TRANSIT);
        OrderingEvents.ReceptionPartiallyReceived event = outbox.last(OrderingEvents.RECEPTION_PARTIALLY_RECEIVED,
                OrderingEvents.ReceptionPartiallyReceived.class);
        assertThat(event.partialCount()).isEqualTo(1);
        assertThat(event.manifestCode()).isEqualTo(dispatch.manifestCode());
        assertThat(event.lines()).singleElement().satisfies(item -> {
            assertThat(item.receptionLineId()).isEqualTo(line);
            assertThat(item.orderLineId()).isEqualTo(line(order, cement));
            assertThat(item.materialId()).isEqualTo(cement);
            assertThat(item.receivedQuantity()).isEqualByComparingTo("10");
            assertThat(item.deltaQuantity()).isEqualByComparingTo("10");
        });
        assertThat(receivedOf(cement)).isEqualByComparingTo("10");

        assertThatThrownBy(() -> receptionCommands.receivePartial(reception.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.NOTHING_TO_RECEIVE);
        assertThatThrownBy(() -> receptionCommands.recordLine(reception.id(), line, qty("9")))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "RECEIVED_BELOW_STOCKED");

        receptionCommands.recordLine(reception.id(), line, qty("25"));
        receptionCommands.receivePartial(reception.id());
        assertThat(outbox.last(OrderingEvents.RECEPTION_PARTIALLY_RECEIVED,
                OrderingEvents.ReceptionPartiallyReceived.class).lines().get(0).deltaQuantity()).isEqualByComparingTo("15");
        assertThat(receivedOf(cement)).isEqualByComparingTo("25");

        receptionCommands.recordLine(reception.id(), line, qty("29"));
        receptionCommands.confirm(reception.id());
        OrderingEvents.ReceptionConfirmed confirmed = outbox.last(OrderingEvents.RECEPTION_CONFIRMED,
                OrderingEvents.ReceptionConfirmed.class);
        assertThat(confirmed.lines().get(0).received()).isEqualByComparingTo("29");
        assertThat(confirmed.lines().get(0).deltaQuantity()).isEqualByComparingTo("4");
        assertThat(receivedOf(cement)).isEqualByComparingTo("29");
    }

    @Test
    void arrival_weighing_is_compared_with_the_departure_weighing() {
        order = approved();
        dispatch = departed(dispatched(order, "30", "100"));
        ReceptionView reception = opened(dispatch);

        ReceptionView.ArrivalWeighingView weighing = receptionCommands.recordArrivalWeighing(reception.id(),
                qty("2530"), qty("1050.5"), "https://evidencias/llegada.jpg");

        assertThat(weighing.netKg()).isEqualByComparingTo("1479.5");
        assertThat(weighing.departureNetKg()).isEqualByComparingTo("1500");
        assertThat(weighing.differenceKg()).isEqualByComparingTo("20.5");
        assertThat(weighing.differencePercent()).isEqualByComparingTo("1.37");
        assertThat(weighing.tolerancePercent()).as("la menor: fierro 1 %").isEqualByComparingTo("1");
        assertThat(weighing.withinTolerance()).isFalse();
        assertThat(receptionQueries.get(reception.id()).arrivalWeighing()).isEqualTo(weighing);
        OrderingEvents.ArrivalWeighed event = outbox.last(OrderingEvents.ARRIVAL_WEIGHED,
                OrderingEvents.ArrivalWeighed.class);
        assertThat(event.receptionId()).isEqualTo(reception.id());
        assertThat(event.grossKg()).isEqualByComparingTo("2530");
        assertThat(event.tareKg()).isEqualByComparingTo("1050.5");
        assertThat(event.netKg()).isEqualByComparingTo("1479.5");
        assertThat(event.departureNetKg()).isEqualByComparingTo("1500");
        assertThat(event.differenceKg()).isEqualByComparingTo("20.5");
        assertThat(event.differencePercent()).isEqualByComparingTo("1.37");
        assertThat(event.tolerancePercent()).isEqualByComparingTo("1");
        assertThat(event.withinTolerance()).isFalse();

        assertThatThrownBy(() -> receptionCommands.recordArrivalWeighing(reception.id(), qty("2530"), qty("1000"), null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.ARRIVAL_WEIGHING_ALREADY_RECORDED);
    }

    @Test
    void arrival_weighing_needs_gross_above_tare_and_an_open_reception() {
        ReceptionView reception = openThirtyOfCement();
        assertThatThrownBy(() -> receptionCommands.recordArrivalWeighing(reception.id(), qty("1000"), qty("1000"), null))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "INVALID_WEIGHING");
        receptionCommands.recordLine(reception.id(), receptionLine(reception, cement), qty("30"));
        receptionCommands.confirm(reception.id());
        assertThatThrownBy(() -> receptionCommands.recordArrivalWeighing(reception.id(), qty("2000"), qty("1000"), null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_ALREADY_CONFIRMED);
    }

    @Test
    void arrival_weighing_concurrent_duplicate_is_a_conflict() {
        ReceptionView reception = openThirtyOfCement();
        ReceptionRepository failing = mock(ReceptionRepository.class);
        when(failing.findById(reception.id())).thenReturn(receptions.findById(reception.id()));
        when(failing.save(any())).thenThrow(new DataIntegrityViolationException("x",
                new SQLException("duplicate key value violates unique constraint \"uk_arrival_weighings_reception\"")));
        ReceptionCommandService service = new ReceptionCommandService(failing, dispatches, orders, directory, access,
                evidence, outbox, clock);
        assertThatThrownBy(() -> service.recordArrivalWeighing(reception.id(), qty("2000"), qty("1000"), null))
                .isInstanceOf(ConflictException.class);
        ReceptionRepository other = mock(ReceptionRepository.class);
        when(other.findById(reception.id())).thenReturn(receptions.findById(reception.id()));
        when(other.save(any())).thenThrow(new DataIntegrityViolationException("x", new SQLException("otro")));
        ReceptionCommandService second = new ReceptionCommandService(other, dispatches, orders, directory, access,
                evidence, outbox, clock);
        assertThatThrownBy(() -> second.recordArrivalWeighing(reception.id(), qty("2000"), qty("1000"), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void without_departure_weighing_the_comparison_is_null() {
        order = approved();
        dispatch = departed(dispatched(order, "30", null));
        ReceptionView reception = opened(dispatch);
        // Una recepción de un despacho cuyo pesaje de salida no se conoce (por ejemplo, datos migrados).
        var stored = dispatches.findById(dispatch.id()).orElseThrow();
        dispatches.stored.put(dispatch.id(), pe.buildshield.ordering.domain.model.dispatch.Dispatch.restore(stored.id(),
                stored.orderId(), stored.warehouseId(), stored.worksiteId(), stored.type(), stored.manifestCode(),
                stored.lines(), stored.preparedAt(), stored.status(), stored.carrier().orElse(null), null,
                stored.departedAt(), null, null, stored.version()));
        directory.materials.clear();

        ReceptionView.ArrivalWeighingView weighing = receptionCommands.recordArrivalWeighing(reception.id(),
                qty("2000"), qty("1000"), null);

        assertThat(weighing.departureNetKg()).isNull();
        assertThat(weighing.differenceKg()).isNull();
        assertThat(weighing.differencePercent()).isNull();
        assertThat(weighing.withinTolerance()).isNull();
        assertThat(weighing.tolerancePercent()).isZero();
    }

    private java.math.BigDecimal receivedOf(UUID material) {
        return orders.findById(order.id()).orElseThrow().line(material).received();
    }
}
