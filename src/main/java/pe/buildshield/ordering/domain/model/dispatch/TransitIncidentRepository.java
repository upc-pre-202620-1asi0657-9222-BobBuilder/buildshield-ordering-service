package pe.buildshield.ordering.domain.model.dispatch;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Incidencias en ruta de la organización del contexto. */
public interface TransitIncidentRepository {

    TransitIncident save(TransitIncident incident);

    /** Del despacho, ordenadas por {@code reportedAt}. */
    List<TransitIncident> findByDispatchId(UUID dispatchId);

    /** Cuántas incidencias tiene cada despacho (los que no tienen no aparecen). */
    Map<UUID, Integer> countByDispatchIds(Collection<UUID> dispatchIds);
}
