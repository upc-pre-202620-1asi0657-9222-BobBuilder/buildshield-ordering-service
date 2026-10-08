package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncident;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncidentRepository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code TransitIncidentRepository} con Spring Data JPA; cuenta las incidencias de varios despachos en una consulta. */
@Repository
@Transactional(readOnly = true)
class JpaTransitIncidentRepository implements TransitIncidentRepository {

    private final SpringDataTransitIncidentRepository jpa;

    JpaTransitIncidentRepository(SpringDataTransitIncidentRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public TransitIncident save(TransitIncident incident) {
        return jpa.saveAndFlush(new TransitIncidentJpaEntity(incident)).toDomain();
    }

    @Override
    public List<TransitIncident> findByDispatchId(UUID dispatchId) {
        return jpa.findByDispatchIdOrderByReportedAtAsc(dispatchId).stream().map(TransitIncidentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Map<UUID, Integer> countByDispatchIds(Collection<UUID> dispatchIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (dispatchIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : jpa.countByDispatchIds(dispatchIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }
}
