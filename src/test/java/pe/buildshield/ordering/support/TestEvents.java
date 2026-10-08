package pe.buildshield.ordering.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sobres JSON de los eventos que ordering consume, como los publican organization e inventory por su outbox. Las
 * pruebas los envían a Kafka embebido.
 */
public final class TestEvents {

    private TestEvents() {
    }

    public static String envelope(ObjectMapper json, UUID eventId, String eventType, UUID organizationId,
            String aggregateType, UUID aggregateId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("organizationId", organizationId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("actorId", null);
        envelope.put("version", 1);
        envelope.put("payload", payload);
        try {
            return json.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** LocationRegistered (o LocationUpdated) de una obra (WORKSITE) o almacén (WAREHOUSE). */
    public static String location(ObjectMapper json, String eventType, UUID organizationId, UUID locationId,
            String type, String name, boolean active) {
        boolean worksite = "WORKSITE".equals(type);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("locationId", locationId);
        payload.put("type", type);
        payload.put("name", name);
        payload.put("address", worksite ? "Av. Principal 100" : "Av. Argentina 2500, Callao");
        payload.put("active", active);
        payload.put("district", worksite ? "Miraflores" : null);
        payload.put("city", worksite ? "Lima" : null);
        payload.put("warehouseType", worksite ? null : "WAREHOUSE");
        payload.put("startDate", worksite ? "2026-11-01" : null);
        payload.put("endDate", null);
        return envelope(json, UUID.randomUUID(), eventType, organizationId, "LOCATION", locationId, payload);
    }

    public static String material(ObjectMapper json, String eventType, UUID organizationId, UUID materialId, String sku,
            String name, String unit, BigDecimal tolerancePercent, boolean active) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("materialId", materialId);
        payload.put("sku", sku);
        payload.put("name", name);
        payload.put("unit", unit);
        payload.put("tolerancePercent", tolerancePercent);
        payload.put("active", active);
        return envelope(json, UUID.randomUUID(), eventType, organizationId, "MATERIAL", materialId, payload);
    }

    public static String assignment(ObjectMapper json, String eventType, UUID organizationId, UUID assignmentId,
            UUID userId, UUID locationId, String locationType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", assignmentId);
        payload.put("userId", userId);
        payload.put("locationId", locationId);
        payload.put("locationType", locationType);
        return envelope(json, UUID.randomUUID(), eventType, organizationId, "ASSIGNMENT", assignmentId, payload);
    }

    public static String stockReserved(ObjectMapper json, UUID eventId, UUID organizationId, UUID orderId,
            UUID warehouseId, List<Map<String, Object>> reservations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("warehouseId", warehouseId);
        payload.put("reservations", reservations);
        return envelope(json, eventId, "StockReserved", organizationId, "ORDER", orderId, payload);
    }

    public static String stockRejected(ObjectMapper json, UUID eventId, UUID organizationId, UUID orderId,
            UUID warehouseId, String reason, List<Map<String, Object>> missing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("warehouseId", warehouseId);
        payload.put("reason", reason);
        payload.put("missing", missing);
        return envelope(json, eventId, "StockReservationRejected", organizationId, "ORDER", orderId, payload);
    }
}
