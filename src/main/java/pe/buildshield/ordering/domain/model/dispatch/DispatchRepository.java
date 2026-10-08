package pe.buildshield.ordering.domain.model.dispatch;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Despachos de la organización del contexto. */
public interface DispatchRepository {

    Optional<Dispatch> findById(UUID id);

    /** US26, US34: por el código del manifiesto. */
    Optional<Dispatch> findByManifestCode(String manifestCode);

    /** Todos, del más reciente al más antiguo. */
    List<Dispatch> findAll();

    /** Despachos cuyo almacén de origen o cuya obra de destino está entre {@code siteIds}. */
    List<Dispatch> findBySites(Collection<UUID> siteIds);

    /** Todos los despachos de un pedido. */
    List<Dispatch> findByOrderId(UUID orderId);

    Dispatch save(Dispatch dispatch);
}
