package pe.buildshield.ordering.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pe.buildshield.ordering.acceptance.ApiRequest.get;
import static pe.buildshield.ordering.acceptance.ApiRequest.post;

/**
 * Pasos del Sprint 3: US25 anulación, US26 consulta por manifiesto, US31 seguimiento, US32 incidencias, US34 apertura
 * por código, US38 recepción parcial y US29 pesaje de llegada.
 */
public class Sprint3Steps {

    @Autowired
    ScenarioSession session;

    // ---------- US25 Anular despacho ----------

    @Cuando("anulo el despacho {string} con el motivo {string}")
    public void cancel(String dispatch, String reason) throws Exception {
        session.send(post("/api/v1/dispatches/" + session.id(dispatch) + "/cancel"), Map.of("reason", reason));
    }

    @Entonces("el despacho {string} tiene el motivo de anulación {string}")
    public void cancellationReasonIs(String dispatch, String reason) throws Exception {
        JsonNode found = dispatchJson(dispatch);
        assertThat(found.path("cancellationReason").asText()).isEqualTo(reason);
        assertThat(found.path("cancelledAt").isNull()).isFalse();
    }

    // ---------- US32 Incidencias y US31 seguimiento ----------

    @Cuando("registro una incidencia {string} en el despacho {string} con la observación {string}")
    public void reportIncident(String typeLabel, String dispatch, String observation) throws Exception {
        session.send(post("/api/v1/dispatches/" + session.id(dispatch) + "/incidents"),
                Map.of("type", incidentType(typeLabel), "observation", observation));
    }

    @Dado("que registré una incidencia {string} en el despacho {string} con la observación {string}")
    public void incidentReported(String typeLabel, String dispatch, String observation) throws Exception {
        reportIncident(typeLabel, dispatch, observation);
        session.expectStatus(201);
    }

    @Entonces("la incidencia registrada es de tipo {string}")
    public void incidentIs(String typeLabel) throws Exception {
        assertThat(session.body().path("typeLabel").asText()).isEqualTo(typeLabel);
        assertThat(session.body().path("type").asText()).isEqualTo(incidentType(typeLabel));
    }

    @Entonces("el despacho {string} tiene {int} incidencia(s)")
    public void incidentCount(String dispatch, int count) throws Exception {
        assertThat(dispatchJson(dispatch).path("incidentCount").asInt()).isEqualTo(count);
    }

    @Entonces("la lista de despachos muestra el despacho {string} con {int} incidencia(s)")
    public void listShowsIncidents(String dispatch, int count) throws Exception {
        JsonNode found = null;
        for (JsonNode item : session.body()) {
            if (item.path("id").asText().equals(session.id(dispatch).toString())) {
                found = item;
            }
        }
        assertThat(found).as("despacho %s en la lista", dispatch).isNotNull();
        assertThat(found.path("incidentCount").asInt()).isEqualTo(count);
        assertThat(found.path("departedAt").isNull()).isFalse();
    }

    @Cuando("consulto las incidencias del despacho {string}")
    public void listIncidents(String dispatch) throws Exception {
        session.send(get("/api/v1/dispatches/" + session.id(dispatch) + "/incidents"), null);
    }

    @Entonces("las incidencias son, en orden:")
    public void incidentsAre(List<String> labels) throws Exception {
        session.expectStatus(200);
        List<String> found = new ArrayList<>();
        session.body().forEach(item -> found.add(item.path("typeLabel").asText()));
        assertThat(found).containsExactlyElementsOf(labels);
    }

    // ---------- US26 Consulta por código de manifiesto ----------

    @Cuando("consulto el despacho por el código del manifiesto de {string}")
    public void byManifestOf(String dispatch) throws Exception {
        session.send(get("/api/v1/dispatches/by-manifest/" + manifestCode(dispatch)), null);
    }

    @Cuando("consulto el despacho por el código {string}")
    public void byCode(String code) throws Exception {
        session.send(get("/api/v1/dispatches/by-manifest/" + code), null);
    }

