package pe.buildshield.ordering.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderRepository;

import java.util.List;
import java.util.UUID;

/**
 * US21: consultar pedidos. El administrador ve todos los de su organización; el encargado de obra, los de sus
 * obras; el encargado de almacén, los de sus almacenes. Lo demás responde 404.
 */
@Service
public class OrderQueryService {

    private final OrderRepository orders;
    private final AccessPolicy access;

    public OrderQueryService(OrderRepository orders, AccessPolicy access) {
        this.orders = orders;
        this.access = access;
    }

    /** Estado y cantidades por material. */
    @Transactional(readOnly = true)
    public OrderView get(UUID orderId) {
        return orders.findById(orderId)
                .filter(order -> access.canSee(order.warehouseId(), order.worksiteId()))
                .map(OrderView::of)
                .orElseThrow(() -> OrderingErrors.orderNotFound(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderView> list() {
        List<Order> visible = access.isAdministrator() ? orders.findAll() : orders.findBySites(access.assignedSites());
        return visible.stream().map(OrderView::of).toList();
    }
}
