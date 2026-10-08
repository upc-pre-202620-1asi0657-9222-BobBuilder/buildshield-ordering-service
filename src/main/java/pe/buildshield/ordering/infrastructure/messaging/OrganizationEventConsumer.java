package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.infrastructure.persistence.JdbcOrganizationProjections;
import pe.buildshield.ordering.shared.messaging.EventEnvelope;
import pe.buildshield.ordering.shared.messaging.IdempotentEventProcessor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consume el tópico {@code buildshield.organization} y actualiza las copias locales:
 *
 * <ul>
 *   <li>{@code LocationRegistered} y {@code LocationUpdated}: {@code location_projection} (obras y almacenes).</li>
 *   <li>{@code MaterialRegistered} y {@code MaterialUpdated}: {@code material_projection} (SKU, unidad y
 *       tolerancia).</li>
 *   <li>{@code StaffAssigned} y {@code StaffUnassigned}: {@code assignment_projection} (qué obras y almacenes ve cada
 *       encargado; la desasignación la desactiva).</li>
 * </ul>
 *
 * Cada evento se procesa una sola vez ({@link IdempotentEventProcessor}). Los demás tipos se ignoran.
 */
@Component
public class OrganizationEventConsumer {

    static final String LOCATION_REGISTERED = "LocationRegistered";
    static final String LOCATION_UPDATED = "LocationUpdated";
    static final String MATERIAL_REGISTERED = "MaterialRegistered";
    static final String MATERIAL_UPDATED = "MaterialUpdated";
    static final String STAFF_ASSIGNED = "StaffAssigned";
    static final String STAFF_UNASSIGNED = "StaffUnassigned";

    private static final Logger log = LoggerFactory.getLogger(OrganizationEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final JdbcOrganizationProjections projections;

    public OrganizationEventConsumer(IdempotentEventProcessor processor, JdbcOrganizationProjections projections) {
        this.processor = processor;
        this.projections = projections;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.organization:buildshield.organization}")
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.process(record, this::handle);
    }

    void handle(EventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.eventType()) {
            case LOCATION_REGISTERED, LOCATION_UPDATED -> projections.upsertLocation(event.organizationId(),
                    uuid(payload, "locationId"), payload.path("type").asText(), text(payload, "name"),
                    text(payload, "address"), text(payload, "district"), text(payload, "city"),
                    payload.path("active").asBoolean(true));
            case MATERIAL_REGISTERED, MATERIAL_UPDATED -> projections.upsertMaterial(event.organizationId(),
                    uuid(payload, "materialId"), text(payload, "sku"), text(payload, "name"), text(payload, "unit"),
                    payload.hasNonNull("tolerancePercent") ? payload.get("tolerancePercent").decimalValue()
                            : BigDecimal.ZERO,
                    payload.path("active").asBoolean(true));
            case STAFF_ASSIGNED, STAFF_UNASSIGNED -> projections.upsertAssignment(event.organizationId(),
                    uuid(payload, "assignmentId"), uuid(payload, "userId"), uuid(payload, "locationId"),
                    payload.path("locationType").asText(), STAFF_ASSIGNED.equals(event.eventType()));
            default -> log.debug("Evento {} ignorado por ordering", event.eventType());
        }
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private static String text(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asText() : null;
    }
}
