package pe.buildshield.ordering.application;

import pe.buildshield.ordering.application.OrganizationDirectory.SiteNames;
import pe.buildshield.ordering.domain.model.dispatch.Carrier;
import pe.buildshield.ordering.domain.model.dispatch.DepartureWeighing;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncident;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.reception.ArrivalWeighing;
import pe.buildshield.ordering.domain.model.reception.Reception;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Eventos de integración que publica ordering en el tópico {@code buildshield.ordering} (payload del sobre). Los
 * nombres de los campos son el contrato con inventory, discrepancy, notification y audit
 * ({@code wiki/informe-tf/notas-etapa-b1.md} §2): no se renombran. Todos llevan los nombres de la obra y del almacén, y
 * van en null si el lugar todavía no llegó a la copia local.
 */
public final class OrderingEvents {

    public static final String ORDER_REGISTERED = "OrderRegistered";
    public static final String ORDER_APPROVED = "OrderApproved";
    public static final String ORDER_REJECTED = "OrderRejected";
    public static final String ORDER_CLOSED = "OrderClosed";
    public static final String DISPATCH_PREPARED = "DispatchPrepared";
    public static final String DISPATCH_CARRIER_ASSIGNED = "DispatchCarrierAssigned";
    public static final String DISPATCH_DEPARTURE_WEIGHED = "DispatchDepartureWeighed";
    public static final String DISPATCH_DEPARTED = "DispatchDeparted";
    public static final String DISPATCH_CANCELLED = "DispatchCancelled";
    public static final String TRANSIT_INCIDENT_REPORTED = "TransitIncidentReported";
    public static final String RETURN_DISPATCH_CREATED = "ReturnDispatchCreated";
    public static final String RETURN_DISPATCH_CLOSED = "ReturnDispatchClosed";
    public static final String RECEPTION_OPENED = "ReceptionOpened";
    public static final String ARRIVAL_WEIGHED = "ArrivalWeighed";
    public static final String RECEPTION_PARTIALLY_RECEIVED = "ReceptionPartiallyReceived";
    public static final String RECEPTION_CONFIRMED = "ReceptionConfirmed";

    public static final String ORDER = "ORDER";
    public static final String DISPATCH = "DISPATCH";
    public static final String RECEPTION = "RECEPTION";

    /** Motivo de {@code OrderClosed} cuando se completa la recepción de todos los despachos. */
    public static final String RECEPTION_COMPLETE = "Recepción completa";

    private OrderingEvents() {
    }

    // ---------- Pedido ----------

    public record OrderLinePayload(UUID orderLineId, UUID materialId, String sku, String unit, BigDecimal quantity) {
    }

    public record OrderRegistered(UUID orderId, UUID worksiteId, String worksiteName, UUID warehouseId,
            String warehouseName, UUID requestedBy,
            LocalDate requiredDate, String notes, List<OrderLinePayload> lines) {

        public static OrderRegistered of(Order order, SiteNames names) {
            return new OrderRegistered(order.id(), order.worksiteId(), names.worksiteName(), order.warehouseId(),
                names.warehouseName(), order.requestedBy(),
                    order.requiredDate(), order.notes(), orderLines(order));
        }
    }

    public record OrderApproved(UUID orderId, UUID warehouseId, String warehouseName, UUID worksiteId,
            String worksiteName, UUID approvedBy,
            List<OrderLinePayload> lines) {

        public static OrderApproved of(Order order, SiteNames names) {
            return new OrderApproved(order.id(), order.warehouseId(), names.warehouseName(), order.worksiteId(),
                names.worksiteName(), order.decidedBy(),
                    orderLines(order));
        }
    }

    /** {@code OrderRejected} (rechazo del encargado: el pedido queda Cancelado) y {@code OrderClosed}. */
    public record OrderFinished(UUID orderId, UUID warehouseId, String warehouseName, UUID worksiteId,
            String worksiteName, String reason) {

        public static OrderFinished of(Order order, String reason, SiteNames names) {
            return new OrderFinished(order.id(), order.warehouseId(), names.warehouseName(), order.worksiteId(),
                names.worksiteName(), reason);
        }
    }

    private static List<OrderLinePayload> orderLines(Order order) {
        return order.lines().stream().map(line -> new OrderLinePayload(line.id(), line.materialId(), line.sku(),
                line.unit(), line.requested())).toList();
    }

    // ---------- Despacho ----------

    public record DispatchLinePayload(UUID dispatchLineId, UUID orderLineId, UUID materialId, BigDecimal quantity) {
    }

