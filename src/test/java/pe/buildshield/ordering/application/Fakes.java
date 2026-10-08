package pe.buildshield.ordering.application;

import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchLine;
import pe.buildshield.ordering.domain.model.dispatch.DispatchRepository;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncident;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncidentRepository;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderRepository;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.domain.model.reception.ReceptionRepository;
import pe.buildshield.ordering.shared.messaging.OutboxWriter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Dobles en memoria de los puertos de la aplicación, para probar los casos de uso sin base de datos ni Kafka. */
final class Fakes {

    private Fakes() {
    }

    /** Pedidos en memoria: asigna ids a pedido y líneas, y aumenta la versión en cada guardado. */
    static final class Orders implements OrderRepository {

        final Map<UUID, Order> stored = new LinkedHashMap<>();
        int saves;

        @Override
        public Optional<Order> findById(UUID id) {
            return Optional.ofNullable(stored.get(id)).map(order -> copy(order, order.version()));
        }

        @Override
        public List<Order> findAll() {
            return new ArrayList<>(stored.values());
        }

        @Override
        public List<Order> findBySites(Collection<UUID> siteIds) {
            return stored.values().stream()
                    .filter(order -> siteIds.contains(order.worksiteId()) || siteIds.contains(order.warehouseId()))
                    .toList();
        }

        @Override
        public Order save(Order order) {
            saves++;
            Order copy = copy(order, order.version() == null ? 0L : order.version() + 1);
            stored.put(copy.id(), copy);
            return copy;
        }

        private static Order copy(Order order, Long version) {
            UUID id = order.id() == null ? UUID.randomUUID() : order.id();
            List<OrderLine> lines = order.lines().stream().map(line -> OrderLine.restore(
                    line.id() == null ? UUID.randomUUID() : line.id(), line.materialId(), line.sku(), line.unit(),
                    line.requested(), line.dispatched(), line.cancelled(), line.received())).toList();
            Order copy = Order.restore(id, order.worksiteId(), order.warehouseId(), order.requestedBy(), order.notes(),
                    order.requiredDate(), order.placedAt(), lines, order.status(), order.rejectionReason(),
                    order.decidedBy(), order.decidedAt(), version);
            return copy;
        }
    }

    static final class Dispatches implements DispatchRepository {

        final Map<UUID, Dispatch> stored = new LinkedHashMap<>();

        @Override
        public Optional<Dispatch> findById(UUID id) {
            return Optional.ofNullable(stored.get(id)).map(dispatch -> copy(dispatch, dispatch.version()));
        }

        @Override
        public Optional<Dispatch> findByManifestCode(String manifestCode) {
            return stored.values().stream().filter(dispatch -> dispatch.manifestCode().value().equals(manifestCode))
                    .findFirst();
        }

        @Override
        public List<Dispatch> findAll() {
            return new ArrayList<>(stored.values());
        }

        @Override
        public List<Dispatch> findBySites(Collection<UUID> siteIds) {
            return stored.values().stream()
                    .filter(dispatch -> siteIds.contains(dispatch.worksiteId()) || siteIds.contains(dispatch.warehouseId()))
                    .toList();
        }

        @Override
        public List<Dispatch> findByOrderId(UUID orderId) {
            return stored.values().stream().filter(dispatch -> dispatch.orderId().equals(orderId)).toList();
        }

        @Override
        public Dispatch save(Dispatch dispatch) {
            Dispatch copy = copy(dispatch, dispatch.version() == null ? 0L : dispatch.version() + 1);
            stored.put(copy.id(), copy);
            return copy;
        }

        private static Dispatch copy(Dispatch dispatch, Long version) {
            UUID id = dispatch.id() == null ? UUID.randomUUID() : dispatch.id();
            List<DispatchLine> lines = dispatch.lines().stream().map(line -> new DispatchLine(
                    line.id() == null ? UUID.randomUUID() : line.id(), line.orderLineId(), line.materialId(),
                    line.quantity())).toList();
            Dispatch copy = Dispatch.restore(id, dispatch.orderId(), dispatch.warehouseId(), dispatch.worksiteId(),
                    dispatch.type(), dispatch.manifestCode(), lines, dispatch.preparedAt(), dispatch.status(),
                    dispatch.carrier().orElse(null), dispatch.departureWeighing().orElse(null), dispatch.departedAt(),
                    dispatch.receivedAt(), dispatch.cancellation().orElse(null),
                    version);
            return copy;
        }
    }

    static final class Incidents implements TransitIncidentRepository {

        final List<TransitIncident> stored = new ArrayList<>();

        @Override
        public TransitIncident save(TransitIncident incident) {
            stored.add(incident);
            return incident;
        }

        @Override
        public List<TransitIncident> findByDispatchId(UUID dispatchId) {
            return stored.stream().filter(incident -> incident.dispatchId().equals(dispatchId))
                    .sorted(Comparator.comparing(TransitIncident::reportedAt)).toList();
        }

