package pe.buildshield.ordering.domain.model.reception;

import java.util.Optional;
import java.util.UUID;

/** Recepciones de la organización del contexto. */
public interface ReceptionRepository {

    /** Con sus líneas y su pesaje de llegada. */
    Optional<Reception> findById(UUID id);

    /** Id de la recepción del despacho, si ya existe (hay a lo más una). */
    Optional<UUID> findIdByDispatchId(UUID dispatchId);

    Reception save(Reception reception);
}
