package pe.buildshield.ordering.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.spring.CucumberContextConfiguration;
import io.cucumber.spring.ScenarioScope;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import pe.buildshield.ordering.support.ContainersConfig;

/**
 * Contexto de los escenarios: servicio completo en un servidor HTTP real (puerto aleatorio), PostgreSQL real y Kafka
 * embebido. Obras, almacenes, materiales y asignaciones llegan como en producción: eventos de organization en
 * {@code buildshield.organization}. La saga de stock la responde {@link FakeInventory}. El outbox se publica solo
 * (cada 100 ms), como en producción.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"buildshield.scheduling.enabled=true", "buildshield.messaging.outbox-interval=PT0.1S"})
@ActiveProfiles("test")
@Import({ContainersConfig.class, CucumberSpringConfiguration.AcceptanceBeans.class})
@EmbeddedKafka(kraft = true, partitions = 3,
        topics = {"buildshield.ordering", "buildshield.organization", "buildshield.inventory"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public class CucumberSpringConfiguration {

    @TestConfiguration(proxyBeanMethods = false)
    static class AcceptanceBeans {

        @Bean
        FakeInventory fakeInventory(KafkaTemplate<String, String> kafka, ObjectMapper json) {
            return new FakeInventory(kafka, json);
        }

        @Bean
        @ScenarioScope
        ScenarioSession scenarioSession(Environment environment, ObjectMapper json) {
            return new ScenarioSession("http://localhost:" + environment.getRequiredProperty("local.server.port"), json);
        }
    }
}
