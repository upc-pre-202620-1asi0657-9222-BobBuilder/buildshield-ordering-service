package pe.buildshield.ordering.application;

import pe.buildshield.ordering.shared.error.ResourceNotFoundException;

import java.util.UUID;

/** Errores 404 comunes: lo que no existe o el usuario no puede ver responde igual (no se revela si existe). */
public final class OrderingErrors {

    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String DISPATCH_NOT_FOUND = "DISPATCH_NOT_FOUND";
    public static final String RECEPTION_NOT_FOUND = "RECEPTION_NOT_FOUND";

    private OrderingErrors() {
    }

    public static ResourceNotFoundException orderNotFound(UUID orderId) {
        return new ResourceNotFoundException(ORDER_NOT_FOUND, "El pedido " + orderId + " no existe");
    }

    public static ResourceNotFoundException dispatchNotFound(Object dispatch) {
        return new ResourceNotFoundException(DISPATCH_NOT_FOUND, "El despacho " + dispatch + " no existe");
    }

    public static ResourceNotFoundException receptionNotFound(UUID receptionId) {
        return new ResourceNotFoundException(RECEPTION_NOT_FOUND, "La recepción " + receptionId + " no existe");
    }
}
