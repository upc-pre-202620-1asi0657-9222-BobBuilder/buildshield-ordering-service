package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrderingErrors;
import pe.buildshield.ordering.application.OrganizationDirectory;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderRepository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code OrderRepository} con Spring Data JPA; el filtro por organización lo pone Hibernate ({@code @TenantId}). Al leer
 * completa cada línea con el SKU y la unidad de {@code material_projection} y con lo recibido de {@code reception_lines},
 * en un número fijo de consultas.
 */
@Repository
@Transactional(readOnly = true)
class JpaOrderRepository implements OrderRepository {

    private final SpringDataOrderRepository jpa;
    private final SpringDataReceptionLineRepository receptionLines;
    private final OrganizationDirectory directory;
    private final EntityManager entityManager;

    JpaOrderRepository(SpringDataOrderRepository jpa, SpringDataReceptionLineRepository receptionLines,
            OrganizationDirectory directory, EntityManager entityManager) {
        this.jpa = jpa;
        this.receptionLines = receptionLines;
        this.directory = directory;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return jpa.findById(id).map(entity -> toDomain(List.of(entity)).get(0));
    }

    @Override
    public List<Order> findAll() {
        return toDomain(jpa.findAllByOrderByCreatedAtDesc());
    }

    @Override
    public List<Order> findBySites(Collection<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return List.of();
        }
        return toDomain(jpa.findBySites(siteIds));
    }

    @Override
    @Transactional
    public Order save(Order order) {
        if (order.id() == null) {
            return toDomain(List.of(jpa.saveAndFlush(new OrderJpaEntity(order)))).get(0);
        }
        OrderJpaEntity entity = jpa.findById(order.id()).orElseThrow(() -> OrderingErrors.orderNotFound(order.id()));
        if (!Objects.equals(entity.getVersion(), order.version())) {
            throw new ObjectOptimisticLockingFailureException(OrderJpaEntity.class, order.id());
        }
        // Un despacho puede cambiar solo las cantidades de las líneas (sin cambiar el estado): se fuerza la
        // versión del pedido para que dos despachos simultáneos no se pisen; el segundo falla con 409.
        entityManager.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        entity.apply(order);
        return toDomain(List.of(jpa.saveAndFlush(entity))).get(0);
    }

    private List<Order> toDomain(List<OrderJpaEntity> entities) {
        Map<UUID, Material> materials = directory.findMaterials(
                entities.stream().flatMap(entity -> entity.materialIds().stream()).collect(Collectors.toSet()));
        Map<UUID, BigDecimal> received = received(
                entities.stream().flatMap(entity -> entity.lineIds().stream()).toList());
        return entities.stream().map(entity -> entity.toDomain(materials, received)).toList();
    }

    private Map<UUID, BigDecimal> received(List<UUID> orderLineIds) {
        Map<UUID, BigDecimal> received = new HashMap<>();
        if (!orderLineIds.isEmpty()) {
            for (Object[] row : receptionLines.sumStockedByOrderLine(orderLineIds)) {
                received.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return received;
    }
}
