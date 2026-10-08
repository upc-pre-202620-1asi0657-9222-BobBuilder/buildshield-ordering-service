package pe.buildshield.ordering.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Servicio completo contra PostgreSQL real (Testcontainers o la base temporal externa explícita
 * {@code ordering_it}) y Kafka embebido ({@code @EmbeddedKafka}, KRaft) con los tópicos que usa ordering.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import(ContainersConfig.class)
@EmbeddedKafka(kraft = true, partitions = 3,
        topics = {"buildshield.ordering", "buildshield.organization", "buildshield.inventory"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public @interface OrderingIntegrationTest {
}
