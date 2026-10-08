package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.ordering.domain.model.dispatch.DispatchLine;
import pe.buildshield.ordering.shared.persistence.OrganizationScopedEntity;

import java.math.BigDecimal;
import java.util.UUID;

/** Tabla dispatch_lines; lleva organization_id como toda tabla de negocio. */
@Entity
@Table(name = "dispatch_lines")
public class DispatchLineJpaEntity extends OrganizationScopedEntity {

    @Id
    private UUID id;

    @Column(name = "order_line_id", nullable = false, updatable = false)
    private UUID orderLineId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantity;

    protected DispatchLineJpaEntity() {
    }

    DispatchLineJpaEntity(DispatchLine line) {
        id = UUID.randomUUID();
        orderLineId = line.orderLineId();
        materialId = line.materialId();
        quantity = line.quantity();
    }

    DispatchLine toDomain() {
        return new DispatchLine(id, orderLineId, materialId, quantity);
    }
}
