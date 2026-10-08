package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrderingErrors;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code DispatchRepository} con Spring Data JPA; el filtro por organización lo pone Hibernate ({@code @TenantId}). */
@Repository
@Transactional(readOnly = true)
class JpaDispatchRepository implements DispatchRepository {

    private final SpringDataDispatchRepository jpa;

    JpaDispatchRepository(SpringDataDispatchRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Dispatch> findById(UUID id) {
        return jpa.findById(id).map(DispatchJpaEntity::toDomain);
    }

    @Override
    public Optional<Dispatch> findByManifestCode(String manifestCode) {
        return jpa.findByManifestCode(manifestCode).map(DispatchJpaEntity::toDomain);
    }

    @Override
    public List<Dispatch> findAll() {
        return jpa.findAllByOrderByPreparedAtDesc().stream().map(DispatchJpaEntity::toDomain).toList();
    }

    @Override
    public List<Dispatch> findBySites(Collection<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return List.of();
        }
        return jpa.findBySites(siteIds).stream().map(DispatchJpaEntity::toDomain).toList();
    }

    @Override
    public List<Dispatch> findByOrderId(UUID orderId) {
        return jpa.findByOrderIdOrderByPreparedAtAsc(orderId).stream().map(DispatchJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public Dispatch save(Dispatch dispatch) {
        if (dispatch.id() == null) {
            return jpa.saveAndFlush(new DispatchJpaEntity(dispatch)).toDomain();
        }
        DispatchJpaEntity entity = jpa.findById(dispatch.id())
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(dispatch.id()));
        if (!Objects.equals(entity.getVersion(), dispatch.version())) {
            throw new ObjectOptimisticLockingFailureException(DispatchJpaEntity.class, dispatch.id());
        }
        entity.apply(dispatch);
        return jpa.saveAndFlush(entity).toDomain();
    }
}
