package pe.buildshield.ordering.domain.model.order;

/** Estados de un pedido, con su nombre visible en español. */
public enum OrderStatus {

    REGISTERED("Registrado"),
    /** Aprobado por el almacén, esperando la respuesta de inventario a la reserva de stock (saga). */
    APPROVAL_PENDING("Aprobando"),
    IN_REVIEW("EnRevision"),
    PARTIALLY_FULFILLED("ParcialmenteAtendido"),
    FULFILLED("Atendido"),
    CLOSED("Cerrado"),
    CANCELLED("Cancelado");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
