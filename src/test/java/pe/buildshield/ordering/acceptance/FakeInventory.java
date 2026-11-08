package pe.buildshield.ordering.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.ordering.shared.messaging.Topics;
import pe.buildshield.ordering.support.TestEvents;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventario simulado para los escenarios (solo pruebas, registrado como bean de la configuración de Cucumber): hace
 * lo mismo que buildshield-inventory-service en la saga, en memoria. Consume {@code buildshield.ordering}:
 *
 * <ul>
 *   <li>{@code OrderApproved}: reserva en el almacén lo solicitado de cada línea y responde {@code StockReserved}, o
 *       {@code StockReservationRejected} con el motivo si alguna línea no alcanza (no reserva nada).</li>
 *   <li>{@code DispatchDeparted}: consume la reserva del despacho.</li>
 *   <li>{@code ReceptionPartiallyReceived} y {@code ReceptionConfirmed}: suma el {@code deltaQuantity} a la obra.</li>
 * </ul>
 */
public class FakeInventory {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    /** Disponible y reservado por lugar (almacén u obra) y material. */
    private final Map<String, BigDecimal> available = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> reserved = new ConcurrentHashMap<>();
    private final Map<UUID, String> skus = new ConcurrentHashMap<>();

    public FakeInventory(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void reset() {
        available.clear();
        reserved.clear();
        skus.clear();
    }

    public void setAvailable(UUID location, UUID material, BigDecimal quantity) {
        available.put(key(location, material), quantity);
    }

    public BigDecimal available(UUID location, UUID material) {
        return available.getOrDefault(key(location, material), BigDecimal.ZERO);
    }

    public BigDecimal reserved(UUID location, UUID material) {
        return reserved.getOrDefault(key(location, material), BigDecimal.ZERO);
    }

    @KafkaListener(topics = Topics.ORDERING, groupId = "fake-inventory")
    public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        JsonNode event = json.readTree(record.value());
        JsonNode payload = event.path("payload");
        UUID organization = UUID.fromString(event.path("organizationId").asText());
        switch (event.path("eventType").asText()) {
            case "OrderApproved" -> reserve(organization, payload);
            case "DispatchDeparted" -> {
                UUID warehouse = uuid(payload, "warehouseId");
                for (JsonNode line : payload.path("lines")) {
                    reserved.merge(key(warehouse, uuid(line, "materialId")), line.path("quantity").decimalValue().negate(),
                            BigDecimal::add);
                }
            }
            case "ReceptionPartiallyReceived", "ReceptionConfirmed" -> {
                UUID worksite = uuid(payload, "worksiteId");
                for (JsonNode line : payload.path("lines")) {
                    available.merge(key(worksite, uuid(line, "materialId")), line.path("deltaQuantity").decimalValue(),
                            BigDecimal::add);
                }
            }
            default -> {
                // Los demás eventos no mueven stock.
            }
        }
    }

    private synchronized void reserve(UUID organization, JsonNode payload) {
        UUID order = uuid(payload, "orderId");
        UUID warehouse = uuid(payload, "warehouseId");
        List<Map<String, Object>> missing = new ArrayList<>();
        String reason = null;
        for (JsonNode line : payload.path("lines")) {
            BigDecimal requested = line.path("quantity").decimalValue();
            BigDecimal has = available(warehouse, uuid(line, "materialId"));
            if (has.compareTo(requested) < 0) {
                missing.add(Map.of("materialId", line.path("materialId").asText(), "requested", requested,
                        "available", has));
                if (reason == null) {
                    reason = "Stock insuficiente en el almacén para " + line.path("sku").asText() + " (hay "
                            + has.stripTrailingZeros().toPlainString() + ", se pidió "
                            + requested.stripTrailingZeros().toPlainString() + ")";
                }
            }
        }
        if (!missing.isEmpty()) {
            kafka.send(Topics.INVENTORY, order.toString(), TestEvents.stockRejected(json, UUID.randomUUID(),
                    organization, order, warehouse, reason, missing));
            return;
        }
        List<Map<String, Object>> reservations = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            UUID material = uuid(line, "materialId");
            BigDecimal quantity = line.path("quantity").decimalValue();
            available.merge(key(warehouse, material), quantity.negate(), BigDecimal::add);
            reserved.merge(key(warehouse, material), quantity, BigDecimal::add);
            Map<String, Object> reservation = new LinkedHashMap<>();
            reservation.put("orderLineId", line.path("orderLineId").asText());
            reservation.put("materialId", material);
            reservation.put("quantity", quantity);
            reservations.add(reservation);
        }
        kafka.send(Topics.INVENTORY, order.toString(), TestEvents.stockReserved(json, UUID.randomUUID(), organization,
                order, warehouse, reservations));
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private static String key(UUID location, UUID material) {
        return location + "/" + material;
    }
}
