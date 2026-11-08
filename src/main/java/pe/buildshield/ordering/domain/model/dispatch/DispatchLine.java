package pe.buildshield.ordering.domain.model.dispatch;

import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Material de una línea de pedido que sale en el despacho, con su cantidad (mayor que cero, hasta 3 decimales). En un
 * despacho de retorno no hay línea de pedido.
 */
public record DispatchLine(UUID id, UUID orderLineId, UUID materialId, BigDecimal quantity) {

    public static final int SCALE = 3;

    public DispatchLine {
        Objects.requireNonNull(materialId, "materialId");
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > SCALE) {
            throw new ValidationException("INVALID_QUANTITY", "La cantidad debe ser mayor que cero, con hasta 3 decimales",
                    List.of(new ErrorDetail("quantity", "mayor que cero, máximo 3 decimales")));
        }
        quantity = quantity.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Línea de un despacho de retorno, sin línea de pedido. */
    public static DispatchLine ofReturn(UUID materialId, BigDecimal quantity) {
        return new DispatchLine(null, null, materialId, quantity);
    }

    /** Línea nueva; el identificador lo asigna la persistencia. */
    public static DispatchLine of(UUID orderLineId, UUID materialId, BigDecimal quantity) {
        return new DispatchLine(null, Objects.requireNonNull(orderLineId, "orderLineId"), materialId, quantity);
    }
}
