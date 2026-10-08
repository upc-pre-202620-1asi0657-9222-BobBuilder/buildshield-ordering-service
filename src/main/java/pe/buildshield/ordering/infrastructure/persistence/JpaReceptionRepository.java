package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrderingErrors;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code ReceptionRepository} con Spring Data JPA; el filtro por organización lo pone Hibernate ({@code @TenantId}). */
@Repository
@Transactional(readOnly = true)
class JpaReceptionRepository implements ReceptionRepository {

    private final SpringDataReceptionRepository jpa;
    private final EntityManager entityManager;

    JpaReceptionRepository(SpringDataReceptionRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Reception> findById(UUID id) {
        return jpa.findWithLines(id).map(ReceptionJpaEntity::toDomain);
    }

    @Override
    public Optional<UUID> findIdByDispatchId(UUID dispatchId) {
        return jpa.findIdByDispatchId(dispatchId);
    }

    @Override
    @Transactional
    public Reception save(Reception reception) {
        if (reception.id() == null) {
            return jpa.saveAndFlush(new ReceptionJpaEntity(reception)).toDomain();
        }
        ReceptionJpaEntity entity = jpa.findWithLines(reception.id())
                .orElseThrow(() -> OrderingErrors.receptionNotFound(reception.id()));
        if (!Objects.equals(entity.getVersion(), reception.version())) {
            throw new ObjectOptimisticLockingFailureException(ReceptionJpaEntity.class, reception.id());
        }
        entity.apply(reception);
        // Registrar lo recibido solo cambia las líneas: se fuerza la versión de la recepción para que una
        // conformidad y un cambio de línea simultáneos no se pisen (el segundo falla con 409).
        entityManager.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return jpa.saveAndFlush(entity).toDomain();
    }
}
