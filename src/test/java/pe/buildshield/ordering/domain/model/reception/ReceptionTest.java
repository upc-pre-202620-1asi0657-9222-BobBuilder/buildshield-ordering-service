package pe.buildshield.ordering.domain.model.reception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ResourceNotFoundException;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceptionTest {

    private static final Instant NOW = Instant.parse("2026-11-03T16:30:00Z");
    private static final String CODE = "MAN-20261103-7KQ2M9XA";
    private static final UUID JORGE = UUID.randomUUID();
    private static final UUID CEMENT_LINE = UUID.randomUUID();
    private static final UUID STEEL_LINE = UUID.randomUUID();

    private static Reception inProgress() {
        return Reception.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CODE, List.of(
                        ReceptionLine.restore(CEMENT_LINE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                new BigDecimal("50"), null, null, null),
                        ReceptionLine.restore(STEEL_LINE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                new BigDecimal("120"), null, BigDecimal.ZERO, null)),
                ReceptionStatus.IN_PROGRESS, 0, null, null, null, null, 0L);
    }

    @Test
    void starts_in_progress_with_one_line_per_dispatch_line() {
        Reception reception = Reception.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CODE, List.of(ReceptionLine.expect(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN)));

        assertThat(reception.id()).isNull();
        assertThat(reception.status()).isEqualTo(ReceptionStatus.IN_PROGRESS);
        assertThat(reception.manifestCode()).isEqualTo(CODE);
        assertThat(reception.partialCount()).isZero();
        assertThat(reception.lastPartialAt()).isNull();
        assertThat(reception.arrivalWeighing()).isEmpty();
        assertThat(reception.lines()).singleElement().satisfies(line -> {
            assertThat(line.isRecorded()).isFalse();
            assertThat(line.difference()).isNull();
            assertThat(line.dispatchedQty()).isEqualByComparingTo("10");
            assertThat(line.stockedQty()).isZero();
        });
        assertThatThrownBy(() -> Reception.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CODE, List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Reception.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), CODE, inProgress().lines(), ReceptionStatus.IN_PROGRESS, -1, null,
                null, null, null, 0L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void records_received_progressively_and_computes_the_shrinkage() {
        Reception reception = inProgress();

        reception.recordReceived(CEMENT_LINE, new BigDecimal("49"));
        assertThat(reception.line(CEMENT_LINE).shrinkagePercent()).isEqualByComparingTo("2.00");
        assertThat(reception.line(CEMENT_LINE).difference()).isEqualByComparingTo("1");

        reception.recordReceived(CEMENT_LINE, new BigDecimal("50"));
        assertThat(reception.line(CEMENT_LINE).shrinkagePercent()).isEqualByComparingTo("0");
        reception.recordReceived(STEEL_LINE, new BigDecimal("110"));
        assertThat(reception.line(STEEL_LINE).shrinkagePercent()).isEqualByComparingTo("8.33");
    }

    @ParameterizedTest
    @CsvSource({"-1, INVALID_QUANTITY", "0.0001, INVALID_QUANTITY", "50.001, RECEIVED_EXCEEDS_DISPATCHED"})
    void received_is_zero_or_more_and_never_exceeds_dispatched(String received, String code) {
        assertThatThrownBy(() -> inProgress().recordReceived(CEMENT_LINE, new BigDecimal(received)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", code);
        assertThatThrownBy(() -> inProgress().recordReceived(CEMENT_LINE, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void unknown_line_is_404() {
        assertThatThrownBy(() -> inProgress().recordReceived(UUID.randomUUID(), BigDecimal.ONE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_LINE_NOT_FOUND);
    }

    @Test
    void confirmation_requires_every_line_and_happens_once() {
        Reception reception = inProgress();
        reception.recordReceived(CEMENT_LINE, new BigDecimal("49"));
        assertThatThrownBy(() -> reception.confirm(JORGE, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_INCOMPLETE);

        reception.recordReceived(STEEL_LINE, BigDecimal.ZERO);
        List<Reception.LineDelta> deltas = reception.confirm(JORGE, NOW);

        assertThat(deltas).extracting(Reception.LineDelta::delta).containsExactly(new BigDecimal("49.000"),
                new BigDecimal("0.000"));
        assertThat(reception.status()).isEqualTo(ReceptionStatus.CONFIRMED);
        assertThat(reception.confirmedBy()).isEqualTo(JORGE);
        assertThat(reception.confirmedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> reception.confirm(JORGE, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_ALREADY_CONFIRMED);
        assertThatThrownBy(() -> reception.recordReceived(CEMENT_LINE, BigDecimal.ONE))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> reception.receivePartial(NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_ALREADY_CONFIRMED);
        assertThat(ReceptionStatus.CONFIRMED.label()).isEqualTo("Confirmada");
    }

    @Test
    void partial_receptions_stock_only_what_is_new_and_confirmation_adds_the_rest() {
        Reception reception = inProgress();
        reception.recordReceived(CEMENT_LINE, new BigDecimal("20"));

        List<Reception.LineDelta> first = reception.receivePartial(NOW);

        assertThat(first).singleElement().satisfies(delta -> {
            assertThat(delta.line().id()).isEqualTo(CEMENT_LINE);
            assertThat(delta.delta()).isEqualByComparingTo("20");
        });
        assertThat(reception.status()).isEqualTo(ReceptionStatus.PARTIALLY_RECEIVED);
        assertThat(reception.partialCount()).isEqualTo(1);
        assertThat(reception.lastPartialAt()).isEqualTo(NOW);
        assertThat(reception.line(CEMENT_LINE).stockedQty()).isEqualByComparingTo("20");
        assertThat(ReceptionStatus.PARTIALLY_RECEIVED.label()).isEqualTo("RecibidaParcialmente");

        assertThatThrownBy(() -> reception.receivePartial(NOW)).as("nada nuevo")
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", Reception.NOTHING_TO_RECEIVE);
        assertThatThrownBy(() -> reception.recordReceived(CEMENT_LINE, new BigDecimal("19")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ReceptionLine.RECEIVED_BELOW_STOCKED);

        reception.recordReceived(CEMENT_LINE, new BigDecimal("35"));
        reception.recordReceived(STEEL_LINE, new BigDecimal("100"));
        List<Reception.LineDelta> second = reception.receivePartial(NOW.plusSeconds(60));
        assertThat(second).extracting(Reception.LineDelta::delta).containsExactly(new BigDecimal("15.000"),
                new BigDecimal("100.000"));
        assertThat(reception.partialCount()).isEqualTo(2);

        reception.recordReceived(CEMENT_LINE, new BigDecimal("49"));
        List<Reception.LineDelta> confirmation = reception.confirm(JORGE, NOW.plusSeconds(120));
        assertThat(confirmation).extracting(Reception.LineDelta::delta).containsExactly(new BigDecimal("14.000"),
                new BigDecimal("0.000"));
        assertThat(reception.line(CEMENT_LINE).stockedQty()).isEqualByComparingTo("49");
    }

    @Test
    void nothing_to_receive_without_recorded_lines() {
        assertThatThrownBy(() -> inProgress().receivePartial(NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.NOTHING_TO_RECEIVE);
    }

    @Test
    void arrival_weighing_is_recorded_once_while_not_confirmed() {
        Reception reception = inProgress();
        ArrivalWeighing weighing = ArrivalWeighing.of(new BigDecimal("2530"), new BigDecimal("1050.5"),
                new BigDecimal("1500"), new BigDecimal("2.5"), null, NOW, JORGE);

        reception.recordArrivalWeighing(weighing);

        assertThat(reception.arrivalWeighing()).contains(weighing);
        assertThatThrownBy(() -> reception.recordArrivalWeighing(weighing)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.ARRIVAL_WEIGHING_ALREADY_RECORDED);

        Reception confirmed = inProgress();
        confirmed.recordReceived(CEMENT_LINE, BigDecimal.ONE);
        confirmed.recordReceived(STEEL_LINE, BigDecimal.ONE);
        confirmed.confirm(JORGE, NOW);
        assertThatThrownBy(() -> confirmed.recordArrivalWeighing(weighing)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", Reception.RECEPTION_ALREADY_CONFIRMED);
    }

    @Test
    void shrinkage_is_compared_with_the_tolerance() {
        assertThat(Shrinkage.percent(new BigDecimal("120"), new BigDecimal("110"))).isEqualByComparingTo("8.33");
        assertThat(Shrinkage.withinTolerance(new BigDecimal("2.50"), new BigDecimal("2.5"))).isTrue();
        assertThat(Shrinkage.withinTolerance(new BigDecimal("2.51"), new BigDecimal("2.5"))).isFalse();
        assertThatThrownBy(() -> Shrinkage.percent(BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReceptionLine.expect(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReceptionLine.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), BigDecimal.TEN, null, new BigDecimal("-1"), null)).isInstanceOf(IllegalStateException.class);
    }
}
