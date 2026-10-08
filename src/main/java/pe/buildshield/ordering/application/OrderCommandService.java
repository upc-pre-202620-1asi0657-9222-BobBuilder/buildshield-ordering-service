package pe.buildshield.ordering.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrganizationDirectory.Location;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderRepository;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ResourceNotFoundException;
import pe.buildshield.ordering.shared.messaging.OutboxWriter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso que cambian pedidos: US18 crear y US20 aprobar o rechazar, más los pasos de la saga de reserva de
 * stock (plan §6). Obras, almacenes, materiales y asignaciones se leen de las copias locales
 * ({@link OrganizationDirectory}); la reserva la hace inventario al consumir {@code OrderApproved}.
 *
 * <p>Cada cambio escribe su evento en el outbox en la misma transacción.
 */
@Service
public class OrderCommandService {

    private static final Logger log = LoggerFactory.getLogger(OrderCommandService.class);

    private final OrderRepository orders;
    private final OrganizationDirectory directory;
    private final AccessPolicy access;
    private final OutboxWriter outbox;
    private final Clock clock;

    public OrderCommandService(OrderRepository orders, OrganizationDirectory directory, AccessPolicy access,
            OutboxWriter outbox, Clock clock) {
        this.orders = orders;
        this.directory = directory;
        this.access = access;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** US18: el encargado de obra pide materiales para una obra asignada a un almacén activo. */
    @Transactional
    public OrderView place(PlaceOrder command) {
        UUID requester = access.requester().userId();
        directory.findWorksite(command.worksiteId())
                .filter(worksite -> access.isAssigned(worksite.id()))
                .orElseThrow(() -> new ResourceNotFoundException("WORKSITE_NOT_FOUND", "La obra no existe"));
        Location warehouse = directory.findWarehouse(command.warehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("WAREHOUSE_NOT_FOUND", "El almacén no existe"));
        if (!warehouse.active()) {
            throw new ConflictException("SITE_INACTIVE", "El almacén está desactivado: no recibe pedidos");
        }
        List<OrderLine> lines = command.lines() == null ? List.of()
                : command.lines().stream().map(this::line).toList();
        Order order = Order.place(command.worksiteId(), command.warehouseId(), requester, command.notes(),
                command.requiredDate(), lines, clock.instant());
        Order saved = orders.save(order);
        outbox.append(OrderingEvents.ORDER_REGISTERED, OrderingEvents.ORDER, saved.id(),
                OrderingEvents.OrderRegistered.of(saved));
        return OrderView.of(saved);
    }

    /**
     * US20, saga paso 1: aprueba el encargado del almacén de origen o el administrador. El pedido queda Aprobando
     * y se publica {@code OrderApproved}; inventario reserva y responde de forma asíncrona.
     */
    @Transactional
    public OrderView approve(UUID orderId) {
        Order order = decidableOrder(orderId);
        order.approve(access.requester().userId(), clock.instant());
        Order saved = orders.save(order);
        outbox.append(OrderingEvents.ORDER_APPROVED, OrderingEvents.ORDER, saved.id(),
                OrderingEvents.OrderApproved.of(saved));
        return OrderView.of(saved);
    }

    /** US20: rechazo con motivo obligatorio; el pedido queda Cancelado y se publica {@code OrderRejected}. */
    @Transactional
    public OrderView reject(UUID orderId, String reason) {
        Order order = decidableOrder(orderId);
        order.reject(access.requester().userId(), reason, clock.instant());
        Order saved = orders.save(order);
        outbox.append(OrderingEvents.ORDER_REJECTED, OrderingEvents.ORDER, saved.id(),
                OrderingEvents.OrderFinished.of(saved, saved.rejectionReason()));
        return OrderView.of(saved);
    }

    /**
     * Saga paso 3 ({@code StockReserved}): Aprobando → EnRevision. Si el pedido no existe o ya no está Aprobando,
     * el evento se ignora.
     *
     * @return {@code true} si el pedido cambió
     */
    @Transactional
    public boolean confirmReservation(UUID orderId) {
        Optional<Order> pending = approvalPending(orderId, "StockReserved");
        pending.ifPresent(order -> {
            order.confirmReservation();
            orders.save(order);
        });
        return pending.isPresent();
    }

    /**
     * Saga paso 3 ({@code StockReservationRejected}): Aprobando → Registrado con el motivo de inventario. Si el
     * pedido no existe o ya no está Aprobando, el evento se ignora.
     *
     * @return {@code true} si el pedido cambió
     */
    @Transactional
    public boolean rejectReservation(UUID orderId, String reason) {
        Optional<Order> pending = approvalPending(orderId, "StockReservationRejected");
        pending.ifPresent(order -> {
            order.rejectReservation(reason);
            orders.save(order);
        });
        return pending.isPresent();
    }

    private Optional<Order> approvalPending(UUID orderId, String eventType) {
        Optional<Order> order = orders.findById(orderId);
        if (order.isEmpty()) {
            log.warn("{} del pedido {} ignorado: el pedido no existe", eventType, orderId);
            return Optional.empty();
        }
        if (order.get().status() != OrderStatus.APPROVAL_PENDING) {
            log.info("{} del pedido {} ignorado: el pedido está {}", eventType, orderId, order.get().status());
            return Optional.empty();
        }
        return order;
    }

    private OrderLine line(LineRequest request) {
        Material material = directory.findMaterial(request.materialId())
                .orElseThrow(() -> new ResourceNotFoundException("MATERIAL_NOT_FOUND", "El material no existe"));
        if (!material.active()) {
            throw new ConflictException("MATERIAL_INACTIVE", "El material " + material.sku() + " está retirado del catálogo");
        }
        return OrderLine.request(material.id(), material.sku(), material.unit(), request.quantity());
    }

    /** Solo el administrador o un encargado asignado al almacén de origen; para el resto el pedido no existe. */
    private Order decidableOrder(UUID orderId) {
        return orders.findById(orderId)
                .filter(order -> access.canDecide(order.warehouseId()))
                .orElseThrow(() -> OrderingErrors.orderNotFound(orderId));
    }

    /** @param requiredDate fecha en que la obra necesita el material (opcional) */
    public record PlaceOrder(UUID worksiteId, UUID warehouseId, String notes, LocalDate requiredDate,
            List<LineRequest> lines) {
    }

    public record LineRequest(UUID materialId, BigDecimal quantity) {
    }
}
