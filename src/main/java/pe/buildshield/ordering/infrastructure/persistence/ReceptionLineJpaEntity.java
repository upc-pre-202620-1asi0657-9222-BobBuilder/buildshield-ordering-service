package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.shared.persistence.OrganizationScopedEntity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tabla reception_lines. Lo despachado, el material y la línea de pedido se leen de la línea del despacho.
 * {@code stocked_qty} no está en el ERD: sin ese dato la conformidad no sabe qué falta ingresar (DESVIACIONES.md).
 */
@Entity
@Table(name = "reception_lines")
public class ReceptionLineJpaEntity extends OrganizationScopedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_line_id", nullable = false, updatable = false)
    private DispatchLineJpaEntity dispatchLine;

    @Column(name = "received_qty", precision = 14, scale = 3)
    private BigDecimal receivedQty;

    @Column(name = "stocked_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal stockedQty;

    @Column(name = "shrinkage_pct", precision = 5, scale = 2)
    private BigDecimal shrinkagePct;

    protected ReceptionLineJpaEntity() {
    }

    ReceptionLineJpaEntity(ReceptionLine line, DispatchLineJpaEntity dispatchLine) {
        id = UUID.randomUUID();
        this.dispatchLine = dispatchLine;
        apply(line);
    }

    void apply(ReceptionLine line) {
        receivedQty = line.receivedQty();
        stockedQty = line.stockedQty();
        shrinkagePct = line.shrinkagePercent();
    }

    UUID id() {
        return id;
    }

    UUID materialId() {
        return dispatchLine.materialId();
    }

    ReceptionLine toDomain() {
        return ReceptionLine.restore(id, dispatchLine.id(), dispatchLine.orderLineId(), dispatchLine.materialId(),
                dispatchLine.quantity(), receivedQty, stockedQty, shrinkagePct);
    }
}
