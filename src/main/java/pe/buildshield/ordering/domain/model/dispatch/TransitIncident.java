package pe.buildshield.ordering.domain.model.dispatch;

import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Incidencia registrada mientras el despacho está en tránsito (US32): retraso, falla, daño, accidente, cambio de
 * ruta u otra, con una observación de 3 a 500 caracteres. No se modifica. La crea {@link Dispatch#reportIncident}.
 */
public record TransitIncident(UUID id, UUID dispatchId, IncidentType type, String observation, UUID reportedBy,
        Instant reportedAt) {

    public static final String INVALID_INCIDENT = "INVALID_INCIDENT";
    static final int MIN_OBSERVATION = 3;
    static final int MAX_OBSERVATION = 500;

    public TransitIncident {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dispatchId, "dispatchId");
        Objects.requireNonNull(reportedBy, "reportedBy");
        Objects.requireNonNull(reportedAt, "reportedAt");
        if (type == null) {
            throw new ValidationException(INVALID_INCIDENT, "El tipo de incidencia es obligatorio",
                    List.of(new ErrorDetail("type", "DELAY, VEHICLE_FAILURE, DAMAGE, ACCIDENT, ROUTE_CHANGE u OTHER")));
        }
        observation = observation == null ? "" : observation.trim();
        if (observation.length() < MIN_OBSERVATION || observation.length() > MAX_OBSERVATION) {
            throw new ValidationException(INVALID_INCIDENT, "La observación debe tener de 3 a 500 caracteres",
                    List.of(new ErrorDetail("observation", "de 3 a 500 caracteres")));
        }
    }
}
