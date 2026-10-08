package pe.buildshield.ordering.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Escribe directo las copias locales de organization (en producción llegan por eventos). */
public final class TestProjections {

    private TestProjections() {
    }

    public static UUID location(JdbcTemplate jdbc, UUID organizationId, String type, String name, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO location_projection (location_id, organization_id, type, name, address, district, "
                        + "city, active, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, organizationId, type, name, "Av. Principal 100", "Miraflores", "Lima", active, now());
        return id;
    }

    public static UUID material(JdbcTemplate jdbc, UUID organizationId, String sku, String name, String unit,
            String tolerance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material_projection (material_id, organization_id, sku, name, unit, tolerance_percent, "
                        + "active, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?)",
                id, organizationId, sku, name, unit, new BigDecimal(tolerance), now());
        return id;
    }

    public static void assign(JdbcTemplate jdbc, UUID organizationId, UUID userId, UUID locationId, String type) {
        jdbc.update("INSERT INTO assignment_projection (assignment_id, organization_id, user_id, location_id, "
                        + "location_type, active, updated_at) VALUES (?, ?, ?, ?, ?, true, ?)",
                UUID.randomUUID(), organizationId, userId, locationId, type, now());
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
