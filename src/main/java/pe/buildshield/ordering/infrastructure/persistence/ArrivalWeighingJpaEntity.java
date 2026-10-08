package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.ordering.domain.model.reception.ArrivalWeighing;
import pe.buildshield.ordering.shared.persistence.OrganizationScopedEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Tabla arrival_weighings: un pesaje de llegada por recepción (US29); no se modifica. */
@Entity
@Table(name = "arrival_weighings")
public class ArrivalWeighingJpaEntity extends OrganizationScopedEntity {

    @Id
    private UUID id;

    @Column(name = "gross_kg", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal grossKg;

    @Column(name = "tare_kg", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal tareKg;

    @Column(name = "net_kg", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal netKg;

    @Column(name = "departure_net_kg", precision = 12, scale = 3, updatable = false)
    private BigDecimal departureNetKg;

    @Column(name = "tolerance_pct", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal tolerancePct;

    @Column(name = "ticket_photo_url", length = 500, updatable = false)
    private String ticketPhotoUrl;

    @Column(name = "weighed_at", nullable = false, updatable = false)
    private Instant weighedAt;

    @Column(name = "weighed_by", nullable = false, updatable = false)
    private UUID weighedBy;

    protected ArrivalWeighingJpaEntity() {
    }

    ArrivalWeighingJpaEntity(ArrivalWeighing weighing) {
        id = UUID.randomUUID();
        grossKg = weighing.grossKg();
        tareKg = weighing.tareKg();
        netKg = weighing.netKg();
        departureNetKg = weighing.departureNetKg();
        tolerancePct = weighing.tolerancePercent();
        ticketPhotoUrl = weighing.ticketPhotoUrl();
        weighedAt = weighing.weighedAt();
        weighedBy = weighing.weighedBy();
    }

    ArrivalWeighing toDomain() {
        return new ArrivalWeighing(grossKg, tareKg, netKg, departureNetKg, tolerancePct, ticketPhotoUrl, weighedAt,
                weighedBy);
    }
}
