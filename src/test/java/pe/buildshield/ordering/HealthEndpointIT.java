package pe.buildshield.ordering;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import pe.buildshield.ordering.support.ContainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Levanta el servicio completo contra PostgreSQL real y Kafka embebido.
 * La base temporal es obligatoria; no se omite ninguna prueba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ContainersConfig.class)
@EmbeddedKafka(kraft = true, partitions = 3,
        topics = {"buildshield.ordering", "buildshield.organization", "buildshield.inventory"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class HealthEndpointIT {

    @Autowired
    TestRestTemplate rest;

    @Test
    void health_endpoint_is_public_and_up() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void other_endpoints_require_authentication() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/orders", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
