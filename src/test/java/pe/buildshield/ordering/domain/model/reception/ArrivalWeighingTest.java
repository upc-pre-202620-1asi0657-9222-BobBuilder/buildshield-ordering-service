package pe.buildshield.ordering.domain.model.reception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US29: pesaje de llegada comparado con el de salida. */
class ArrivalWeighingTest {

    private static final Instant NOW = Instant.parse("2026-11-03T16:00:00Z");
    private static final UUID JORGE = UUID.randomUUID();

    private static ArrivalWeighing weigh(String gross, String tare, String departureNet, String tolerance) {
        return ArrivalWeighing.of(new BigDecimal(gross), new BigDecimal(tare),
                departureNet == null ? null : new BigDecimal(departureNet), new BigDecimal(tolerance),
                " https://evidencias/llegada.jpg ", NOW, JORGE);
    }

    @Test
    void compares_with_the_departure_net() {
        ArrivalWeighing weighing = weigh("2530", "1050.5", "1500", "2.5");

        assertThat(weighing.netKg()).isEqualByComparingTo("1479.5");
        assertThat(weighing.departureNetKg()).isEqualByComparingTo("1500");
        assertThat(weighing.differenceKg()).isEqualByComparingTo("20.5");
        assertThat(weighing.differencePercent()).isEqualByComparingTo("1.37");
        assertThat(weighing.tolerancePercent()).isEqualByComparingTo("2.50");
        assertThat(weighing.withinTolerance()).isTrue();
        assertThat(weighing.ticketPhotoUrl()).isEqualTo("https://evidencias/llegada.jpg");
    }

    @ParameterizedTest
    @CsvSource({"2450, 1000, 1500, 2.5, 3.33, false", "2560, 1000, 1500, 5, -4.00, true",
            "2470, 1000, 1500, 2.0, 2.00, true",
            "2470.1, 1000, 1500, 2.0, 1.99, true", "2400, 1000, 1500, 1, 6.67, false"})
    void within_tolerance_uses_the_absolute_difference(String gross, String tare, String departure, String tolerance,
            String percent, boolean within) {
        ArrivalWeighing weighing = weigh(gross, tare, departure, tolerance);

        assertThat(weighing.differencePercent()).isEqualByComparingTo(percent);
        assertThat(weighing.withinTolerance()).isEqualTo(within);
    }

    @Test
    void without_departure_weighing_the_comparison_is_null() {
        ArrivalWeighing weighing = weigh("2530", "1050.5", null, "2.5");

        assertThat(weighing.departureNetKg()).isNull();
        assertThat(weighing.differenceKg()).isNull();
        assertThat(weighing.differencePercent()).isNull();
        assertThat(weighing.withinTolerance()).isNull();
        assertThat(ArrivalWeighing.of(BigDecimal.TEN, BigDecimal.ONE, null, null, " ", NOW, JORGE).tolerancePercent())
                .isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @CsvSource({"1000, 1000", "1000, 1500", "0, 0", "-1, 0", "100, -1", "100.0001, 1"})
    void gross_must_exceed_tare(String gross, String tare) {
        assertThatThrownBy(() -> weigh(gross, tare, "1500", "2"))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", ArrivalWeighing.INVALID_WEIGHING);
    }

    @Test
    void net_must_be_gross_minus_tare() {
        assertThatThrownBy(() -> new ArrivalWeighing(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, null, null, null,
                NOW, JORGE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ArrivalWeighing.of(null, BigDecimal.ONE, null, null, null, NOW, JORGE))
                .isInstanceOf(ValidationException.class);
    }
}
