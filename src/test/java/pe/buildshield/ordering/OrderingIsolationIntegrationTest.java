package pe.buildshield.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pe.buildshield.ordering.application.OrderCommandService;
import pe.buildshield.ordering.shared.security.JwtTokenIssuer;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;
import pe.buildshield.ordering.support.OrderingIntegrationTest;
import pe.buildshield.ordering.support.TestDatabase;
import pe.buildshield.ordering.support.TestProjections;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento multiempresa por HTTP con PostgreSQL real: una organización no ve ni modifica los pedidos, despachos
 * ni recepciones de otra (404), y sus listas no los incluyen. Tampoco usa las obras, almacenes ni materiales de otra.
 */
@OrderingIntegrationTest
@AutoConfigureMockMvc
class OrderingIsolationIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    ObjectMapper json;

    @Autowired
    OrderCommandService orderCommands;

    private final UUID andina = UUID.randomUUID();
    private final UUID otra = UUID.randomUUID();
    private final TenantInfo andinaAdmin = new TenantInfo(andina, UUID.randomUUID(), "ADMINISTRATOR");
    private final TenantInfo jorge = new TenantInfo(andina, UUID.randomUUID(), "SITE_MANAGER");
    private final TenantInfo otraAdmin = new TenantInfo(otra, UUID.randomUUID(), "ADMINISTRATOR");
    private final TenantInfo otraSite = new TenantInfo(otra, UUID.randomUUID(), "SITE_MANAGER");
    private UUID torre;
    private UUID central;
    private UUID cement;
    private String orderId;
    private String dispatchId;
    private String manifestCode;
    private String receptionId;

    @BeforeEach
    void andinaHasAReceptionInProgress() throws Exception {
        TestDatabase.reset(jdbc);
        torre = TestProjections.location(jdbc, andina, "WORKSITE", "Torre Norte", true);
        central = TestProjections.location(jdbc, andina, "WAREHOUSE", "Almacén Central", true);
        cement = TestProjections.material(jdbc, andina, "CEM-001", "Cemento", "BAG", "2.5");
        TestProjections.assign(jdbc, andina, jorge.userId(), torre, "WORKSITE");
        TestProjections.assign(jdbc, otra, otraSite.userId(), torre, "WORKSITE");

        orderId = body(send(post("/api/v1/orders"), jorge, "{\"worksiteId\":\"" + torre + "\",\"warehouseId\":\""
                + central + "\",\"lines\":[{\"materialId\":\"" + cement + "\",\"quantity\":10}]}", 201)).path("id").asText();
        send(post("/api/v1/orders/" + orderId + "/approve"), andinaAdmin, null, 200);
        TenantContext.runAs(new TenantInfo(andina, TenantContext.SYSTEM_USER_ID, "SYSTEM"),
                () -> orderCommands.confirmReservation(UUID.fromString(orderId)));
        String lineId = body(send(get("/api/v1/orders/" + orderId), andinaAdmin, null, 200)).path("lines").get(0)
                .path("id").asText();
        JsonNode dispatch = body(send(post("/api/v1/dispatches"), andinaAdmin, "{\"orderId\":\"" + orderId
                + "\",\"lines\":[{\"orderLineId\":\"" + lineId + "\",\"quantity\":10}]}", 201));
        dispatchId = dispatch.path("id").asText();
        manifestCode = dispatch.path("manifestCode").asText();
        send(patch("/api/v1/dispatches/" + dispatchId + "/carrier"), andinaAdmin, "{\"carrierName\":\"Rímac\",\"carrierDocument\":\"20555666777\",\"plate\":\"ABC-123\"}", 200);
        send(post("/api/v1/dispatches/" + dispatchId + "/departure-weighing"), andinaAdmin,
                "{\"grossKg\":2000,\"tareKg\":1000}", 200);
        send(post("/api/v1/dispatches/" + dispatchId + "/depart"), andinaAdmin, null, 200);
        receptionId = body(send(post("/api/v1/receptions"), jorge, "{\"dispatchId\":\"" + dispatchId + "\"}", 201))
                .path("id").asText();
    }

    @Test
    void another_organization_sees_nothing() throws Exception {
        for (String path : new String[]{"/api/v1/orders/" + orderId, "/api/v1/dispatches/" + dispatchId,
                "/api/v1/dispatches/" + dispatchId + "/manifest", "/api/v1/dispatches/" + dispatchId + "/incidents",
                "/api/v1/dispatches/by-manifest/" + manifestCode, "/api/v1/receptions/" + receptionId,
                "/api/v1/receptions/" + receptionId + "/comparison"}) {
            send(get(path), otraAdmin, null, 404);
        }
        assertThat(body(send(get("/api/v1/orders"), otraAdmin, null, 200)).size()).isZero();
        assertThat(body(send(get("/api/v1/dispatches"), otraAdmin, null, 200)).size()).isZero();
        assertThat(body(send(get("/api/v1/orders"), andinaAdmin, null, 200)).size()).isEqualTo(1);
        assertThat(body(send(get("/api/v1/dispatches"), andinaAdmin, null, 200)).size()).isEqualTo(1);
    }

    @Test
    void another_organization_cannot_change_anything() throws Exception {
        send(post("/api/v1/orders/" + orderId + "/reject"), otraAdmin, "{\"reason\":\"No\"}", 404);
        send(post("/api/v1/dispatches/" + dispatchId + "/incidents"), otraAdmin,
                "{\"type\":\"DELAY\",\"observation\":\"Tráfico\"}", 404);
        send(post("/api/v1/dispatches/" + dispatchId + "/cancel"), otraAdmin, "{\"reason\":\"Motivo\"}", 404);
        send(post("/api/v1/receptions/" + receptionId + "/confirm"), otraSite, null, 404);
        send(post("/api/v1/receptions"), otraSite, "{\"manifestCode\":\"" + manifestCode + "\"}", 404);
        send(post("/api/v1/orders"), otraSite, "{\"worksiteId\":\"" + torre + "\",\"warehouseId\":\"" + central
                + "\",\"lines\":[{\"materialId\":\"" + cement + "\",\"quantity\":1}]}", 404);
        assertThat(body(send(get("/api/v1/receptions/" + receptionId), jorge, null, 200)).path("status").asText())
                .isEqualTo("IN_PROGRESS");
    }

    private org.springframework.test.web.servlet.MvcResult send(MockHttpServletRequestBuilder request, TenantInfo who,
            String body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + issuer.issue(who).value());
        request.header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
