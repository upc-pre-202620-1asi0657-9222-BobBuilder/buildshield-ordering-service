package pe.buildshield.ordering.application;

import pe.buildshield.ordering.domain.model.dispatch.DepartureWeighing;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchLine;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Despacho con su estado, líneas, transportista, pesaje de salida, anulación (US25) y cantidad de incidencias en
 * ruta (US31, US32).
 */
public record DispatchView(UUID id, UUID orderId, UUID warehouseId, UUID worksiteId, DispatchType type,
        DispatchStatus status, String manifestCode, Instant preparedAt, Instant departedAt, Instant receivedAt,
        Instant cancelledAt, String cancellationReason, int incidentCount, CarrierView carrier,
        WeighingView departureWeighing, List<LineView> lines) {

    public static DispatchView of(Dispatch dispatch, int incidentCount) {
        Dispatch.Cancellation cancellation = dispatch.cancellation().orElse(null);
        return new DispatchView(dispatch.id(), dispatch.orderId(), dispatch.warehouseId(), dispatch.worksiteId(),
                dispatch.type(), dispatch.status(), dispatch.manifestCode().value(), dispatch.preparedAt(),
                dispatch.departedAt(), dispatch.receivedAt(), cancellation == null ? null : cancellation.at(),
                cancellation == null ? null : cancellation.reason(), incidentCount,
                dispatch.carrier().map(carrier -> new CarrierView(carrier.name(), carrier.document(), carrier.plate()))
                        .orElse(null),
                dispatch.departureWeighing().map(WeighingView::of).orElse(null),
                dispatch.lines().stream().map(LineView::of).toList());
    }

    public record CarrierView(String name, String document, String plate) {
    }

    public record WeighingView(BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg, String ticketPhotoUrl,
            Instant weighedAt, UUID weighedBy) {

        static WeighingView of(DepartureWeighing weighing) {
            return new WeighingView(weighing.grossKg(), weighing.tareKg(), weighing.netKg(), weighing.ticketPhotoUrl(),
                    weighing.weighedAt(), weighing.weighedBy());
        }
    }

    public record LineView(UUID id, UUID orderLineId, UUID materialId, BigDecimal quantity) {

        static LineView of(DispatchLine line) {
            return new LineView(line.id(), line.orderLineId(), line.materialId(), line.quantity());
        }
    }
}
