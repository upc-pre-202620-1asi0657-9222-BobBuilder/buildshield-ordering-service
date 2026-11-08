package pe.buildshield.ordering.application;

import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Pedido con su estado y cantidades por material (respuesta de los casos de uso de pedidos). */
public record OrderView(UUID id, UUID worksiteId, UUID warehouseId, UUID requestedBy, OrderStatus status,
        String notes, LocalDate requiredDate, String rejectionReason, Instant placedAt, UUID decidedBy,
        Instant decidedAt, List<LineView> lines) {

    public static OrderView of(Order order) {
        return new OrderView(order.id(), order.worksiteId(), order.warehouseId(), order.requestedBy(), order.status(),
                order.notes(), order.requiredDate(), order.rejectionReason(), order.placedAt(), order.decidedBy(),
                order.decidedAt(), order.lines().stream().map(LineView::of).toList());
    }

    public record LineView(UUID id, UUID materialId, String sku, String unit, BigDecimal requested,
            BigDecimal dispatched, BigDecimal cancelled, BigDecimal received, BigDecimal pending) {

        static LineView of(OrderLine line) {
            return new LineView(line.id(), line.materialId(), line.sku(), line.unit(), line.requested(),
                    line.dispatched(), line.cancelled(), line.received(), line.pending());
        }
    }
}
