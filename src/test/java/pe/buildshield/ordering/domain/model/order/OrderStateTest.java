package pe.buildshield.ordering.domain.model.order;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Matriz completa del patrón State: cada operación en cada estado lleva a un estado o es rechazada. */
class OrderStateTest {

    private static final String OPERATIONS = "approve, reserved, reservationRejected, reject, cancel, dispatchPartial, "
            + "dispatchFull, revertSome, revertAll, close";

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({
            "REGISTERED,          approve,             APPROVAL_PENDING",
            "REGISTERED,          reserved,            RECHAZADA",
            "REGISTERED,          reservationRejected, RECHAZADA",
            "REGISTERED,          reject,              CANCELLED",
            "REGISTERED,          cancel,              CANCELLED",
            "REGISTERED,          dispatchPartial,     RECHAZADA",
            "REGISTERED,          revertAll,           RECHAZADA",
            "REGISTERED,          close,               RECHAZADA",
            "APPROVAL_PENDING,    approve,             RECHAZADA",
            "APPROVAL_PENDING,    reserved,            IN_REVIEW",
            "APPROVAL_PENDING,    reservationRejected, REGISTERED",
            "APPROVAL_PENDING,    reject,              RECHAZADA",
            "APPROVAL_PENDING,    cancel,              RECHAZADA",
            "APPROVAL_PENDING,    dispatchFull,        RECHAZADA",
            "APPROVAL_PENDING,    revertSome,          RECHAZADA",
            "APPROVAL_PENDING,    close,               RECHAZADA",
            "IN_REVIEW,           approve,             RECHAZADA",
            "IN_REVIEW,           reserved,            RECHAZADA",
            "IN_REVIEW,           reject,              RECHAZADA",
            "IN_REVIEW,           cancel,              CANCELLED",
            "IN_REVIEW,           dispatchPartial,     PARTIALLY_FULFILLED",
            "IN_REVIEW,           dispatchFull,        FULFILLED",
            "IN_REVIEW,           revertSome,          PARTIALLY_FULFILLED",
            "IN_REVIEW,           revertAll,           IN_REVIEW",
            "IN_REVIEW,           close,               RECHAZADA",
            "PARTIALLY_FULFILLED, approve,             RECHAZADA",
            "PARTIALLY_FULFILLED, reject,              RECHAZADA",
            "PARTIALLY_FULFILLED, cancel,              RECHAZADA",
            "PARTIALLY_FULFILLED, dispatchPartial,     PARTIALLY_FULFILLED",
            "PARTIALLY_FULFILLED, dispatchFull,        FULFILLED",
            "PARTIALLY_FULFILLED, revertSome,          PARTIALLY_FULFILLED",
            "PARTIALLY_FULFILLED, revertAll,           IN_REVIEW",
            "PARTIALLY_FULFILLED, close,               RECHAZADA",
            "FULFILLED,           approve,             RECHAZADA",
            "FULFILLED,           reservationRejected, RECHAZADA",
            "FULFILLED,           reject,              RECHAZADA",
            "FULFILLED,           cancel,              RECHAZADA",
            "FULFILLED,           dispatchPartial,     RECHAZADA",
            "FULFILLED,           dispatchFull,        RECHAZADA",
            "FULFILLED,           revertSome,          PARTIALLY_FULFILLED",
            "FULFILLED,           revertAll,           IN_REVIEW",
            "FULFILLED,           close,               CLOSED",
            "CLOSED,              approve,             RECHAZADA",
            "CLOSED,              reject,              RECHAZADA",
            "CLOSED,              cancel,              RECHAZADA",
            "CLOSED,              dispatchFull,        RECHAZADA",
            "CLOSED,              revertSome,          RECHAZADA",
            "CLOSED,              close,               RECHAZADA",
            "CANCELLED,           approve,             RECHAZADA",
            "CANCELLED,           reserved,            RECHAZADA",
            "CANCELLED,           reject,              RECHAZADA",
            "CANCELLED,           cancel,              RECHAZADA",
            "CANCELLED,           dispatchFull,        RECHAZADA",
            "CANCELLED,           revertAll,           RECHAZADA",
            "CANCELLED,           close,               RECHAZADA",
    })
    void transition(OrderStatus from, String operation, String expected) {
        OrderState state = OrderState.of(from);
        Function<OrderState, OrderState> op = switch (operation) {
            case "approve" -> OrderState::approve;
            case "reserved" -> OrderState::reservationConfirmed;
            case "reservationRejected" -> OrderState::reservationRejected;
            case "reject" -> OrderState::reject;
            case "cancel" -> OrderState::cancel;
            case "dispatchPartial" -> s -> s.dispatched(false);
            case "dispatchFull" -> s -> s.dispatched(true);
            case "revertSome" -> s -> s.dispatchReverted(true);
            case "revertAll" -> s -> s.dispatchReverted(false);
            case "close" -> OrderState::close;
            default -> throw new IllegalArgumentException(operation + " no está en " + OPERATIONS);
        };

        if ("RECHAZADA".equals(expected)) {
            assertThatThrownBy(() -> op.apply(state))
                    .isInstanceOf(InvalidOrderTransitionException.class)
                    .hasFieldOrPropertyWithValue("code", InvalidOrderTransitionException.CODE)
                    .hasMessageContaining(from.label());
        } else {
            assertThat(op.apply(state).status()).isEqualTo(OrderStatus.valueOf(expected));
        }
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void every_status_maps_to_its_state(OrderStatus status) {
        OrderState state = OrderState.of(status);

        assertThat(state.status()).isEqualTo(status);
        assertThat(state.acceptsDispatch())
                .isEqualTo(status == OrderStatus.IN_REVIEW || status == OrderStatus.PARTIALLY_FULFILLED);
    }

    @ParameterizedTest
    @CsvSource({"REGISTERED, Registrado", "APPROVAL_PENDING, Aprobando", "IN_REVIEW, EnRevision",
            "PARTIALLY_FULFILLED, ParcialmenteAtendido", "FULFILLED, Atendido", "CLOSED, Cerrado",
            "CANCELLED, Cancelado"})
    void statuses_have_spanish_labels(OrderStatus status, String label) {
        assertThat(status.label()).isEqualTo(label);
    }
}
