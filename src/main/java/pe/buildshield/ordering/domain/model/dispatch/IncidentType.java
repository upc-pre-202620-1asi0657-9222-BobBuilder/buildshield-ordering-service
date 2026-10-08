package pe.buildshield.ordering.domain.model.dispatch;

/** Tipo de incidencia en ruta (US32), con su nombre visible en español. */
public enum IncidentType {

    DELAY("Retraso"),
    VEHICLE_FAILURE("Falla del vehículo"),
    DAMAGE("Daño de la carga"),
    ACCIDENT("Accidente"),
    ROUTE_CHANGE("Cambio de ruta"),
    OTHER("Otra");

    private final String label;

    IncidentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
