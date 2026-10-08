package pe.buildshield.ordering.domain.model.reception;

import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Material recibido de una línea del despacho. Guarda lo despachado (copia de la línea del despacho), lo
 * recibido (acumulado, se registra de a poco) y lo ya ingresado al stock de la obra en recepciones parciales
 * ({@code stockedQty}); al registrar lo recibido calcula la merma.
 *
 * <p>Invariantes: lo recibido es cero o más, con hasta 3 decimales, no supera lo despachado y nunca baja de lo
 * ya ingresado en parciales.
 */
public class ReceptionLine {

    public static final int SCALE = 3;
    public static final String RECEIVED_EXCEEDS_DISPATCHED = "RECEIVED_EXCEEDS_DISPATCHED";
    public static final String RECEIVED_BELOW_STOCKED = "RECEIVED_BELOW_STOCKED";

    private final UUID id;
    private final UUID dispatchLineId;
    private final UUID orderLineId;
    private final UUID materialId;
    private final BigDecimal dispatchedQty;
    private BigDecimal receivedQty;
    private BigDecimal stockedQty;
    private BigDecimal shrinkagePercent;

    private ReceptionLine(UUID id, UUID dispatchLineId, UUID orderLineId, UUID materialId, BigDecimal dispatchedQty,
            BigDecimal receivedQty, BigDecimal stockedQty, BigDecimal shrinkagePercent) {
        this.id = id;
        this.dispatchLineId = Objects.requireNonNull(dispatchLineId, "dispatchLineId");
        this.orderLineId = Objects.requireNonNull(orderLineId, "orderLineId");
        this.materialId = Objects.requireNonNull(materialId, "materialId");
        if (dispatchedQty == null || dispatchedQty.signum() <= 0) {
            throw new IllegalStateException("Lo despachado debe ser mayor que cero");
        }
        this.dispatchedQty = dispatchedQty.setScale(SCALE, RoundingMode.HALF_UP);
        this.receivedQty = receivedQty == null ? null : receivedQty.setScale(SCALE, RoundingMode.HALF_UP);
        this.stockedQty = (stockedQty == null ? BigDecimal.ZERO : stockedQty).setScale(SCALE, RoundingMode.HALF_UP);
        if (this.stockedQty.signum() < 0) {
            throw new IllegalStateException("Lo ingresado no puede ser negativo");
        }
        this.shrinkagePercent = shrinkagePercent;
    }

    /** Línea por recibir, a partir de una línea del despacho. */
    public static ReceptionLine expect(UUID dispatchLineId, UUID orderLineId, UUID materialId, BigDecimal dispatchedQty) {
        return new ReceptionLine(null, dispatchLineId, orderLineId, materialId, dispatchedQty, null, BigDecimal.ZERO,
                null);
    }

    public static ReceptionLine restore(UUID id, UUID dispatchLineId, UUID orderLineId, UUID materialId,
            BigDecimal dispatchedQty, BigDecimal receivedQty, BigDecimal stockedQty, BigDecimal shrinkagePercent) {
        return new ReceptionLine(Objects.requireNonNull(id, "id"), dispatchLineId, orderLineId, materialId,
                dispatchedQty, receivedQty, stockedQty, shrinkagePercent);
    }

    /** Registra (o corrige) lo recibido acumulado y recalcula la merma. */
    void recordReceived(BigDecimal quantity) {
        if (quantity == null || quantity.signum() < 0 || quantity.stripTrailingZeros().scale() > SCALE) {
            throw new ValidationException("INVALID_QUANTITY", "Lo recibido debe ser cero o más, con hasta 3 decimales",
                    List.of(new ErrorDetail("receivedQty", "cero o más, máximo 3 decimales")));
        }
        BigDecimal received = quantity.setScale(SCALE, RoundingMode.UNNECESSARY);
        if (received.compareTo(dispatchedQty) > 0) {
            throw new ValidationException(RECEIVED_EXCEEDS_DISPATCHED, "Lo recibido (" + received.toPlainString()
                    + ") supera lo despachado (" + dispatchedQty.toPlainString() + ")",
                    List.of(new ErrorDetail("receivedQty", "máximo " + dispatchedQty.toPlainString())));
        }
        if (received.compareTo(stockedQty) < 0) {
            throw new ConflictException(RECEIVED_BELOW_STOCKED, "Lo recibido (" + received.toPlainString()
                    + ") no puede ser menor que lo ya ingresado en recepciones parciales (" + stockedQty.toPlainString()
                    + ")", List.of(new ErrorDetail("receivedQty", "mínimo " + stockedQty.toPlainString())));
        }
        receivedQty = received;
        shrinkagePercent = Shrinkage.percent(dispatchedQty, received);
    }

    /**
     * Pasa al stock lo recibido que todavía no se había ingresado.
     *
     * @return la diferencia ingresada ahora (cero si no había nada nuevo o si aún no se registra lo recibido)
     */
    BigDecimal stockReceived() {
        if (receivedQty == null) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal delta = receivedQty.subtract(stockedQty);
        stockedQty = receivedQty;
        return delta;
    }

    public boolean isRecorded() {
        return receivedQty != null;
    }

    /** Lo que faltó: despachado - recibido; {@code null} si todavía no se registró lo recibido. */
    public BigDecimal difference() {
        return receivedQty == null ? null : dispatchedQty.subtract(receivedQty);
    }

    /** {@code null} hasta que la línea se guarda. */
    public UUID id() {
        return id;
    }

    public UUID dispatchLineId() {
        return dispatchLineId;
    }

    public UUID orderLineId() {
        return orderLineId;
    }

    public UUID materialId() {
        return materialId;
    }

    public BigDecimal dispatchedQty() {
        return dispatchedQty;
    }

    public BigDecimal receivedQty() {
        return receivedQty;
    }

    public BigDecimal stockedQty() {
        return stockedQty;
    }

    public BigDecimal shrinkagePercent() {
        return shrinkagePercent;
    }
}
