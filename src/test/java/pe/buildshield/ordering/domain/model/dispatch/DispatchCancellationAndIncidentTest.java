package pe.buildshield.ordering.domain.model.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US25 anulación y US32 incidencias en ruta. */
class DispatchCancellationAndIncidentTest {

    private static final Instant NOW = Instant.parse("2026-11-03T13:00:00Z");
    private static final UUID ROSA = UUID.randomUUID();
    private static final UUID LINE_A = UUID.randomUUID();
    private static final UUID LINE_B = UUID.randomUUID();
    private static final ManifestCode CODE = new ManifestCode("MAN-20261103-7KQ2M9XA");

    private static Dispatch stored(DispatchStatus status) {
        return Dispatch.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DispatchType.PARTIAL, CODE, List.of(
                        new DispatchLine(UUID.randomUUID(), LINE_A, UUID.randomUUID(), new BigDecimal("20")),
                        new DispatchLine(UUID.randomUUID(), LINE_B, UUID.randomUUID(), new BigDecimal("5.5"))),
                NOW, status, null, null, null, null, null, 1L);
    }

    @Test
    void a_prepared_dispatch_is_cancelled_with_its_reason() {
        Dispatch dispatch = stored(DispatchStatus.PREPARED);

        dispatch.cancel(ROSA, "  El camión no llegó  ", NOW);

        assertThat(dispatch.status()).isEqualTo(DispatchStatus.CANCELLED);
        assertThat(dispatch.cancellation()).hasValueSatisfying(cancellation -> {
            assertThat(cancellation.reason()).isEqualTo("El camión no llegó");
            assertThat(cancellation.by()).isEqualTo(ROSA);
            assertThat(cancellation.at()).isEqualTo(NOW);
        });
        assertThat(dispatch.quantitiesByOrderLine()).containsEntry(LINE_A, new BigDecimal("20.000"))
                .containsEntry(LINE_B, new BigDecimal("5.500"));
        assertThatThrownBy(() -> dispatch.assignCarrier(new Carrier("Rímac", "20555666777", "ABC-123")))
                .isInstanceOf(InvalidDispatchTransitionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ab"})
    void the_reason_has_from_3_to_300_characters(String reason) {
        Dispatch dispatch = stored(DispatchStatus.PREPARED);
        assertThatThrownBy(() -> dispatch.cancel(ROSA, reason, NOW)).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", Dispatch.INVALID_CANCELLATION_REASON);
        assertThatThrownBy(() -> dispatch.cancel(ROSA, null, NOW)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> dispatch.cancel(ROSA, "x".repeat(301), NOW)).isInstanceOf(ValidationException.class);
        assertThat(dispatch.status()).isEqualTo(DispatchStatus.PREPARED);
    }

    @ParameterizedTest
    @EnumSource(value = DispatchStatus.class, names = {"IN_TRANSIT", "RECEIVED", "CANCELLED"})
    void only_a_prepared_dispatch_is_cancelled(DispatchStatus status) {
        assertThatThrownBy(() -> stored(status).cancel(ROSA, "Motivo válido", NOW))
                .isInstanceOf(InvalidDispatchTransitionException.class)
                .hasFieldOrPropertyWithValue("code", InvalidDispatchTransitionException.CODE);
    }

    @Test
    void incidents_are_reported_in_transit() {
        Dispatch dispatch = stored(DispatchStatus.IN_TRANSIT);

        TransitIncident incident = dispatch.reportIncident(IncidentType.DELAY, "  Tráfico en la vía  ", ROSA, NOW);

        assertThat(incident.id()).isNotNull();
        assertThat(incident.dispatchId()).isEqualTo(dispatch.id());
        assertThat(incident.type()).isEqualTo(IncidentType.DELAY);
        assertThat(incident.observation()).isEqualTo("Tráfico en la vía");
        assertThat(incident.reportedBy()).isEqualTo(ROSA);
        assertThat(incident.reportedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = DispatchStatus.class, names = {"PREPARED", "RECEIVED", "CANCELLED"})
    void incidents_only_while_in_transit(DispatchStatus status) {
        assertThatThrownBy(() -> stored(status).reportIncident(IncidentType.OTHER, "Algo pasó", ROSA, NOW))
                .isInstanceOf(InvalidDispatchTransitionException.class);
    }

    @Test
    void an_incident_needs_a_type_and_an_observation_from_3_to_500_characters() {
        Dispatch dispatch = stored(DispatchStatus.IN_TRANSIT);
        assertThatThrownBy(() -> dispatch.reportIncident(null, "Algo pasó", ROSA, NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", TransitIncident.INVALID_INCIDENT);
        assertThatThrownBy(() -> dispatch.reportIncident(IncidentType.DAMAGE, "ok", ROSA, NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> dispatch.reportIncident(IncidentType.DAMAGE, null, ROSA, NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> dispatch.reportIncident(IncidentType.DAMAGE, "x".repeat(501), ROSA, NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void incident_types_have_spanish_labels() {
        assertThat(IncidentType.DELAY.label()).isEqualTo("Retraso");
        assertThat(IncidentType.VEHICLE_FAILURE.label()).isEqualTo("Falla del vehículo");
        assertThat(IncidentType.DAMAGE.label()).isEqualTo("Daño de la carga");
        assertThat(IncidentType.ACCIDENT.label()).isEqualTo("Accidente");
        assertThat(IncidentType.ROUTE_CHANGE.label()).isEqualTo("Cambio de ruta");
        assertThat(IncidentType.OTHER.label()).isEqualTo("Otra");
        assertThat(DispatchStatus.CANCELLED.label()).isEqualTo("Anulado");
    }

    @Test
    void a_restored_cancelled_dispatch_keeps_its_cancellation() {
        Dispatch.Cancellation cancellation = new Dispatch.Cancellation(NOW, ROSA, "Motivo");
        Dispatch dispatch = Dispatch.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DispatchType.COMPLETE, CODE, List.of(new DispatchLine(UUID.randomUUID(), LINE_A, UUID.randomUUID(),
                        BigDecimal.ONE)), NOW, DispatchStatus.CANCELLED, null, null, null, null, cancellation, 2L);

        assertThat(dispatch.cancellation()).contains(cancellation);
        assertThatThrownBy(() -> new Dispatch.Cancellation(NOW, ROSA, null)).isInstanceOf(NullPointerException.class);
    }
}
