package pe.buildshield.ordering.domain.model.reception;

/** Estados de una recepción en obra, con su nombre visible en español. */
public enum ReceptionStatus {

    IN_PROGRESS("EnCurso"),
    /** Hubo al menos una recepción parcial (US38); el despacho sigue en tránsito. */
    PARTIALLY_RECEIVED("RecibidaParcialmente"),
    CONFIRMED("Confirmada");

    private final String label;

    ReceptionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
