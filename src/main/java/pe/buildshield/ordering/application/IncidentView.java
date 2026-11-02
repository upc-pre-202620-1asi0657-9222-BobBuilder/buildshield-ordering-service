package pe.buildshield.ordering.application;

import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncident;

import java.time.Instant;
import java.util.UUID;

/** Incidencia en ruta de un despacho (US32). */
public record IncidentView(UUID id, UUID dispatchId, IncidentType type, String observation, String photoUrl,
        UUID reportedBy, Instant reportedAt) {

    public static IncidentView of(TransitIncident incident) {
        return new IncidentView(incident.id(), incident.dispatchId(), incident.type(), incident.observation(),
                incident.photoUrl(), incident.reportedBy(), incident.reportedAt());
    }
}
