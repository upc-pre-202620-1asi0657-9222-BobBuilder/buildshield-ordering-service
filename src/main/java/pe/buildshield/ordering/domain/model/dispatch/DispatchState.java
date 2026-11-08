package pe.buildshield.ordering.domain.model.dispatch;

/**
 * Patrón State del despacho. Cada estado decide qué operaciones admite; lo que no admite lanza
 * {@link InvalidDispatchTransitionException}.
 *
 * <pre>
 * Preparado --salir--> EnTransito --recibir--> Recibido
 *     |  (transportista, pesaje de salida)    (incidencias en ruta, US32)
 *     +--anular (US25)--> Anulado
 * </pre>
 */
public sealed interface DispatchState permits DispatchState.Prepared, DispatchState.InTransit, DispatchState.Received,
        DispatchState.Cancelled {

    DispatchStatus status();

    /** ¿Admite cambios de preparación (transportista y pesaje de salida)? */
    default boolean isEditable() {
        return false;
    }

    /** ¿Admite registrar incidencias en ruta? */
    default boolean acceptsIncidents() {
        return false;
    }

    default DispatchState depart() {
        throw new InvalidDispatchTransitionException(status(), "despachar");
    }

    default DispatchState receive() {
        throw new InvalidDispatchTransitionException(status(), "recibir");
    }

    default DispatchState cancel() {
        throw new InvalidDispatchTransitionException(status(), "anular");
    }

    static DispatchState of(DispatchStatus status) {
        return switch (status) {
            case PREPARED -> new Prepared();
            case IN_TRANSIT -> new InTransit();
            case RECEIVED -> new Received();
            case CANCELLED -> new Cancelled();
        };
    }

    /** En el almacén: se registra el transportista y el pesaje de salida; todavía se puede anular. */
    record Prepared() implements DispatchState {
        public DispatchStatus status() {
            return DispatchStatus.PREPARED;
        }

        public boolean isEditable() {
            return true;
        }

        public DispatchState depart() {
            return new InTransit();
        }

        public DispatchState cancel() {
            return new Cancelled();
        }
    }

    /** Salió del almacén: se registran incidencias y la obra lo recibe. */
    record InTransit() implements DispatchState {
        public DispatchStatus status() {
            return DispatchStatus.IN_TRANSIT;
        }

        public boolean acceptsIncidents() {
            return true;
        }

        public DispatchState receive() {
            return new Received();
        }
    }

    record Received() implements DispatchState {
        public DispatchStatus status() {
            return DispatchStatus.RECEIVED;
        }
    }

    /** Anulado antes de salir (US25). */
    record Cancelled() implements DispatchState {
        public DispatchStatus status() {
            return DispatchStatus.CANCELLED;
        }
    }
}
