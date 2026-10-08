package pe.buildshield.ordering.application;

import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cotejo de la recepción (US35, QAS01): {@code complete} si todas las líneas tienen lo recibido;
 * {@code withinTolerance} si además ninguna supera la tolerancia de su material.
 */
public record ComparisonView(UUID receptionId, UUID dispatchId, UUID orderId, ReceptionStatus status,
        boolean complete, boolean withinTolerance, List<Line> lines) {

    /**
     * @param difference      despachado - recibido (lo que faltó); {@code null} si aún no se registra lo recibido
     * @param withinTolerance merma ≤ tolerancia del material; {@code null} si aún no se registra lo recibido
     */
    public record Line(UUID lineId, UUID materialId, String sku, String materialName, String unit,
            BigDecimal requested, BigDecimal dispatched, BigDecimal received, BigDecimal difference,
            BigDecimal shrinkagePercent, BigDecimal tolerancePercent, Boolean withinTolerance) {
    }
}
