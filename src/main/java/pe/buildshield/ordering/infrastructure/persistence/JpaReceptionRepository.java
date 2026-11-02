package pe.buildshield.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrderingErrors;
import pe.buildshield.ordering.application.OrganizationDirectory;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionRepository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ReceptionRepository} con Spring Data JPA; el filtro por organización lo pone Hibernate ({@code @TenantId}). Un
 * pesaje de llegada anterior a V3 no guardó su tolerancia: se completa con la menor actual de los materiales, de
 * {@code material_projection}.
 */
@Repository
@Transactional(readOnly = true)
class JpaReceptionRepository implements ReceptionRepository {

    private final SpringDataReceptionRepository jpa;
    private final OrganizationDirectory directory;
    private final EntityManager entityManager;

    JpaReceptionRepository(SpringDataReceptionRepository jpa, OrganizationDirectory directory,
            EntityManager entityManager) {
        this.jpa = jpa;
        this.directory = directory;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Reception> findById(UUID id) {
        return jpa.findWithLines(id).map(this::toDomain);
    }

    @Override
    public Optional<UUID> findIdByDispatchId(UUID dispatchId) {
        return jpa.findIdByDispatchId(dispatchId);
    }

    @Override
    @Transactional
    public Reception save(Reception reception) {
        if (reception.id() == null) {
            DispatchJpaEntity dispatch = entityManager.getReference(DispatchJpaEntity.class, reception.dispatchId());
            return toDomain(jpa.saveAndFlush(new ReceptionJpaEntity(reception, dispatch,
                    lineId -> entityManager.getReference(DispatchLineJpaEntity.class, lineId))));
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
        return toDomain(jpa.saveAndFlush(entity));
    }

    private Reception toDomain(ReceptionJpaEntity entity) {
        if (!entity.needsCurrentTolerance()) {
            return entity.toDomain(null);
        }
        BigDecimal tolerance = directory.findMaterials(entity.materialIds()).values().stream()
                .map(Material::tolerancePercent).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return entity.toDomain(tolerance);
    }
}
