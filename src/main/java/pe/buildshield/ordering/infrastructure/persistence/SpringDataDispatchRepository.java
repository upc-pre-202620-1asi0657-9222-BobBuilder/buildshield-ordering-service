package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataDispatchRepository extends JpaRepository<DispatchJpaEntity, UUID> {

    List<DispatchJpaEntity> findAllByOrderByPreparedAtDesc();

    Optional<DispatchJpaEntity> findByManifestCode(String manifestCode);

    List<DispatchJpaEntity> findByOrderIdOrderByPreparedAtAsc(UUID orderId);

    @Query("SELECT d FROM DispatchJpaEntity d WHERE d.warehouseId IN :sites OR d.worksiteId IN :sites ORDER BY d.preparedAt DESC")
    List<DispatchJpaEntity> findBySites(Collection<UUID> sites);
}
