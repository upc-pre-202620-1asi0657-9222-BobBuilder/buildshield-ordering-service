package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;
import pe.buildshield.ordering.shared.persistence.AuditableAbstractAggregateRoot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tabla receptions con sus líneas (reception_lines) y su pesaje de llegada (arrival_weighings, a lo más uno: la
 * tabla tiene {@code reception_id} único). El pedido, la obra, el almacén y el manifiesto se leen del despacho.
 */
@Entity
@Table(name = "receptions")
public class ReceptionJpaEntity extends AuditableAbstractAggregateRoot<ReceptionJpaEntity> {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false, updatable = false)
    private DispatchJpaEntity dispatch;

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

    /**
     * @param dispatch      el despacho que se recibe
     * @param dispatchLines busca cada línea del despacho por su id
     */
    ReceptionJpaEntity(Reception reception, DispatchJpaEntity dispatch,
            Function<UUID, DispatchLineJpaEntity> dispatchLines) {
        this.dispatch = dispatch;
        reception.lines().forEach(line -> lines.add(
                new ReceptionLineJpaEntity(line, dispatchLines.apply(line.dispatchLineId()))));
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

    /** Hay un pesaje de llegada anterior a V3, que no guardó su tolerancia. */
    boolean needsCurrentTolerance() {
        return weighings.stream().anyMatch(weighing -> !weighing.hasStoredTolerance());
    }

    List<UUID> materialIds() {
        return lines.stream().map(ReceptionLineJpaEntity::materialId).toList();
    }

    /** @param currentTolerance menor tolerancia actual de los materiales; solo si el pesaje no guardó la suya */
    Reception toDomain(BigDecimal currentTolerance) {
        List<ReceptionLine> domainLines = lines.stream().map(ReceptionLineJpaEntity::toDomain).toList();
        return Reception.restore(getId(), dispatch.getId(), dispatch.orderId(), dispatch.warehouseId(),
                dispatch.worksiteId(), dispatch.manifestCode(), domainLines, status, partialCount, lastPartialAt,
                confirmedBy, confirmedAt,
                weighings.stream().findFirst()
                        .map(weighing -> weighing.toDomain(dispatch.departureNetKg(), currentTolerance)).orElse(null),
                getVersion());
    }
}