    public record DispatchPrepared(UUID dispatchId, UUID orderId, String manifestCode, UUID warehouseId,
            String warehouseName,
            UUID worksiteId, String worksiteName, String type, List<DispatchLinePayload> lines) {

        public static DispatchPrepared of(Dispatch dispatch, SiteNames names) {
            return new DispatchPrepared(dispatch.id(), dispatch.orderId(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    dispatch.type().name(), dispatchLines(dispatch));
        }
    }

    public record DispatchCarrierAssigned(UUID dispatchId, UUID orderId, String manifestCode, UUID warehouseId,
            String warehouseName,
            UUID worksiteId, String worksiteName, String carrierName, String carrierDocument, String plate) {

        public static DispatchCarrierAssigned of(Dispatch dispatch, SiteNames names) {
            Carrier carrier = dispatch.carrier().orElseThrow();
            return new DispatchCarrierAssigned(dispatch.id(), dispatch.orderId(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    carrier.name(), carrier.document(), carrier.plate());
        }
    }

    public record DispatchDepartureWeighed(UUID dispatchId, UUID orderId, String manifestCode, UUID warehouseId,
            String warehouseName,
            UUID worksiteId, String worksiteName, BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg,
            String ticketPhotoUrl) {

        public static DispatchDepartureWeighed of(Dispatch dispatch, SiteNames names) {
            DepartureWeighing weighing = dispatch.departureWeighing().orElseThrow();
            return new DispatchDepartureWeighed(dispatch.id(), dispatch.orderId(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    weighing.grossKg(), weighing.tareKg(),
                    weighing.netKg(), weighing.ticketPhotoUrl());
        }
    }

    public record DispatchDeparted(UUID dispatchId, UUID orderId, String manifestCode, UUID warehouseId,
            String warehouseName,
            UUID worksiteId, String worksiteName, Instant departedAt, boolean returnDispatch,
            List<DispatchLinePayload> lines) {

        public static DispatchDeparted of(Dispatch dispatch, SiteNames names) {
            return new DispatchDeparted(dispatch.id(), dispatch.orderId(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    dispatch.departedAt(),
                    dispatch.type() == DispatchType.RETURN, dispatchLines(dispatch));
        }
    }

    public record DispatchCancelled(UUID dispatchId, UUID orderId, String manifestCode, UUID warehouseId,
            String warehouseName,
            UUID worksiteId, String worksiteName, String reason, Instant cancelledAt) {

        public static DispatchCancelled of(Dispatch dispatch, SiteNames names) {
            Dispatch.Cancellation cancellation = dispatch.cancellation().orElseThrow();
            return new DispatchCancelled(dispatch.id(), dispatch.orderId(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    cancellation.reason(), cancellation.at());
        }
    }

    public record TransitIncidentReported(UUID dispatchId, UUID incidentId, UUID orderId, String manifestCode,
            UUID warehouseId, String warehouseName, UUID worksiteId, String worksiteName, String type,
            String typeLabel, String observation, String photoUrl,
            Instant reportedAt) {

        public static TransitIncidentReported of(Dispatch dispatch, TransitIncident incident, SiteNames names) {
            return new TransitIncidentReported(dispatch.id(), incident.id(), dispatch.orderId(),
                    dispatch.manifestCode().value(), dispatch.warehouseId(), names.warehouseName(),
                    dispatch.worksiteId(), names.worksiteName(),
                    incident.type().name(), incident.type().label(), incident.observation(), incident.photoUrl(),
                    incident.reportedAt());
        }
    }

    /**
     * Despacho de retorno creado para una devolución de inventario: en tránsito, sin pedido y con
     * {@code orderLineId} nulo en cada línea. Inventario guarda {@code dispatchId} en la devolución; el stock ya lo movió
     * inventario y este evento no lo mueve.
     */
    public record ReturnDispatchCreated(UUID returnId, UUID dispatchId, String manifestCode, UUID warehouseId,
            String warehouseName, UUID worksiteId, String worksiteName, String status, Instant departedAt,
            List<DispatchLinePayload> lines) {

        public static ReturnDispatchCreated of(UUID returnId, Dispatch dispatch, SiteNames names) {
            return new ReturnDispatchCreated(returnId, dispatch.id(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    dispatch.status().name(), dispatch.departedAt(), dispatchLines(dispatch));
        }
    }

    /** Inventario recibió la devolución y el despacho de retorno quedó Recibido. No mueve stock. */
    public record ReturnDispatchClosed(UUID returnId, UUID dispatchId, String manifestCode, UUID warehouseId,
            String warehouseName, UUID worksiteId, String worksiteName, String status, Instant receivedAt) {

        public static ReturnDispatchClosed of(UUID returnId, Dispatch dispatch, Instant receivedAt, SiteNames names) {
            return new ReturnDispatchClosed(returnId, dispatch.id(), dispatch.manifestCode().value(),
                    dispatch.warehouseId(), names.warehouseName(), dispatch.worksiteId(), names.worksiteName(),
                    dispatch.status().name(), receivedAt);
        }
    }

    private static List<DispatchLinePayload> dispatchLines(Dispatch dispatch) {
        return dispatch.lines().stream().map(line -> new DispatchLinePayload(line.id(), line.orderLineId(),
                line.materialId(), line.quantity())).toList();
    }

    // ---------- Recepción ----------

    public record ReceptionOpened(UUID receptionId, UUID dispatchId, UUID orderId, String manifestCode,
            UUID warehouseId, String warehouseName, UUID worksiteId, String worksiteName) {

        public static ReceptionOpened of(Reception reception, SiteNames names) {
            return new ReceptionOpened(reception.id(), reception.dispatchId(), reception.orderId(),
                    reception.manifestCode(), reception.warehouseId(), names.warehouseName(), reception.worksiteId(),
                    names.worksiteName());
        }
    }

    /** Los cuatro campos de comparación van en null si el despacho no tuvo pesaje de salida. */
    public record ArrivalWeighed(UUID receptionId, UUID dispatchId, UUID orderId, String manifestCode,
            UUID warehouseId, String warehouseName, UUID worksiteId, String worksiteName, BigDecimal grossKg,
            BigDecimal tareKg, BigDecimal netKg,
            BigDecimal departureNetKg, BigDecimal differenceKg, BigDecimal differencePercent,
            BigDecimal tolerancePercent, Boolean withinTolerance) {

        public static ArrivalWeighed of(Reception reception, SiteNames names) {
            ArrivalWeighing weighing = reception.arrivalWeighing().orElseThrow();
            return new ArrivalWeighed(reception.id(), reception.dispatchId(), reception.orderId(),
                    reception.manifestCode(), reception.warehouseId(), names.warehouseName(), reception.worksiteId(),
                    names.worksiteName(), weighing.grossKg(),
                    weighing.tareKg(), weighing.netKg(), weighing.departureNetKg(), weighing.differenceKg(),
                    weighing.differencePercent(), weighing.tolerancePercent(), weighing.withinTolerance());
        }
    }

    /** @param receivedQuantity lo recibido acumulado de la línea */
    public record PartialLinePayload(UUID receptionLineId, UUID dispatchLineId, UUID orderLineId, UUID materialId,
            BigDecimal receivedQuantity, BigDecimal deltaQuantity) {
    }

    /** Solo las líneas con diferencia mayor que cero. */
    public record ReceptionPartiallyReceived(UUID receptionId, UUID dispatchId, UUID orderId, String manifestCode,
            UUID warehouseId, String warehouseName, UUID worksiteId, String worksiteName, boolean returnDispatch,
            int partialCount,
            List<PartialLinePayload> lines) {

        public static ReceptionPartiallyReceived of(Reception reception, List<Reception.LineDelta> deltas,
                SiteNames names) {
            return new ReceptionPartiallyReceived(reception.id(), reception.dispatchId(), reception.orderId(),
                    reception.manifestCode(), reception.warehouseId(), names.warehouseName(), reception.worksiteId(),
                    names.worksiteName(),
                    reception.orderId() == null, reception.partialCount(),
                    deltas.stream().map(delta -> new PartialLinePayload(delta.line().id(),
                    delta.line().dispatchLineId(),
                            delta.line().orderLineId(),
                            delta.line().materialId(), delta.line().receivedQty(), delta.delta())).toList());
        }
    }

    /**
     * Línea de la conformidad. {@code requested} es lo solicitado en la línea del pedido; {@code dispatched}, lo
     * despachado en este despacho; {@code deltaQuantity}, lo que se ingresa ahora (recibido menos lo ya ingresado en
     * parciales, puede ser 0). {@code withinTolerance} = {@code shrinkagePercent <= tolerancePercent}.
     */
    public record ConfirmedLinePayload(UUID receptionLineId, UUID dispatchLineId, UUID orderLineId, UUID materialId,
            String sku, String materialName, String unit, BigDecimal requested, BigDecimal dispatched,
            BigDecimal received, BigDecimal deltaQuantity, BigDecimal shrinkagePercent, BigDecimal tolerancePercent,
            boolean withinTolerance) {
    }

    public record ReceptionConfirmed(UUID receptionId, UUID dispatchId, UUID orderId, String manifestCode,
            UUID worksiteId, String worksiteName, UUID warehouseId, String warehouseName, boolean returnDispatch,
            UUID confirmedBy,
            Instant confirmedAt,
            List<ConfirmedLinePayload> lines) {
    }
}
