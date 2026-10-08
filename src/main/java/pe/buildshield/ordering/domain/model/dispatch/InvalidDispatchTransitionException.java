package pe.buildshield.ordering.domain.model.dispatch;

import pe.buildshield.ordering.shared.error.ConflictException;

/** La operación no está permitida en el estado actual del despacho. Se responde 409. */
public class InvalidDispatchTransitionException extends ConflictException {

    public static final String CODE = "INVALID_DISPATCH_TRANSITION";

    public InvalidDispatchTransitionException(DispatchStatus current, String operation) {
        super(CODE, "No se puede " + operation + " un despacho en estado " + current.label());
    }
}
