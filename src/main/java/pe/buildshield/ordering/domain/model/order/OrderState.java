package pe.buildshield.ordering.domain.model.order;

/**
 * Patrón State del pedido. Cada estado decide qué transiciones admite; lo que no admite lanza
 * {@link InvalidOrderTransitionException}.
 *
 * <pre>
 * Registrado --aprobar--> Aprobando --StockReserved--> EnRevision --despacho--> ParcialmenteAtendido --> Atendido --cerrar--> Cerrado
 * Aprobando --StockReservationRejected--> Registrado
 * ParcialmenteAtendido o Atendido --anular despacho--> EnRevision (nada despachado) o ParcialmenteAtendido
 * Registrado --rechazar o cancelar--> Cancelado; EnRevision --cancelar--> Cancelado
 * </pre>
 */
public sealed interface OrderState permits OrderState.Registered, OrderState.ApprovalPending, OrderState.InReview,
        OrderState.PartiallyFulfilled, OrderState.Fulfilled, OrderState.Closed, OrderState.Cancelled {

    OrderStatus status();

    default OrderState approve() {
        throw new InvalidOrderTransitionException(status(), "aprobar");
    }

    /** Inventario reservó el stock ({@code StockReserved}). */
    default OrderState reservationConfirmed() {
        throw new InvalidOrderTransitionException(status(), "confirmar la reserva de");
    }

    /** Inventario no pudo reservar ({@code StockReservationRejected}): vuelve a Registrado. */
    default OrderState reservationRejected() {
        throw new InvalidOrderTransitionException(status(), "rechazar la reserva de");
    }

    default OrderState reject() {
        throw new InvalidOrderTransitionException(status(), "rechazar");
    }

    default OrderState cancel() {
        throw new InvalidOrderTransitionException(status(), "cancelar");
    }

    default boolean acceptsDispatch() {
        return false;
    }

    /** Estado después de un despacho; {@code fullyDispatched} si ya no queda nada pendiente. */
    default OrderState dispatched(boolean fullyDispatched) {
        throw new InvalidOrderTransitionException(status(), "despachar");
    }

    /**
     * Estado después de anular un despacho (US25): lo despachado vuelve a lo pendiente.
     *
     * @param anythingDispatched si aún queda algo despachado por otros despachos
     */
    default OrderState dispatchReverted(boolean anythingDispatched) {
        throw new InvalidOrderTransitionException(status(), "anular un despacho de");
    }

    default OrderState close() {
        throw new InvalidOrderTransitionException(status(), "cerrar");
    }

    static OrderState of(OrderStatus status) {
        return switch (status) {
            case REGISTERED -> new Registered();
            case APPROVAL_PENDING -> new ApprovalPending();
            case IN_REVIEW -> new InReview();
            case PARTIALLY_FULFILLED -> new PartiallyFulfilled();
            case FULFILLED -> new Fulfilled();
            case CLOSED -> new Closed();
            case CANCELLED -> new Cancelled();
        };
    }

    private static OrderState afterRevert(boolean anythingDispatched) {
        return anythingDispatched ? new PartiallyFulfilled() : new InReview();
    }

    /** Recién creado (o con la reserva rechazada), pendiente de aprobación. */
    record Registered() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.REGISTERED;
        }

        public OrderState approve() {
            return new ApprovalPending();
        }

        public OrderState reject() {
            return new Cancelled();
        }

        public OrderState cancel() {
            return new Cancelled();
        }
    }

    /** Aprobado; inventario está reservando el stock. Todavía no se despacha. */
    record ApprovalPending() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.APPROVAL_PENDING;
        }

        public OrderState reservationConfirmed() {
            return new InReview();
        }

        public OrderState reservationRejected() {
            return new Registered();
        }
    }

    /** Aprobado con el stock reservado: el almacén lo prepara y despacha. */
    record InReview() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.IN_REVIEW;
        }

        public boolean acceptsDispatch() {
            return true;
        }

        public OrderState dispatched(boolean fullyDispatched) {
            return fullyDispatched ? new Fulfilled() : new PartiallyFulfilled();
        }

        public OrderState dispatchReverted(boolean anythingDispatched) {
            return afterRevert(anythingDispatched);
        }

        public OrderState cancel() {
            return new Cancelled();
        }
    }

    record PartiallyFulfilled() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.PARTIALLY_FULFILLED;
        }

        public boolean acceptsDispatch() {
            return true;
        }

        public OrderState dispatched(boolean fullyDispatched) {
            return fullyDispatched ? new Fulfilled() : this;
        }

        public OrderState dispatchReverted(boolean anythingDispatched) {
            return afterRevert(anythingDispatched);
        }
    }

    /** Todo despachado (o lo pendiente cancelado); se cierra al completar la recepción. */
    record Fulfilled() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.FULFILLED;
        }

        public OrderState dispatchReverted(boolean anythingDispatched) {
            return afterRevert(anythingDispatched);
        }

        public OrderState close() {
            return new Closed();
        }
    }

    record Closed() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.CLOSED;
        }
    }

    record Cancelled() implements OrderState {
        public OrderStatus status() {
            return OrderStatus.CANCELLED;
        }
    }
}
