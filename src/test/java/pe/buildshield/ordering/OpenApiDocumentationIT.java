package pe.buildshield.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.ordering.support.OrderingIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La especificación OpenAPI es pública y documenta los endpoints de ordering con su esquema de seguridad. */
@OrderingIntegrationTest
@AutoConfigureMockMvc
class OpenApiDocumentationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void api_docs_are_public_and_describe_the_ordering_endpoints() throws Exception {
        String body = mvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = json.readTree(body);

        assertThat(spec.path("paths").fieldNames()).toIterable().contains(
                "/api/v1/orders", "/api/v1/orders/{id}/approve", "/api/v1/dispatches",
                "/api/v1/dispatches/{id}/cancel", "/api/v1/dispatches/{id}/incidents",
                "/api/v1/dispatches/by-manifest/{code}", "/api/v1/receptions", "/api/v1/receptions/{id}/partial",
                "/api/v1/receptions/{id}/arrival-weighing");
        assertThat(spec.at("/paths/~1api~1v1~1orders/post/responses").fieldNames()).toIterable()
                .contains("201", "400", "403", "404", "409");
        assertThat(spec.at("/paths/~1api~1v1~1orders~1{id}~1approve/post/responses").fieldNames()).toIterable()
                .contains("200", "404", "409");
        assertThat(spec.at("/paths/~1api~1v1~1orders~1{id}~1approve/post/description").asText())
                .contains("StockReserved", "StockReservationRejected");
        assertThat(spec.at("/components/securitySchemes/bearer/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.at("/paths/~1api~1v1~1dispatches~1{id}~1cancel/post/summary").asText())
                .isEqualTo("Anular un despacho preparado");
        assertThat(spec.at("/components/schemas/DispatchResource/properties").fieldNames()).toIterable()
                .contains("incidentCount", "cancelledAt", "cancellationReason");
        assertThat(spec.at("/components/schemas/ReceptionResource/properties").fieldNames()).toIterable()
                .contains("partialCount", "lastPartialAt", "arrivalWeighing");
        assertThat(spec.at("/components/schemas/OrderResource/properties").fieldNames()).toIterable()
                .contains("requiredDate");
    }

    @Test
    void swagger_ui_is_public() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
