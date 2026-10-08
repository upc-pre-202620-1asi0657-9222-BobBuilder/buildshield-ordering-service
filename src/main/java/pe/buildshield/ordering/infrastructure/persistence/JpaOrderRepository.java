package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrderingErrors;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code OrderRepository} con Spring Data JPA; el filtro por organización lo pone Hibernate ({@code @TenantId}). */
@Repository
@Transactional(readOnly = true)
class JpaOrderRepository implements OrderRepository {

    private final SpringDataOrderRepository jpa;
    private final EntityManager entityManager;

    JpaOrderRepository(SpringDataOrderRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return jpa.findById(id).map(OrderJpaEntity::toDomain);
    }

    @Override
    public List<Order> findAll() {
        return jpa.findAllByOrderByPlacedAtDesc().stream().map(OrderJpaEntity::toDomain).toList();
    }

    @Override
    public List<Order> findBySites(Collection<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return List.of();
        }
        return jpa.findBySites(siteIds).stream().map(OrderJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public Order save(Order order) {
        if (order.id() == null) {
            return jpa.saveAndFlush(new OrderJpaEntity(order)).toDomain();
        }
        OrderJpaEntity entity = jpa.findById(order.id()).orElseThrow(() -> OrderingErrors.orderNotFound(order.id()));
        if (!Objects.equals(entity.getVersion(), order.version())) {
            throw new ObjectOptimisticLockingFailureException(OrderJpaEntity.class, order.id());
        }
        // Un despacho puede cambiar solo las cantidades de las líneas (sin cambiar el estado): se fuerza la
        // versión del pedido para que dos despachos simultáneos no se pisen; el segundo falla con 409.
        entityManager.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        entity.apply(order);
        return jpa.saveAndFlush(entity).toDomain();
    }
}
