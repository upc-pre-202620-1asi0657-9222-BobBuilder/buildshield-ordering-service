package pe.buildshield.ordering.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.domain.model.dispatch.Carrier;
import pe.buildshield.ordering.domain.model.dispatch.DepartureWeighing;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchLine;
import pe.buildshield.ordering.domain.model.dispatch.DispatchRepository;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.dispatch.ManifestCode;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncident;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncidentRepository;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderRepository;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;
import pe.buildshield.ordering.shared.messaging.OutboxWriter;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Casos de uso que cambian despachos: US22 generar, US23 partición del pedido, US27 pesaje de salida, US30
 * transportista, salida del almacén, US25 anulación y US32 incidencias en ruta. El pedido es del mismo contexto: se
 * actualiza en la misma transacción. El stock lo mueve inventario al consumir {@code DispatchDeparted}.
 *
 * <p>Quién: preparan, completan, despachan, anulan y registran incidencias el administrador o un encargado de
 * almacén asignado al almacén de origen. Para el resto el despacho (o el pedido) no existe: 404.
 */
@Service
public class DispatchCommandService {

    public static final String ORDER_NOT_DISPATCHABLE = "ORDER_NOT_DISPATCHABLE";

    private final DispatchRepository dispatches;
    private final TransitIncidentRepository incidents;
    private final OrderRepository orders;
    private final AccessPolicy access;
    private final EvidenceStorage evidence;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final RandomGenerator random;

    @Autowired
    public DispatchCommandService(DispatchRepository dispatches, TransitIncidentRepository incidents,
            OrderRepository orders, AccessPolicy access, EvidenceStorage evidence, OutboxWriter outbox, Clock clock) {
        this(dispatches, incidents, orders, access, evidence, outbox, clock, new SecureRandom());
    }

    DispatchCommandService(DispatchRepository dispatches, TransitIncidentRepository incidents, OrderRepository orders,
            AccessPolicy access, EvidenceStorage evidence, OutboxWriter outbox, Clock clock, RandomGenerator random) {
        this.dispatches = dispatches;
        this.incidents = incidents;
        this.orders = orders;
        this.access = access;
        this.evidence = evidence;
        this.outbox = outbox;
        this.clock = clock;
        this.random = random;
    }

    /**
     * US22, US23: prepara un despacho de un pedido aprobado (EnRevision o ParcialmenteAtendido). Cada cantidad es
     * mayor que cero y no supera lo pendiente de su línea; lo despachado se suma al pedido en la misma transacción,
     * así dos despachos simultáneos no pueden asignar más de lo pedido.
     */
    @Transactional
    public DispatchView create(CreateDispatch command) {
        Order order = orders.findById(command.orderId())
                .filter(found -> access.canManageWarehouse(found.warehouseId()))
                .orElseThrow(() -> OrderingErrors.orderNotFound(command.orderId()));
        if (!order.acceptsDispatch()) {
            throw new ConflictException(ORDER_NOT_DISPATCHABLE,
                    "Solo se despachan pedidos aprobados con material pendiente; el pedido está " + order.status());
        }
        List<DispatchLine> lines = command.lines() == null ? List.of()
                : command.lines().stream().map(request -> line(order, request)).toList();
        Dispatch.requireLines(lines);
        Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
        lines.forEach(line -> quantities.put(line.orderLineId(), line.quantity()));
        order.registerDispatch(quantities);
        Order updated = orders.save(order);
        DispatchType type = updated.hasPending() ? DispatchType.PARTIAL : DispatchType.COMPLETE;
        Instant now = clock.instant();
        Dispatch saved = dispatches.save(Dispatch.prepare(order.id(), order.warehouseId(), order.worksiteId(), type,
                ManifestCode.generate(now, random), lines, now));
        outbox.append(OrderingEvents.DISPATCH_PREPARED, OrderingEvents.DISPATCH, saved.id(),
                OrderingEvents.DispatchPrepared.of(saved));
        return DispatchView.of(saved, 0);
    }

    /** US30: transportista, solo mientras el despacho está Preparado. */
    @Transactional
    public DispatchView assignCarrier(UUID dispatchId, String name, String document, String plate) {
        Dispatch dispatch = manageable(dispatchId);
        dispatch.assignCarrier(new Carrier(name, document, plate));
        Dispatch saved = dispatches.save(dispatch);
        outbox.append(OrderingEvents.DISPATCH_CARRIER_ASSIGNED, OrderingEvents.DISPATCH, saved.id(),
                OrderingEvents.DispatchCarrierAssigned.of(saved));
        return view(saved);
    }

