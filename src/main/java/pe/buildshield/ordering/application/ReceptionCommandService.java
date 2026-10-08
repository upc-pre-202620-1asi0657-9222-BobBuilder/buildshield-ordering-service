package pe.buildshield.ordering.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.dispatch.DepartureWeighing;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchLine;
import pe.buildshield.ordering.domain.model.dispatch.DispatchRepository;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderRepository;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.domain.model.reception.ArrivalWeighing;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.domain.model.reception.ReceptionRepository;
import pe.buildshield.ordering.domain.model.reception.Shrinkage;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;
import pe.buildshield.ordering.shared.messaging.OutboxWriter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso que cambian recepciones: abrir (por despacho o por código de manifiesto, US34), US35 registrar lo
 * recibido, US38 recepción parcial, US37 conformidad (QAS10) y US29 pesaje de llegada. El pedido y el despacho son
 * del mismo contexto y se actualizan en la misma transacción; el stock de la obra lo suma inventario al consumir
 * {@code ReceptionPartiallyReceived} y {@code ReceptionConfirmed} (solo {@code deltaQuantity}).
 *
 * <p>Quién: abren, registran y confirman el administrador o un encargado de obra asignado a la obra de destino. Para
 * el resto la recepción (o el despacho) no existe: 404.
 */
@Service
public class ReceptionCommandService {

    public static final String RECEPTION_ALREADY_EXISTS = "RECEPTION_ALREADY_EXISTS";
    public static final String DISPATCH_NOT_IN_TRANSIT = "DISPATCH_NOT_IN_TRANSIT";
    public static final String INVALID_RECEPTION_REQUEST = "INVALID_RECEPTION_REQUEST";

