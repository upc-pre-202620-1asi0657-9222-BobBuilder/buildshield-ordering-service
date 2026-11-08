package pe.buildshield.ordering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Gestión de Pedidos y Entregas de BuildShield (microservicio, puerto 8084, base ordering_db). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BuildshieldOrderingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildshieldOrderingApplication.class, args);
    }
}
