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
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.shared.persistence.AuditableAbstractAggregateRoot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tabla orders con sus líneas (order_lines). */
@Entity
@Table(name = "orders")
public class OrderJpaEntity extends AuditableAbstractAggregateRoot<OrderJpaEntity> {

    @Column(name = "worksite_id", nullable = false, updatable = false)
    private UUID worksiteId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "required_date")
    private LocalDate requiredDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private OrderStatus status;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    @BatchSize(size = 50)
    private List<OrderLineJpaEntity> lines = new ArrayList<>();

    protected OrderJpaEntity() {
    }

    OrderJpaEntity(Order order) {
        worksiteId = order.worksiteId();
        warehouseId = order.warehouseId();
        notes = order.notes();
        requiredDate = order.requiredDate();
        order.lines().forEach(line -> lines.add(new OrderLineJpaEntity(line)));
        apply(order);
    }

    /**
     * Copia lo que cambia durante la vida del pedido: estado, decisión y cantidades de cada línea. Mientras está
     * Registrado (US19) también cambian las observaciones, la fecha requerida y qué materiales lleva: una línea se
     * reconoce por su material.
     */
    void apply(Order order) {
        status = order.status();
        rejectionReason = order.rejectionReason();
        decidedBy = order.decidedBy();
        decidedAt = order.decidedAt();
        notes = order.notes();
        requiredDate = order.requiredDate();
        lines.removeIf(entity -> order.lines().stream().noneMatch(line -> line.materialId().equals(entity.materialId())));
        for (OrderLine line : order.lines()) {
            lines.stream().filter(entity -> entity.materialId().equals(line.materialId())).findFirst()
                    .ifPresentOrElse(entity -> entity.apply(line), () -> lines.add(new OrderLineJpaEntity(line)));
        }
    }

    List<UUID> materialIds() {
        return lines.stream().map(OrderLineJpaEntity::materialId).toList();
    }

    List<UUID> lineIds() {
        return lines.stream().map(OrderLineJpaEntity::id).toList();
    }

    /**
     * Quien pidió y cuándo son la auditoría de la fila. Las líneas van ordenadas por SKU.
     *
     * @param materials copia local de los materiales, por id
     * @param received  lo ya ingresado a la obra, por id de línea
     */
    Order toDomain(Map<UUID, Material> materials, Map<UUID, BigDecimal> received) {
        List<OrderLine> domainLines = lines.stream()
                .map(line -> line.toDomain(materials.get(line.materialId()),
                        received.getOrDefault(line.id(), BigDecimal.ZERO)))
                .sorted(Comparator.comparing(OrderLine::sku, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return Order.restore(getId(), worksiteId, warehouseId, getCreatedBy(), notes, requiredDate, getCreatedAt(),
                domainLines, status, rejectionReason, decidedBy, decidedAt, getVersion());
    }
}
