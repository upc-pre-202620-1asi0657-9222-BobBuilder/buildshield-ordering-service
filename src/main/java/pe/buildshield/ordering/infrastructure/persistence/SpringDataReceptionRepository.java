package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface SpringDataReceptionRepository extends JpaRepository<ReceptionJpaEntity, UUID> {

    /** La recepción, su despacho y sus líneas con la línea del despacho, en una sola consulta (cotejo sin N+1). */
    @Query("""
            SELECT DISTINCT r FROM ReceptionJpaEntity r JOIN FETCH r.dispatch
            LEFT JOIN FETCH r.lines l LEFT JOIN FETCH l.dispatchLine WHERE r.id = :id""")
    Optional<ReceptionJpaEntity> findWithLines(UUID id);

    @Query("SELECT r.id FROM ReceptionJpaEntity r WHERE r.dispatch.id = :dispatchId")
    Optional<UUID> findIdByDispatchId(UUID dispatchId);
}