    @Entonces("la respuesta es el despacho {string}")
    public void responseIsDispatch(String dispatch) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("id").asText()).isEqualTo(session.id(dispatch).toString());
    }

    // ---------- US34 Apertura por código ----------

    @Cuando("abro la recepción {string} con el código del manifiesto de {string}")
    public void startByCode(String name, String dispatch) throws Exception {
        session.send(post("/api/v1/receptions"), Map.of("manifestCode", manifestCode(dispatch)));
        if (session.status() == 201) {
            session.remember(name);
        }
    }

    @Dado("que abrí la recepción {string} con el código del manifiesto de {string}")
    public void startedByCode(String name, String dispatch) throws Exception {
        startByCode(name, dispatch);
        session.expectStatus(201);
    }

    // ---------- US38 Recepción parcial ----------

    @Cuando("guardo la recepción parcial de {string} con la clave {string}")
    public void partial(String reception, String key) throws Exception {
        session.send(post("/api/v1/receptions/" + session.id(reception) + "/partial").idempotencyKey(session.key(key)),
                null);
    }

    @Dado("que guardé la recepción parcial de {string} con la clave {string}")
    public void partialSaved(String reception, String key) throws Exception {
        partial(reception, key);
        session.expectStatus(200);
    }

    @Entonces("la respuesta es la misma recepción parcial reproducida")
    public void partialReplayed() {
        session.expectStatus(200);
        assertThat(session.last().replayed()).isEqualTo("true");
    }

    @Entonces("la recepción {string} tiene {int} recepción(es) parcial(es)")
    public void partialCount(String reception, int count) throws Exception {
        JsonNode found = receptionJson(reception);
        assertThat(found.path("partialCount").asInt()).isEqualTo(count);
        assertThat(found.path("lastPartialAt").isNull()).isEqualTo(count == 0);
    }

    // ---------- US29 Pesaje de llegada ----------

    @Cuando("registro el pesaje de llegada de la recepción {string} con bruto {string} kg y tara {string} kg")
    public void arrivalWeighing(String reception, String gross, String tare) throws Exception {
        session.send(post("/api/v1/receptions/" + session.id(reception) + "/arrival-weighing"),
                Map.of("grossKg", new BigDecimal(gross), "tareKg", new BigDecimal(tare)));
    }

    @Dado("que registré el pesaje de llegada de la recepción {string} con bruto {string} kg y tara {string} kg")
    public void arrivalWeighed(String reception, String gross, String tare) throws Exception {
        arrivalWeighing(reception, gross, tare);
        session.expectStatus(201);
    }

    @Entonces("el pesaje de llegada tiene neto {string} kg, diferencia {string} kg \\({string} %) y {string} dentro de la tolerancia de {string} %")
    public void arrivalResult(String net, String difference, String percent, String within, String tolerance)
            throws Exception {
        session.expectStatus(201);
        JsonNode weighing = session.body();
        assertThat(weighing.path("netKg").decimalValue()).isEqualByComparingTo(net);
        assertThat(weighing.path("departureNetKg").decimalValue()).isEqualByComparingTo("1500");
        assertThat(weighing.path("differenceKg").decimalValue()).isEqualByComparingTo(difference);
        assertThat(weighing.path("differencePercent").decimalValue()).isEqualByComparingTo(percent);
        assertThat(weighing.path("tolerancePercent").decimalValue()).isEqualByComparingTo(tolerance);
        assertThat(weighing.path("withinTolerance").asBoolean()).isEqualTo("sí".equals(within));
    }

    @Entonces("la recepción {string} muestra el pesaje de llegada con neto {string} kg")
    public void receptionShowsWeighing(String reception, String net) throws Exception {
        assertThat(receptionJson(reception).path("arrivalWeighing").path("netKg").decimalValue())
                .isEqualByComparingTo(net);
    }

    // ---------- Ayudas ----------

    /** Código guardado al crear el despacho (quien consulta puede no ver el despacho). */
    private String manifestCode(String dispatch) {
        return session.text(dispatch + "#manifest");
    }

    private JsonNode dispatchJson(String name) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/dispatches/" + session.id(name)), null,
                session.accessToken());
        assertThat(response.status()).as("consultar despacho %s: %s", name, response.body()).isEqualTo(200);
        return session.read(response);
    }

    private JsonNode receptionJson(String name) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/receptions/" + session.id(name)), null,
                session.accessToken());
        assertThat(response.status()).as("consultar recepción %s: %s", name, response.body()).isEqualTo(200);
        return session.read(response);
    }

    /** "Retraso" → DELAY, "Cambio de ruta" → ROUTE_CHANGE, … */
    private static String incidentType(String label) {
        return switch (label) {
            case "Retraso" -> "DELAY";
            case "Falla del vehículo" -> "VEHICLE_FAILURE";
            case "Daño de la carga" -> "DAMAGE";
            case "Accidente" -> "ACCIDENT";
            case "Cambio de ruta" -> "ROUTE_CHANGE";
            case "Otra" -> "OTHER";
            default -> label;
        };
    }
}
