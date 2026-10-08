package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface SpringDataTransitIncidentRepository extends JpaRepository<TransitIncidentJpaEntity, UUID> {

    List<TransitIncidentJpaEntity> findByDispatchIdOrderByReportedAtAsc(UUID dispatchId);

    /** Filas [dispatchId, cantidad]. */
    @Query("SELECT i.dispatchId, COUNT(i) FROM TransitIncidentJpaEntity i WHERE i.dispatchId IN :dispatchIds GROUP BY i.dispatchId")
    List<Object[]> countByDispatchIds(Collection<UUID> dispatchIds);
}
