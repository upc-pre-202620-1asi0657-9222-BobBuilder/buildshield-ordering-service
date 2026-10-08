package pe.buildshield.ordering.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Dado;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.ordering.shared.messaging.Topics;
import pe.buildshield.ordering.support.TestEvents;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.awaitility.Awaitility.await;

/**
 * Datos de organization (obras, almacenes, materiales y asignaciones). Como en producción, llegan por eventos de
 * {@code buildshield.organization} a Kafka; cada paso espera a que la copia local se actualice.
 */
public class OrganizationSteps {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    /** Asignaciones del escenario: "correo|lugar" → id. */
    private final Map<String, UUID> assignments = new HashMap<>();

    @Dado("que registré la obra {string} del {string} al {string}")
    public void worksiteRegistered(String name, String start, String end) {
        location(name, "WORKSITE", "LocationRegistered", true);
    }

    @Dado("que registré el almacén {string}")
    public void warehouseRegistered(String name) {
        location(name, "WAREHOUSE", "LocationRegistered", true);
    }

    @Dado("que desactivé el almacén {string}")
    public void warehouseDeactivated(String name) {
        location(name, "WAREHOUSE", "LocationUpdated", false);
    }

    @Dado("que registré el material {string} con SKU {string}, unidad {string} y tolerancia de merma {string} %")
    public void materialRegistered(String name, String sku, String unit, String tolerance) {
        material("MaterialRegistered", name, sku, unit(unit), tolerance, true);
    }

    @Dado("que retiré del catálogo el material {string}")
    public void materialRetired(String sku) {
        String[] data = materials.get(sku.toUpperCase());
        material("MaterialUpdated", data[0], sku.toUpperCase(), data[1], data[2], false);
    }

    @Dado("que asigné a {string} a la obra {string}")
    public void assignedToWorksite(String email, String worksite) {
        assign(email, worksite, "WORKSITE", "StaffAssigned");
    }

    @Dado("que asigné a {string} al almacén {string}")
    public void assignedToWarehouse(String email, String warehouse) {
        assign(email, warehouse, "WAREHOUSE", "StaffAssigned");
    }

    @Dado("que terminé la asignación de {string} a la obra {string}")
    public void unassignedFromWorksite(String email, String worksite) {
        assign(email, worksite, "WORKSITE", "StaffUnassigned");
    }

    /** Material por SKU: [nombre, unidad, tolerancia]. */
    private final Map<String, String[]> materials = new HashMap<>();

    private void location(String name, String type, String eventType, boolean active) {
        UUID id = "LocationRegistered".equals(eventType) ? UUID.randomUUID() : session.id(name);
        session.put(name, id);
        kafka.send(Topics.ORGANIZATION, id.toString(), TestEvents.location(json, eventType, session.organization(), id,
                type, name, active));
        awaitRow(() -> count("SELECT count(*) FROM location_projection WHERE location_id = ? AND active = ?", id,
                active));
    }

    private void material(String eventType, String name, String sku, String unit, String tolerance, boolean active) {
        UUID id = "MaterialRegistered".equals(eventType) ? UUID.randomUUID() : session.id(sku);
        session.put(sku, id);
        materials.put(sku, new String[]{name, unit, tolerance});
        kafka.send(Topics.ORGANIZATION, id.toString(), TestEvents.material(json, eventType, session.organization(), id,
                sku, name, unit, new BigDecimal(tolerance), active));
        awaitRow(() -> count("SELECT count(*) FROM material_projection WHERE material_id = ? AND active = ?", id,
                active));
    }

    private void assign(String email, String site, String type, String eventType) {
        String key = email + "|" + site;
        UUID id = assignments.computeIfAbsent(key, ignored -> UUID.randomUUID());
        boolean active = "StaffAssigned".equals(eventType);
        kafka.send(Topics.ORGANIZATION, id.toString(), TestEvents.assignment(json, eventType, session.organization(), id,
                session.id(email), session.id(site), type));
        awaitRow(() -> count("SELECT count(*) FROM assignment_projection WHERE assignment_id = ? AND active = ?", id,
                active));
    }

    private void awaitRow(Callable<Long> count) {
        await().atMost(TIMEOUT).pollInSameThread().pollInterval(Duration.ofMillis(50)).until(count, value -> value == 1);
    }

    private long count(String sql, UUID id, boolean active) {
        Long count = jdbc.queryForObject(sql, Long.class, id, active);
        return count == null ? 0 : count;
    }

    /** "bolsa" → BAG; "unidad" → UNIT; "metro cúbico" → M3. */
    private static String unit(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "bolsa" -> "BAG";
            case "unidad" -> "UNIT";
            case "metro cúbico" -> "M3";
            case "kilogramo" -> "KG";
            case "caja" -> "BOX";
            default -> displayName;
        };
    }
}
