package pe.buildshield.ordering.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.shared.messaging.Topics;
import pe.buildshield.ordering.support.TestEvents;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.ordering.acceptance.ApiRequest.get;
import static pe.buildshield.ordering.acceptance.ApiRequest.post;

/** US43: los despachos de retorno llegan por eventos de inventario; la API solo los muestra. */
public class ReturnDispatchSteps {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Dado("que inventario inició la devolución {string} de la obra {string} al almacén {string} con {string} del material {string}")
    public void returnDispatched(String name, String worksite, String warehouse, String quantity, String sku) {
        UUID returnId = UUID.randomUUID();
        UUID dispatchId = Dispatch.returnDispatchId(returnId);
        session.put(name + "-devolucion", returnId);
        session.put(name, dispatchId);
        session.putText(name + "-obra", worksite);
        session.putText(name + "-almacen", warehouse);
        session.putText(name + "-sku", sku);
        session.putText(name + "-cantidad", quantity);
        publish(name, "MaterialReturnDispatched", "IN_TRANSIT");
        await().atMost(TIMEOUT).until(() -> status(dispatchId) != null);
    }

    @Dado("que inventario recibió la devolución {string}")
    public void returnReceived(String name) {
        publish(name, "MaterialReturnReceived", "RECEIVED");
        UUID dispatchId = session.id(name);
        await().atMost(TIMEOUT).until(() -> "RECEIVED".equals(status(dispatchId)));
    }

    @Cuando("consulto el despacho {string}")
    public void getDispatch(String name) throws Exception {
        session.send(get("/api/v1/dispatches/" + session.id(name)), null);
    }

    @Cuando("intento abrir la recepción del despacho {string}")
    public void openReception(String name) throws Exception {
        session.send(post("/api/v1/receptions"), Map.of("dispatchId", session.id(name).toString()));
    }

    @Entonces("el despacho es un retorno sin pedido en estado {string}")
    public void isReturn(String status) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("type").asText()).isEqualTo("RETURN");
        assertThat(session.body().path("status").asText()).isEqualTo(status);
        assertThat(session.body().path("orderId").isNull()).isTrue();
        assertThat(session.body().path("manifestCode").asText()).startsWith("MAN-");
        assertThat(session.body().path("lines").get(0).path("orderLineId").isNull()).isTrue();
    }

    @Entonces("el despacho tiene fecha de recepción")
    public void hasReceivedAt() throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("receivedAt").isNull()).isFalse();
    }

    private void publish(String name, String eventType, String status) {
        UUID returnId = session.id(name + "-devolucion");
        kafka.send(Topics.INVENTORY, returnId.toString(), TestEvents.materialReturn(json, UUID.randomUUID(), eventType,
                session.organization(), returnId, session.id(session.text(name + "-obra")),
                session.id(session.text(name + "-almacen")), status, session.id(session.text(name + "-sku")),
                new BigDecimal(session.text(name + "-cantidad"))));
    }

    private String status(UUID dispatchId) {
        return jdbc.queryForList("SELECT status FROM dispatches WHERE id = ?", String.class, dispatchId).stream()
                .findFirst().orElse(null);
    }
}
