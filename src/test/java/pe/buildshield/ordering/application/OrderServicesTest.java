package pe.buildshield.ordering.application;

import org.junit.jupiter.api.Test;
import pe.buildshield.ordering.domain.model.order.InvalidOrderTransitionException;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ResourceNotFoundException;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US18, US20, US21 y la saga de reserva de stock (OrderCommandService y OrderQueryService). */
class OrderServicesTest extends ServiceTestBase {

    @Test
    void site_manager_places_an_order_for_an_assigned_worksite_and_publishes_order_registered() {
        asJorge();
        OrderView view = orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, " Losa ",
                LocalDate.of(2026, 11, 10), List.of(new OrderCommandService.LineRequest(cement, qty("50")))));

        assertThat(view.id()).isNotNull();
        assertThat(view.status()).isEqualTo(OrderStatus.REGISTERED);
        assertThat(view.requestedBy()).isEqualTo(JORGE);
        assertThat(view.requiredDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo("CEM-001");
            assertThat(line.unit()).isEqualTo("BAG");
            assertThat(line.pending()).isEqualByComparingTo("50");
        });
        OrderingEvents.OrderRegistered event = outbox.last(OrderingEvents.ORDER_REGISTERED,
                OrderingEvents.OrderRegistered.class);
        assertThat(outbox.events.get(0).aggregateType()).isEqualTo("ORDER");
        assertThat(outbox.events.get(0).aggregateId()).isEqualTo(view.id());
        assertThat(event.orderId()).isEqualTo(view.id());
        assertThat(event.worksiteId()).isEqualTo(torre);
        assertThat(event.warehouseId()).isEqualTo(central);
        assertThat(event.requestedBy()).isEqualTo(JORGE);
        assertThat(event.requiredDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(event.notes()).isEqualTo("Losa");
        assertThat(event.lines()).singleElement().satisfies(line -> {
            assertThat(line.orderLineId()).isEqualTo(view.lines().get(0).id());
            assertThat(line.materialId()).isEqualTo(cement);
            assertThat(line.sku()).isEqualTo("CEM-001");
            assertThat(line.unit()).isEqualTo("BAG");
            assertThat(line.quantity()).isEqualByComparingTo("50");
        });
    }

    @Test
    void unassigned_or_unknown_worksite_is_404() {
        asLucia();
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, null, null,
                List.of(new OrderCommandService.LineRequest(cement, qty("1"))))))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WORKSITE_NOT_FOUND");
        asJorge();
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(central, central, null, null,
                List.of(new OrderCommandService.LineRequest(cement, qty("1"))))))
                .as("un almacén no es una obra").isInstanceOf(ResourceNotFoundException.class);
        assertThat(orders.stored).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void warehouse_and_materials_must_exist_and_be_active() {
        UUID closed = directory.warehouse("Cerrado", false);
        UUID retired = directory.material("OLD-001", "Retirado", "BAG", "1", false);
        asJorge();
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, closed, null, null,
                List.of(new OrderCommandService.LineRequest(cement, qty("1"))))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "SITE_INACTIVE");
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, UUID.randomUUID(), null,
                null, List.of(new OrderCommandService.LineRequest(cement, qty("1"))))))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WAREHOUSE_NOT_FOUND");
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, null, null,
                List.of(new OrderCommandService.LineRequest(retired, qty("1"))))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_INACTIVE");
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, null, null,
                List.of(new OrderCommandService.LineRequest(UUID.randomUUID(), qty("1"))))))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_FOUND");
        assertThatThrownBy(() -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, null, null,
                null))).isInstanceOf(ValidationException.class);
    }

    @Test
    void approval_leaves_the_order_approval_pending_and_publishes_order_approved() {
        OrderView order = placed();
        asRosa();

        OrderView approved = orderCommands.approve(order.id());

        assertThat(approved.status()).isEqualTo(OrderStatus.APPROVAL_PENDING);
        assertThat(approved.decidedBy()).isEqualTo(ROSA);
        assertThat(approved.decidedAt()).isEqualTo(NOW);
        OrderingEvents.OrderApproved event = outbox.last(OrderingEvents.ORDER_APPROVED, OrderingEvents.OrderApproved.class);
        assertThat(event.orderId()).isEqualTo(order.id());
        assertThat(event.warehouseId()).isEqualTo(central);
        assertThat(event.worksiteId()).isEqualTo(torre);
        assertThat(event.approvedBy()).isEqualTo(ROSA);
        assertThat(event.lines()).hasSize(2).extracting(OrderingEvents.OrderLinePayload::sku)
                .containsExactlyInAnyOrder("CEM-001", "FIE-012");
    }

    @Test
    void stock_reserved_moves_the_order_to_in_review() {
        OrderView order = placed();
        asRosa();
        orderCommands.approve(order.id());

        assertThat(orderCommands.confirmReservation(order.id())).isTrue();

        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.IN_REVIEW);
        assertThat(orderCommands.confirmReservation(order.id())).as("ya no está Aprobando: se ignora").isFalse();
        assertThat(orderCommands.rejectReservation(order.id(), "tarde")).isFalse();
        assertThat(orderQueries.get(order.id()).status()).isEqualTo(OrderStatus.IN_REVIEW);
    }

    @Test
    void stock_reservation_rejected_returns_to_registered_with_the_reason() {
        OrderView order = placed();
        asRosa();
        orderCommands.approve(order.id());

        assertThat(orderCommands.rejectReservation(order.id(), "Stock insuficiente para CEM-001")).isTrue();

        OrderView view = orderQueries.get(order.id());
        assertThat(view.status()).isEqualTo(OrderStatus.REGISTERED);
        assertThat(view.rejectionReason()).isEqualTo("Stock insuficiente para CEM-001");
        assertThat(view.decidedBy()).isNull();
        assertThat(orderCommands.rejectReservation(UUID.randomUUID(), "x")).as("pedido inexistente").isFalse();
        assertThat(orderCommands.confirmReservation(UUID.randomUUID())).isFalse();
    }

    @Test
    void rejection_cancels_and_publishes_order_rejected() {
        OrderView order = placed();
        asAdmin();

        OrderView rejected = orderCommands.reject(order.id(), "Sin transporte");

        assertThat(rejected.status()).isEqualTo(OrderStatus.CANCELLED);
        OrderingEvents.OrderFinished event = outbox.last(OrderingEvents.ORDER_REJECTED, OrderingEvents.OrderFinished.class);
        assertThat(event).isEqualTo(new OrderingEvents.OrderFinished(order.id(), central, torre, "Sin transporte"));
        assertThatThrownBy(() -> orderCommands.approve(order.id())).isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void only_the_origin_warehouse_or_the_administrator_decides() {
        OrderView order = placed();
        asMaria();
        assertThatThrownBy(() -> orderCommands.approve(order.id())).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_FOUND");
        assertThatThrownBy(() -> orderCommands.reject(order.id(), "No")).isInstanceOf(ResourceNotFoundException.class);
        asAdmin();
        assertThat(orderCommands.approve(order.id()).status()).isEqualTo(OrderStatus.APPROVAL_PENDING);
    }

    @Test
    void visibility_of_orders_by_role() {
        OrderView mine = placed();
        asLucia();
        OrderView other = orderCommands.place(new OrderCommandService.PlaceOrder(sur, callao, null, null,
                List.of(new OrderCommandService.LineRequest(cement, qty("10")))));

        asLucia();
        assertThat(orderQueries.list()).extracting(OrderView::id).containsExactly(other.id());
        assertThatThrownBy(() -> orderQueries.get(mine.id())).isInstanceOf(ResourceNotFoundException.class);
        asRosa();
        assertThat(orderQueries.list()).extracting(OrderView::id).containsExactly(mine.id());
        assertThat(orderQueries.get(mine.id()).id()).isEqualTo(mine.id());
        asAdmin();
        assertThat(orderQueries.list()).hasSize(2);
        asJorge();
        assertThat(orderQueries.get(mine.id()).worksiteId()).isEqualTo(torre);
    }
}