    /** US27: un solo pesaje de salida, solo mientras el despacho está Preparado. */
    @Transactional
    public DispatchView recordDepartureWeighing(UUID dispatchId, BigDecimal grossKg, BigDecimal tareKg,
            String ticketPhotoUrl) {
        Dispatch dispatch = manageable(dispatchId);
        String evidenceUrl = evidence.registerTicketPhoto(dispatchId, ticketPhotoUrl);
        dispatch.recordDepartureWeighing(DepartureWeighing.of(grossKg, tareKg, evidenceUrl, clock.instant(),
                access.requester().userId()));
        Dispatch saved;
        try {
            saved = dispatches.save(dispatch);
        } catch (DataIntegrityViolationException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uk_departure_weighings_dispatch")) {
                throw new ConflictException(Dispatch.DEPARTURE_WEIGHING_ALREADY_RECORDED,
                        "El despacho ya tiene su pesaje de salida");
            }
            throw ex;
        }
        outbox.append(OrderingEvents.DISPATCH_DEPARTURE_WEIGHED, OrderingEvents.DISPATCH, saved.id(),
                OrderingEvents.DispatchDepartureWeighed.of(saved));
        return view(saved);
    }

    /**
     * Salida del almacén (Preparado → EnTransito): exige transportista y pesaje. Publica {@code DispatchDeparted};
     * inventario consume la reserva de cada línea al recibirlo.
     */
    @Transactional
    public DispatchView depart(UUID dispatchId) {
        Dispatch dispatch = manageable(dispatchId);
        dispatch.depart(clock.instant());
        Dispatch saved = dispatches.save(dispatch);
        outbox.append(OrderingEvents.DISPATCH_DEPARTED, OrderingEvents.DISPATCH, saved.id(),
                OrderingEvents.DispatchDeparted.of(saved));
        return view(saved);
    }

    /**
     * US25: anula un despacho Preparado con motivo. Lo despachado vuelve a lo pendiente de cada línea del pedido
     * (EnRevision si ya no queda nada despachado, ParcialmenteAtendido si hay otros despachos). La reserva de stock
     * del pedido no cambia. Publica {@code DispatchCancelled}.
     */
    @Transactional
    public DispatchView cancel(UUID dispatchId, String reason) {
        Dispatch dispatch = manageable(dispatchId);
        dispatch.cancel(access.requester().userId(), reason, clock.instant());
        Order order = orders.findById(dispatch.orderId())
                .orElseThrow(() -> OrderingErrors.orderNotFound(dispatch.orderId()));
        order.revertDispatch(dispatch.quantitiesByOrderLine());
        orders.save(order);
        Dispatch saved = dispatches.save(dispatch);
        outbox.append(OrderingEvents.DISPATCH_CANCELLED, OrderingEvents.DISPATCH, saved.id(),
                OrderingEvents.DispatchCancelled.of(saved));
        return view(saved);
    }

    /** US32: incidencia en ruta, solo mientras el despacho está EnTransito. Publica {@code TransitIncidentReported}. */
    @Transactional
    public IncidentView reportIncident(UUID dispatchId, IncidentType type, String observation) {
        Dispatch dispatch = manageable(dispatchId);
        TransitIncident incident = incidents.save(dispatch.reportIncident(type, observation,
                access.requester().userId(), clock.instant()));
        outbox.append(OrderingEvents.TRANSIT_INCIDENT_REPORTED, OrderingEvents.DISPATCH, dispatch.id(),
                OrderingEvents.TransitIncidentReported.of(dispatch, incident));
        return IncidentView.of(incident);
    }

    private DispatchView view(Dispatch dispatch) {
        return DispatchView.of(dispatch, incidents.countByDispatchIds(List.of(dispatch.id()))
                .getOrDefault(dispatch.id(), 0));
    }

    private static DispatchLine line(Order order, LineRequest request) {
        OrderLine orderLine = order.lines().stream()
                .filter(candidate -> request.orderLineId() != null && request.orderLineId().equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new ValidationException(Order.ORDER_LINE_NOT_FOUND, "La línea "
                        + request.orderLineId() + " no pertenece al pedido",
                        List.of(new ErrorDetail("orderLineId", String.valueOf(request.orderLineId())))));
        return DispatchLine.of(orderLine.id(), orderLine.materialId(), request.quantity());
    }

    private Dispatch manageable(UUID dispatchId) {
        return dispatches.findById(dispatchId)
                .filter(dispatch -> access.canManageWarehouse(dispatch.warehouseId()))
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(dispatchId));
    }

    public record CreateDispatch(UUID orderId, List<LineRequest> lines) {
    }

    public record LineRequest(UUID orderLineId, BigDecimal quantity) {
    }
}
