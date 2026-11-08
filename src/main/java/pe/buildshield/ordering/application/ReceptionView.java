package pe.buildshield.ordering.application;

import pe.buildshield.ordering.domain.model.reception.ArrivalWeighing;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Recepción con lo recibido por línea, las recepciones parciales (US38) y el pesaje de llegada (US29). */
public record ReceptionView(UUID id, UUID dispatchId, UUID orderId, UUID warehouseId, UUID worksiteId,
        ReceptionStatus status, UUID confirmedBy, Instant confirmedAt, int partialCount, Instant lastPartialAt,
        ArrivalWeighingView arrivalWeighing, List<LineView> lines) {

    public static ReceptionView of(Reception reception) {
        return new ReceptionView(reception.id(), reception.dispatchId(), reception.orderId(), reception.warehouseId(),
                reception.worksiteId(), reception.status(), reception.confirmedBy(), reception.confirmedAt(),
                reception.partialCount(), reception.lastPartialAt(),
                reception.arrivalWeighing().map(ArrivalWeighingView::of).orElse(null),
                reception.lines().stream().map(LineView::of).toList());
    }

    /** @param stockedQty lo ya ingresado al stock de la obra en recepciones parciales */
    public record LineView(UUID id, UUID dispatchLineId, UUID orderLineId, UUID materialId, BigDecimal dispatchedQty,
            BigDecimal receivedQty, BigDecimal stockedQty, BigDecimal shrinkagePercent) {

        static LineView of(ReceptionLine line) {
            return new LineView(line.id(), line.dispatchLineId(), line.orderLineId(), line.materialId(),
                    line.dispatchedQty(), line.receivedQty(), line.stockedQty(), line.shrinkagePercent());
        }
    }

    /** Pesaje de llegada comparado con el de salida; la comparación es nula si no hubo pesaje de salida. */
    public record ArrivalWeighingView(BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg,
            BigDecimal departureNetKg, BigDecimal differenceKg, BigDecimal differencePercent,
            BigDecimal tolerancePercent, Boolean withinTolerance) {

        public static ArrivalWeighingView of(ArrivalWeighing weighing) {
            return new ArrivalWeighingView(weighing.grossKg(), weighing.tareKg(), weighing.netKg(),
                    weighing.departureNetKg(), weighing.differenceKg(), weighing.differencePercent(),
                    weighing.tolerancePercent(), weighing.withinTolerance());
        }
    }
}