    private final ReceptionRepository receptions;
    private final DispatchRepository dispatches;
    private final OrderRepository orders;
    private final OrganizationDirectory directory;
    private final AccessPolicy access;
    private final EvidenceStorage evidence;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ReceptionCommandService(ReceptionRepository receptions, DispatchRepository dispatches,
            OrderRepository orders, OrganizationDirectory directory, AccessPolicy access, EvidenceStorage evidence,
            OutboxWriter outbox, Clock clock) {
        this.receptions = receptions;
        this.dispatches = dispatches;
        this.orders = orders;
        this.directory = directory;
        this.access = access;
        this.evidence = evidence;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Abre la recepción de un despacho en tránsito hacia una obra del usuario, identificado por su id o por el
     * código del manifiesto (exactamente uno). Hay una sola por despacho: si ya existe responde 409
     * {@code RECEPTION_ALREADY_EXISTS} con su id en los detalles.
     */
    @Transactional
    public ReceptionView start(UUID dispatchId, String manifestCode) {
        boolean byCode = manifestCode != null && !manifestCode.isBlank();
        if ((dispatchId == null) == !byCode) {
            throw new ValidationException(INVALID_RECEPTION_REQUEST,
                    "Indica el despacho por dispatchId o por manifestCode (solo uno)",
                    List.of(new ErrorDetail("dispatchId", "dispatchId o manifestCode, exactamente uno")));
        }
        Optional<Dispatch> found = byCode ? dispatches.findByManifestCode(manifestCode.trim().toUpperCase())
                : dispatches.findById(dispatchId);
        Dispatch dispatch = found.filter(candidate -> access.canWorkWorksite(candidate.worksiteId()))
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(byCode ? manifestCode : dispatchId));
        receptions.findIdByDispatchId(dispatch.id()).ifPresent(existing -> {
            throw alreadyExists(existing);
        });
        if (dispatch.status() != DispatchStatus.IN_TRANSIT) {
            throw new ConflictException(DISPATCH_NOT_IN_TRANSIT,
                    "Solo se recibe un despacho en tránsito; el despacho está " + dispatch.status());
        }
        List<ReceptionLine> lines = dispatch.lines().stream()
                .map(line -> ReceptionLine.expect(line.id(), line.orderLineId(), line.materialId(), line.quantity()))
                .toList();
        Reception reception = Reception.start(dispatch.id(), dispatch.orderId(), dispatch.warehouseId(),
                dispatch.worksiteId(), dispatch.manifestCode().value(), lines);
        Reception saved;
        try {
            saved = receptions.save(reception);
        } catch (DataIntegrityViolationException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uk_receptions_dispatch")) {
                throw alreadyExists(null);
            }
            throw ex;
        }
        outbox.append(OrderingEvents.RECEPTION_OPENED, OrderingEvents.RECEPTION, saved.id(),
                OrderingEvents.ReceptionOpened.of(saved));
        return ReceptionView.of(saved);
    }

    /**
     * US35: registra lo recibido (acumulado) de una línea; se puede corregir mientras la recepción no está
     * confirmada, sin bajar de lo ya ingresado en parciales (409 {@code RECEIVED_BELOW_STOCKED}).
     */
    @Transactional
    public ReceptionView recordLine(UUID receptionId, UUID lineId, BigDecimal receivedQty) {
        Reception reception = workable(receptionId);
        reception.recordReceived(lineId, receivedQty);
        return ReceptionView.of(receptions.save(reception));
    }

    /**
     * US38: recepción parcial. Ingresa lo recibido hasta ahora que aún no se había ingresado: lo registra en el
     * pedido y publica {@code ReceptionPartiallyReceived} con la diferencia de cada línea. El despacho sigue en
     * tránsito.
     */
    @Transactional
    public ReceptionView receivePartial(UUID receptionId) {
        Reception reception = workable(receptionId);
        List<Reception.LineDelta> deltas = reception.receivePartial(clock.instant());
        Reception saved = receptions.save(reception);
        Order order = orders.findById(saved.orderId()).orElseThrow(() -> OrderingErrors.orderNotFound(saved.orderId()));
        order.registerReceived(receivedByOrderLine(deltas));
        orders.save(order);
        outbox.append(OrderingEvents.RECEPTION_PARTIALLY_RECEIVED, OrderingEvents.RECEPTION, saved.id(),
                OrderingEvents.ReceptionPartiallyReceived.of(saved, deltas));
        return ReceptionView.of(saved);
    }

    /**
     * US37, QAS10: conformidad. Exige lo recibido de todas las líneas y, en una sola transacción, registra en el
     * pedido lo que faltaba ingresar, marca la recepción Confirmada y el despacho Recibido, y publica
     * {@code ReceptionConfirmed}. Si con esto el pedido está Atendido y todos sus despachos vigentes fueron
     * recibidos, el pedido queda Cerrado y se publica {@code OrderClosed}. Una segunda conformidad responde 409
     * {@code RECEPTION_ALREADY_CONFIRMED}.
     */
    @Transactional
    public ReceptionView confirm(UUID receptionId) {
        Reception reception = workable(receptionId);
        Instant now = clock.instant();
        List<Reception.LineDelta> deltas = reception.confirm(access.requester().userId(), now);
        Reception saved = receptions.save(reception);

        Order order = orders.findById(saved.orderId()).orElseThrow(() -> OrderingErrors.orderNotFound(saved.orderId()));
        Map<UUID, BigDecimal> received = receivedByOrderLine(deltas);
        if (!received.isEmpty()) {
            order.registerReceived(received);
        }
        Dispatch dispatch = dispatches.findById(saved.dispatchId())
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(saved.dispatchId()));
        dispatch.markReceived(now);
        dispatches.save(dispatch);
        boolean closing = order.status() == OrderStatus.FULFILLED && dispatches.findByOrderId(order.id()).stream()
                .filter(other -> other.status() != DispatchStatus.CANCELLED)
                .allMatch(other -> other.id().equals(dispatch.id()) || other.status() == DispatchStatus.RECEIVED);
        if (closing) {
            order.close();
        }
        if (!received.isEmpty() || closing) {
            orders.save(order);
        }

        outbox.append(OrderingEvents.RECEPTION_CONFIRMED, OrderingEvents.RECEPTION, saved.id(),
                confirmedEvent(saved, order, deltas));
        if (closing) {
            outbox.append(OrderingEvents.ORDER_CLOSED, OrderingEvents.ORDER, order.id(),
                    OrderingEvents.OrderFinished.of(order, OrderingEvents.RECEPTION_COMPLETE));
        }
        return ReceptionView.of(saved);
    }

    /**
     * US29: pesaje de llegada, una vez por recepción y mientras no esté confirmada. Se compara con el neto de salida
     * del despacho y con la menor tolerancia de sus materiales. Publica {@code ArrivalWeighed}.
     */
    @Transactional
    public ReceptionView.ArrivalWeighingView recordArrivalWeighing(UUID receptionId, BigDecimal grossKg,
            BigDecimal tareKg, String ticketPhotoUrl) {
        Reception reception = workable(receptionId);
        Dispatch dispatch = dispatches.findById(reception.dispatchId())
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(reception.dispatchId()));
        BigDecimal departureNet = dispatch.departureWeighing().map(DepartureWeighing::netKg).orElse(null);
        BigDecimal tolerance = directory.findMaterials(dispatch.lines().stream().map(DispatchLine::materialId).toList())
                .values().stream().map(Material::tolerancePercent).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        String evidenceUrl = evidence.registerTicketPhoto(receptionId, ticketPhotoUrl);
        ArrivalWeighing weighing = ArrivalWeighing.of(grossKg, tareKg, departureNet, tolerance, evidenceUrl,
                clock.instant(), access.requester().userId());
        reception.recordArrivalWeighing(weighing);
        Reception saved;
        try {
            saved = receptions.save(reception);
        } catch (DataIntegrityViolationException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uk_arrival_weighings_reception")) {
                throw new ConflictException(Reception.ARRIVAL_WEIGHING_ALREADY_RECORDED,
                        "La recepción ya tiene su pesaje de llegada");
            }
            throw ex;
        }
        outbox.append(OrderingEvents.ARRIVAL_WEIGHED, OrderingEvents.RECEPTION, saved.id(),
                OrderingEvents.ArrivalWeighed.of(saved));
        return ReceptionView.ArrivalWeighingView.of(weighing);
    }

    private OrderingEvents.ReceptionConfirmed confirmedEvent(Reception reception, Order order,
            List<Reception.LineDelta> deltas) {
        Map<UUID, Material> materials = directory.findMaterials(
                deltas.stream().map(delta -> delta.line().materialId()).toList());
        List<OrderingEvents.ConfirmedLinePayload> lines = deltas.stream().map(delta -> {
            ReceptionLine line = delta.line();
            Material material = materials.get(line.materialId());
            OrderLine orderLine = order.lineById(line.orderLineId());
            BigDecimal tolerance = material == null ? BigDecimal.ZERO : material.tolerancePercent();
            return new OrderingEvents.ConfirmedLinePayload(line.id(), line.dispatchLineId(), line.orderLineId(),
                    line.materialId(), material == null ? orderLine.sku() : material.sku(),
                    material == null ? orderLine.sku() : material.name(),
                    material == null ? orderLine.unit() : material.unit(), orderLine.requested(), line.dispatchedQty(),
                    line.receivedQty(), delta.delta(), line.shrinkagePercent(), tolerance,
                    Shrinkage.withinTolerance(line.shrinkagePercent(), tolerance));
        }).toList();
        return new OrderingEvents.ReceptionConfirmed(reception.id(), reception.dispatchId(), reception.orderId(),
                reception.manifestCode(), reception.worksiteId(), reception.warehouseId(), reception.confirmedBy(),
                reception.confirmedAt(), lines);
    }

    private static Map<UUID, BigDecimal> receivedByOrderLine(List<Reception.LineDelta> deltas) {
        Map<UUID, BigDecimal> received = new LinkedHashMap<>();
        deltas.stream().filter(delta -> delta.delta().signum() > 0)
                .forEach(delta -> received.merge(delta.line().orderLineId(), delta.delta(), BigDecimal::add));
        return received;
    }

    private Reception workable(UUID receptionId) {
        return receptions.findById(receptionId).filter(reception -> access.canWorkWorksite(reception.worksiteId()))
                .orElseThrow(() -> OrderingErrors.receptionNotFound(receptionId));
    }

    private static ConflictException alreadyExists(UUID existing) {
        return new ConflictException(RECEPTION_ALREADY_EXISTS, "El despacho ya tiene una recepción",
                existing == null ? List.of() : List.of(new ErrorDetail("receptionId", existing.toString())));
    }
}
