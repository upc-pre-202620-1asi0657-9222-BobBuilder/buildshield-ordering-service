package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;
import pe.buildshield.ordering.shared.persistence.AuditableAbstractAggregateRoot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tabla receptions con sus líneas (reception_lines) y su pesaje de llegada (arrival_weighings, a lo más uno: la
 * tabla tiene {@code reception_id} único).
 */
@Entity
@Table(name = "receptions")
public class ReceptionJpaEntity extends AuditableAbstractAggregateRoot<ReceptionJpaEntity> {

    @Column(name = "dispatch_id", nullable = false, updatable = false)
    private UUID dispatchId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "worksite_id", nullable = false, updatable = false)
    private UUID worksiteId;

    @Column(name = "manifest_code", nullable = false, length = 30, updatable = false)
    private String manifestCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReceptionStatus status;

    @Column(name = "partial_count", nullable = false)
    private int partialCount;

    @Column(name = "last_partial_at")
    private Instant lastPartialAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "reception_id", nullable = false, updatable = false)
    private List<ReceptionLineJpaEntity> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "reception_id", nullable = false, updatable = false)
    @BatchSize(size = 50)
    private List<ArrivalWeighingJpaEntity> weighings = new ArrayList<>();

    protected ReceptionJpaEntity() {
    }

    ReceptionJpaEntity(Reception reception) {
        dispatchId = reception.dispatchId();
        orderId = reception.orderId();
        warehouseId = reception.warehouseId();
        worksiteId = reception.worksiteId();
        manifestCode = reception.manifestCode();
        reception.lines().forEach(line -> lines.add(new ReceptionLineJpaEntity(line)));
        status = reception.status();
        partialCount = reception.partialCount();
    }

    /** Copia lo que cambia: estado, parciales, conformidad, pesaje de llegada y lo recibido de cada línea. */
    void apply(Reception reception) {
        status = reception.status();
        partialCount = reception.partialCount();
        lastPartialAt = reception.lastPartialAt();
        confirmedBy = reception.confirmedBy();
        confirmedAt = reception.confirmedAt();
        for (ReceptionLineJpaEntity line : lines) {
            line.apply(reception.line(line.id()));
        }
        if (weighings.isEmpty()) {
            reception.arrivalWeighing().ifPresent(weighing -> weighings.add(new ArrivalWeighingJpaEntity(weighing)));
        }
    }

    Reception toDomain() {
        List<ReceptionLine> domainLines = lines.stream().map(ReceptionLineJpaEntity::toDomain).toList();
        return Reception.restore(getId(), dispatchId, orderId, warehouseId, worksiteId, manifestCode, domainLines,
                status, partialCount, lastPartialAt, confirmedBy, confirmedAt,
                weighings.stream().findFirst().map(ArrivalWeighingJpaEntity::toDomain).orElse(null), getVersion());
    }
}
