package pe.buildshield.ordering.domain.model.order;

import org.junit.jupiter.api.Test;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final LocalDate REQUIRED = LocalDate.of(2026, 11, 10);
    private static final UUID WORKSITE = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID JORGE = UUID.randomUUID();
    private static final UUID ROSA = UUID.randomUUID();
    private static final UUID CEMENT = UUID.randomUUID();
    private static final UUID STEEL = UUID.randomUUID();
    private static final UUID CEMENT_LINE = UUID.randomUUID();
    private static final UUID STEEL_LINE = UUID.randomUUID();

    private static OrderLine line(UUID material, String sku, String qty) {
        return OrderLine.request(material, sku, "UNIT", new BigDecimal(qty));
    }

    private static Order order() {
        return Order.place(WORKSITE, WAREHOUSE, JORGE, " Urgente ", REQUIRED,
                List.of(line(CEMENT, "CEM-001", "50"), line(STEEL, "FIE-012", "120")), NOW);
    }

    private static Order stored(OrderStatus status) {
        return Order.restore(UUID.randomUUID(), WORKSITE, WAREHOUSE, JORGE, null, null, NOW, List.of(
                OrderLine.restore(CEMENT_LINE, CEMENT, "CEM-001", "BAG", new BigDecimal("50"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                OrderLine.restore(STEEL_LINE, STEEL, "FIE-012", "UNIT", new BigDecimal("120"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO)), status, null, ROSA, NOW, 1L);
    }

    @Test
    void placed_order_is_registered_with_its_lines_and_required_date() {
        Order order = order();

        assertThat(order.status()).isEqualTo(OrderStatus.REGISTERED);
        assertThat(order.id()).isNull();
        assertThat(order.lines()).hasSize(2);
        assertThat(order.notes()).isEqualTo("Urgente");
        assertThat(order.requiredDate()).isEqualTo(REQUIRED);
        assertThat(order.requestedBy()).isEqualTo(JORGE);
        assertThat(order.placedAt()).isEqualTo(NOW);
        assertThat(order.worksiteId()).isEqualTo(WORKSITE);
        assertThat(order.warehouseId()).isEqualTo(WAREHOUSE);
    }

    @Test
    void needs_lines_without_repeated_materials_and_short_notes() {
        assertThatThrownBy(() -> Order.place(WORKSITE, WAREHOUSE, JORGE, null, null, List.of(), NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "ORDER_WITHOUT_LINES");
        assertThatThrownBy(() -> Order.place(WORKSITE, WAREHOUSE, JORGE, null, null, null, NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "ORDER_WITHOUT_LINES");
        assertThatThrownBy(() -> Order.place(WORKSITE, WAREHOUSE, JORGE, null, null,
                List.of(line(CEMENT, "CEM-001", "50"), line(CEMENT, "CEM-001", "10")), NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "DUPLICATE_MATERIAL");
        assertThatThrownBy(() -> Order.place(WORKSITE, WAREHOUSE, JORGE, "x".repeat(501), null,
                List.of(line(CEMENT, "CEM-001", "1")), NOW))
                .isInstanceOf(ValidationException.class);
        assertThat(Order.place(WORKSITE, WAREHOUSE, JORGE, "  ", null, List.of(line(CEMENT, "CEM-001", "1")), NOW)
                .notes()).isNull();
    }

    @Test
    void approval_waits_for_the_stock_reservation_and_records_who_decided() {
        Order order = order();

        order.approve(ROSA, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.APPROVAL_PENDING);
        assertThat(order.decidedBy()).isEqualTo(ROSA);
        assertThat(order.decidedAt()).isEqualTo(NOW);
        assertThat(order.acceptsDispatch()).as("aún no se despacha").isFalse();

        order.confirmReservation();

        assertThat(order.status()).isEqualTo(OrderStatus.IN_REVIEW);
        assertThat(order.acceptsDispatch()).isTrue();
    }

    @Test
    void rejected_reservation_returns_to_registered_with_the_reason_and_can_be_approved_again() {
        Order order = order();
        order.approve(ROSA, NOW);

        order.rejectReservation("  Stock insuficiente en el almacén para CEM-001 (hay 10, se pidió 50) ");

        assertThat(order.status()).isEqualTo(OrderStatus.REGISTERED);
        assertThat(order.rejectionReason()).isEqualTo("Stock insuficiente en el almacén para CEM-001 (hay 10, se pidió 50)");
        assertThat(order.decidedBy()).isNull();
        assertThat(order.decidedAt()).isNull();

        order.approve(ROSA, NOW);
        assertThat(order.rejectionReason()).as("aprobar de nuevo limpia el motivo").isNull();
        order.rejectReservation(null);
        assertThat(order.rejectionReason()).isEqualTo("No se pudo reservar el stock");
        order.approve(ROSA, NOW);
        order.rejectReservation("y".repeat(600));
        assertThat(order.rejectionReason()).hasSize(500);
    }

    @Test
    void reservation_answers_only_apply_while_approval_is_pending() {
        assertThatThrownBy(() -> order().confirmReservation()).isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> order().rejectReservation("x")).isInstanceOf(InvalidOrderTransitionException.class);
        Order pending = order();
        pending.approve(ROSA, NOW);
        assertThatThrownBy(() -> pending.approve(ROSA, NOW)).isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> pending.reject(ROSA, "No", NOW)).isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void rejection_requires_a_reason_and_cancels() {
        Order order = order();

        assertThatThrownBy(() -> order.reject(ROSA, " ", NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", Order.REJECTION_REASON_REQUIRED);
        assertThatThrownBy(() -> order.reject(ROSA, null, NOW)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> order.reject(ROSA, "x".repeat(501), NOW)).isInstanceOf(ValidationException.class);
        assertThat(order.status()).isEqualTo(OrderStatus.REGISTERED);

        order.reject(ROSA, "  Sin transporte  ", NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.rejectionReason()).isEqualTo("Sin transporte");
        assertThatThrownBy(() -> order.approve(ROSA, NOW)).isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void dispatch_by_line_id_moves_to_partially_fulfilled_then_fulfilled_and_close() {
        Order order = stored(OrderStatus.IN_REVIEW);
        assertThat(order.acceptsDispatch()).isTrue();

        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("20")));
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(order.lineById(CEMENT_LINE).pending()).isEqualByComparingTo("30");
        assertThat(order.hasPending()).isTrue();

        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("30"), STEEL_LINE, new BigDecimal("120")));
        assertThat(order.status()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(order.hasPending()).isFalse();
        assertThat(order.acceptsDispatch()).isFalse();

        order.close();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void a_dispatch_exceeding_one_line_changes_nothing() {
        Order order = stored(OrderStatus.IN_REVIEW);

        assertThatThrownBy(() -> order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("10"),
                STEEL_LINE, new BigDecimal("121"))))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "DISPATCH_EXCEEDS_PENDING");
        assertThat(order.lineById(CEMENT_LINE).dispatched()).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.IN_REVIEW);
    }

    @Test
    void dispatch_requires_lines_of_the_order_and_an_approved_order() {
        assertThatThrownBy(() -> stored(OrderStatus.REGISTERED).registerDispatch(Map.of(CEMENT_LINE, BigDecimal.ONE)))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> stored(OrderStatus.APPROVAL_PENDING).registerDispatch(Map.of(CEMENT_LINE, BigDecimal.ONE)))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> stored(OrderStatus.IN_REVIEW).registerDispatch(Map.of()))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "DISPATCH_WITHOUT_LINES");
        assertThatThrownBy(() -> stored(OrderStatus.IN_REVIEW).registerDispatch(null))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "DISPATCH_WITHOUT_LINES");
        assertThatThrownBy(() -> stored(OrderStatus.IN_REVIEW).registerDispatch(Map.of(UUID.randomUUID(), BigDecimal.ONE)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "ORDER_LINE_NOT_FOUND");
    }

    @Test
    void cancelled_dispatch_returns_to_pending_and_order_goes_back() {
        Order order = stored(OrderStatus.IN_REVIEW);
        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("20")));
        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("30"), STEEL_LINE, new BigDecimal("120")));
        assertThat(order.status()).isEqualTo(OrderStatus.FULFILLED);

        order.revertDispatch(Map.of(CEMENT_LINE, new BigDecimal("30"), STEEL_LINE, new BigDecimal("120")));
        assertThat(order.status()).as("queda el primer despacho").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(order.lineById(CEMENT_LINE).pending()).isEqualByComparingTo("30");
        assertThat(order.lineById(STEEL_LINE).pending()).isEqualByComparingTo("120");

        order.revertDispatch(Map.of(CEMENT_LINE, new BigDecimal("20")));
        assertThat(order.status()).as("nada despachado").isEqualTo(OrderStatus.IN_REVIEW);
        assertThat(order.hasPending()).isTrue();
    }

    @Test
    void reverting_a_dispatch_needs_a_dispatched_order_and_cannot_return_what_was_received() {
        assertThatThrownBy(() -> stored(OrderStatus.REGISTERED).revertDispatch(Map.of(CEMENT_LINE, BigDecimal.ONE)))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> stored(OrderStatus.CLOSED).revertDispatch(Map.of(CEMENT_LINE, BigDecimal.ONE)))
                .isInstanceOf(InvalidOrderTransitionException.class);
        Order order = stored(OrderStatus.IN_REVIEW);
        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("20")));
        order.registerReceived(Map.of(CEMENT_LINE, new BigDecimal("15")));

        assertThatThrownBy(() -> order.revertDispatch(Map.of(CEMENT_LINE, new BigDecimal("10"))))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", OrderLine.DISPATCH_REVERT_EXCEEDS_DISPATCHED);
    }

    @Test
    void cancelling_a_partially_fulfilled_order_cancels_what_is_pending() {
        Order order = stored(OrderStatus.IN_REVIEW);
        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("20")));

        order.cancel();

        assertThat(order.status()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(order.line(CEMENT).cancelled()).isEqualByComparingTo("30");
        assertThat(order.line(STEEL).cancelled()).isEqualByComparingTo("120");
    }

    @Test
    void cancelling_before_any_dispatch_cancels_everything() {
        Order order = order();

        order.cancel();

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.lines()).allMatch(OrderLine::isComplete);
    }

    @Test
    void unknown_material_is_not_in_the_order() {
        assertThatThrownBy(() -> order().line(UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_IN_ORDER");
    }

    @Test
    void restores_its_state() {
        UUID id = UUID.randomUUID();
        Order restored = Order.restore(id, WORKSITE, WAREHOUSE, JORGE, null, REQUIRED, NOW,
                List.of(line(CEMENT, "CEM-001", "5")), OrderStatus.IN_REVIEW, null, ROSA, NOW, 3L);

        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.status()).isEqualTo(OrderStatus.IN_REVIEW);
        assertThat(restored.requiredDate()).isEqualTo(REQUIRED);
        assertThat(restored.version()).isEqualTo(3L);
    }

    @Test
    void received_quantities_are_registered_by_line_up_to_what_was_dispatched() {
        Order order = stored(OrderStatus.IN_REVIEW);
        order.registerDispatch(Map.of(CEMENT_LINE, new BigDecimal("50")));

        order.registerReceived(Map.of(CEMENT_LINE, new BigDecimal("49")));

        assertThat(order.lineById(CEMENT_LINE).received()).isEqualByComparingTo("49");
        assertThat(order.lineById(CEMENT_LINE).id()).isEqualTo(CEMENT_LINE);
        assertThatThrownBy(() -> order.registerReceived(Map.of(CEMENT_LINE, new BigDecimal("2"))))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "RECEIVED_EXCEEDS_DISPATCHED");
    }
}
