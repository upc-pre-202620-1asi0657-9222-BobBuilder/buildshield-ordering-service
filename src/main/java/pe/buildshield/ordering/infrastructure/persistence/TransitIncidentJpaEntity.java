package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncident;
import pe.buildshield.ordering.shared.persistence.OrganizationScopedEntity;

import java.time.Instant;
import java.util.UUID;

/** Tabla transit_incidents: incidencias en ruta (US32); no se modifican. */
@Entity
@Table(name = "transit_incidents")
public class TransitIncidentJpaEntity extends OrganizationScopedEntity {

    @Id
    private UUID id;

    @Column(name = "dispatch_id", nullable = false, updatable = false)
    private UUID dispatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private IncidentType type;

    @Column(name = "observation", nullable = false, length = 500, updatable = false)
    private String observation;

    @Column(name = "reported_by", nullable = false, updatable = false)
    private UUID reportedBy;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    protected TransitIncidentJpaEntity() {
    }

    TransitIncidentJpaEntity(TransitIncident incident) {
        id = incident.id();
        dispatchId = incident.dispatchId();
        type = incident.type();
        observation = incident.observation();
        reportedBy = incident.reportedBy();
        reportedAt = incident.reportedAt();
    }

    TransitIncident toDomain() {
        return new TransitIncident(id, dispatchId, type, observation, reportedBy, reportedAt);
    }
}
