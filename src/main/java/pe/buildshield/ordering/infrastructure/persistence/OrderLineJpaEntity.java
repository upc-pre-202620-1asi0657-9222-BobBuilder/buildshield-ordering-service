package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.shared.persistence.OrganizationScopedEntity;
import pe.buildshield.ordering.domain.model.order.OrderLine;

import java.math.BigDecimal;
import java.util.UUID;

/** Tabla order_lines. El SKU y la unidad salen de material_projection y lo recibido, de reception_lines. */
@Entity
@Table(name = "order_lines")
public class OrderLineJpaEntity extends OrganizationScopedEntity {

    @Id
    private UUID id;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "requested_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal requestedQty;

    @Column(name = "dispatched_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal dispatchedQty;

    @Column(name = "cancelled_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal cancelledQty;

    protected OrderLineJpaEntity() {
    }

    OrderLineJpaEntity(OrderLine line) {
        id = UUID.randomUUID();
        materialId = line.materialId();
        apply(line);
    }

    /** Lo solicitado solo cambia al editar un pedido Registrado (US19); lo despachado y cancelado, durante su vida. */
    void apply(OrderLine line) {
        requestedQty = line.requested();
        dispatchedQty = line.dispatched();
        cancelledQty = line.cancelled();
    }

    UUID id() {
        return id;
    }

    UUID materialId() {
        return materialId;
    }

    /**
     * @param material copia local del material, de donde salen el SKU y la unidad
     * @param received lo ya ingresado a la obra por esta línea, sumado de reception_lines
     */
    OrderLine toDomain(Material material, BigDecimal received) {
        return OrderLine.restore(id, materialId, material == null ? null : material.sku(),
                material == null ? null : material.unit(), requestedQty, dispatchedQty, cancelledQty, received);
    }
}
