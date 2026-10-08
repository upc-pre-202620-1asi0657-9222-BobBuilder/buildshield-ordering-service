package pe.buildshield.ordering.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** Limpieza SOLO de la base efímera de pruebas ({@code test} de Testcontainers o {@code ordering_it}). */
public final class TestDatabase {

    /** Tablas del servicio, sin el historial de Flyway. */
    public static final List<String> TABLES = List.of("arrival_weighings", "reception_lines", "receptions",
            "transit_incidents", "departure_weighings", "dispatch_lines", "dispatches", "order_lines", "orders",
            "location_projection", "material_projection", "assignment_projection", "idempotency_keys",
            "outbox_events", "processed_events");

    private TestDatabase() {
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String database;
            try (var query = connection.createStatement(); var result = query.executeQuery("SELECT current_database()")) {
                result.next();
                database = result.getString(1);
            }
            if (!(database.equals("test") || database.equals("ordering_it"))) {
                throw new IllegalStateException("La limpieza requiere una base temporal de pruebas");
            }
            try (var statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " CASCADE");
            }
            return null;
        });
    }
}
