package pe.buildshield.ordering.domain.model.reception;

import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pesaje de llegada a la obra (US29): bruto y tara en kg con hasta 3 decimales; el neto (bruto - tara) debe ser
 * mayor que cero. Se compara con el neto del pesaje de salida del despacho:
 * {@code diferencia = neto de salida - neto de llegada} y {@code diferencia % = diferencia / neto de salida × 100}
 * (2 decimales, HALF_UP). Está dentro de la tolerancia si {@code |diferencia %| <= tolerancia}, donde la
 * tolerancia es la menor de los materiales del despacho. Sin pesaje de salida la comparación es nula.
 */
public record ArrivalWeighing(BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg, BigDecimal departureNetKg,
        BigDecimal tolerancePercent, String ticketPhotoUrl, Instant weighedAt, UUID weighedBy) {

    public static final String INVALID_WEIGHING = "INVALID_WEIGHING";
    public static final int SCALE = 3;
    public static final int PERCENT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public ArrivalWeighing {
        Objects.requireNonNull(weighedAt, "weighedAt");
        Objects.requireNonNull(weighedBy, "weighedBy");
        grossKg = weight(grossKg, "grossKg", false);
        tareKg = weight(tareKg, "tareKg", true);
        if (grossKg.compareTo(tareKg) <= 0) {
            throw new ValidationException(INVALID_WEIGHING, "La tara debe ser menor que el peso bruto",
                    List.of(new ErrorDetail("tareKg", "menor que grossKg")));
        }
        if (netKg == null || netKg.compareTo(grossKg.subtract(tareKg)) != 0) {
            throw new IllegalStateException("El neto es bruto menos tara");
        }
        netKg = netKg.setScale(SCALE, RoundingMode.UNNECESSARY);
        departureNetKg = departureNetKg == null ? null : departureNetKg.setScale(SCALE, RoundingMode.HALF_UP);
        tolerancePercent = (tolerancePercent == null ? BigDecimal.ZERO : tolerancePercent)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        ticketPhotoUrl = ticketPhotoUrl == null || ticketPhotoUrl.isBlank() ? null : ticketPhotoUrl.trim();
    }

    /** Calcula el neto a partir del bruto y la tara. */
    public static ArrivalWeighing of(BigDecimal grossKg, BigDecimal tareKg, BigDecimal departureNetKg,
            BigDecimal tolerancePercent, String ticketPhotoUrl, Instant weighedAt, UUID weighedBy) {
        BigDecimal gross = weight(grossKg, "grossKg", false);
        BigDecimal tare = weight(tareKg, "tareKg", true);
        return new ArrivalWeighing(gross, tare, gross.subtract(tare), departureNetKg, tolerancePercent, ticketPhotoUrl,
                weighedAt, weighedBy);
    }

    /** Neto de salida - neto de llegada; {@code null} sin pesaje de salida. */
    public BigDecimal differenceKg() {
        return departureNetKg == null ? null : departureNetKg.subtract(netKg);
    }

    /** Diferencia respecto del neto de salida, en %, con 2 decimales; {@code null} sin pesaje de salida. */
    public BigDecimal differencePercent() {
        if (departureNetKg == null || departureNetKg.signum() == 0) {
            return null;
        }
        return differenceKg().multiply(HUNDRED).divide(departureNetKg, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code |diferencia %| <= tolerancia}; {@code null} sin pesaje de salida. */
    public Boolean withinTolerance() {
        BigDecimal percent = differencePercent();
        return percent == null ? null : percent.abs().compareTo(tolerancePercent) <= 0;
    }

    private static BigDecimal weight(BigDecimal value, String field, boolean zeroAllowed) {
        if (value == null || value.signum() < 0 || (!zeroAllowed && value.signum() == 0)
                || value.stripTrailingZeros().scale() > SCALE) {
            throw new ValidationException(INVALID_WEIGHING, "El peso debe ser " + (zeroAllowed ? "cero o más" : "mayor que cero")
                    + ", en kg con hasta 3 decimales", List.of(new ErrorDetail(field, "kg, máximo 3 decimales")));
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