        @Override
        public Map<UUID, Integer> countByDispatchIds(Collection<UUID> dispatchIds) {
            Map<UUID, Integer> counts = new HashMap<>();
            stored.stream().filter(incident -> dispatchIds.contains(incident.dispatchId()))
                    .forEach(incident -> counts.merge(incident.dispatchId(), 1, Integer::sum));
            return counts;
        }
    }

    static final class Receptions implements ReceptionRepository {

        final Map<UUID, Reception> stored = new LinkedHashMap<>();

        @Override
        public Optional<Reception> findById(UUID id) {
            return Optional.ofNullable(stored.get(id)).map(reception -> copy(reception, reception.version()));
        }

        @Override
        public Optional<UUID> findIdByDispatchId(UUID dispatchId) {
            return stored.values().stream().filter(reception -> reception.dispatchId().equals(dispatchId))
                    .map(Reception::id).findFirst();
        }

        @Override
        public Reception save(Reception reception) {
            Reception copy = copy(reception, reception.version() == null ? 0L : reception.version() + 1);
            stored.put(copy.id(), copy);
            return copy;
        }

        private static Reception copy(Reception reception, Long version) {
            UUID id = reception.id() == null ? UUID.randomUUID() : reception.id();
            List<ReceptionLine> lines = reception.lines().stream().map(line -> ReceptionLine.restore(
                    line.id() == null ? UUID.randomUUID() : line.id(), line.dispatchLineId(), line.orderLineId(),
                    line.materialId(), line.dispatchedQty(), line.receivedQty(), line.stockedQty(),
                    line.shrinkagePercent())).toList();
            Reception copy = Reception.restore(id, reception.dispatchId(), reception.orderId(), reception.warehouseId(),
                    reception.worksiteId(), reception.manifestCode(), lines, reception.status(),
                    reception.partialCount(), reception.lastPartialAt(), reception.confirmedBy(),
                    reception.confirmedAt(), reception.arrivalWeighing().orElse(null),
                    version);
            return copy;
        }
    }

    /** Copias locales de organization en memoria (sin filtro por organización: cada prueba usa una sola). */
    static final class Directory implements OrganizationDirectory {

        final Map<UUID, Location> locations = new HashMap<>();
        final Map<UUID, Material> materials = new HashMap<>();
        final Map<UUID, Set<UUID>> assignments = new HashMap<>();

        UUID worksite(String name) {
            UUID id = UUID.randomUUID();
            locations.put(id, new Location(id, WORKSITE, name, "Av. Principal 100", "Miraflores", "Lima", true));
            return id;
        }

        UUID warehouse(String name, boolean active) {
            UUID id = UUID.randomUUID();
            locations.put(id, new Location(id, WAREHOUSE, name, "Av. Argentina 2500, Callao", null, null, active));
            return id;
        }

        UUID material(String sku, String name, String unit, String tolerance, boolean active) {
            UUID id = UUID.randomUUID();
            materials.put(id, new Material(id, sku, name, unit, new BigDecimal(tolerance), active));
            return id;
        }

        void assign(UUID user, UUID site) {
            assignments.computeIfAbsent(user, ignored -> new HashSet<>()).add(site);
        }

        @Override
        public Optional<Location> findLocation(UUID locationId) {
            return Optional.ofNullable(locations.get(locationId));
        }

        @Override
        public Optional<Material> findMaterial(UUID materialId) {
            return Optional.ofNullable(materials.get(materialId));
        }

        @Override
        public Map<UUID, Material> findMaterials(Collection<UUID> materialIds) {
            Map<UUID, Material> found = new HashMap<>();
            materialIds.stream().filter(materials::containsKey).forEach(id -> found.put(id, materials.get(id)));
            return found;
        }

        @Override
        public boolean isAssigned(UUID userId, UUID locationId) {
            return assignments.getOrDefault(userId, Set.of()).contains(locationId);
        }

        @Override
        public Set<UUID> assignedSites(UUID userId) {
            return assignments.getOrDefault(userId, Set.of());
        }
    }

    /** Outbox que solo recuerda los eventos escritos. */
    static final class Outbox extends OutboxWriter {

        record Appended(String eventType, String aggregateType, Object aggregateId, Object payload) {
        }

        final List<Appended> events = new ArrayList<>();

        Outbox() {
            super(null, null, null);
        }

        @Override
        public UUID append(String eventType, String aggregateType, Object aggregateId, Object payload) {
            events.add(new Appended(eventType, aggregateType, aggregateId, payload));
            return UUID.randomUUID();
        }

        List<String> types() {
            return events.stream().map(Appended::eventType).toList();
        }

        @SuppressWarnings("unchecked")
        <T> T last(String eventType, Class<T> payloadType) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).eventType().equals(eventType)) {
                    return (T) events.get(i).payload();
                }
            }
            throw new AssertionError("No se publicó " + eventType + "; publicados: " + types());
        }
    }
}
