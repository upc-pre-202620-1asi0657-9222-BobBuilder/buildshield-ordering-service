package pe.buildshield.ordering.application;

import java.util.UUID;

/**
 * Puerto de la evidencia (foto del ticket de balanza del pesaje de salida o de llegada). En la arquitectura
 * objetivo lo implementa un adaptador de Amazon S3; en local y en pruebas, {@code EvidenceStorageAdapter}, que solo
 * valida y conserva la referencia. El dominio guarda únicamente la URL devuelta.
 */
public interface EvidenceStorage {

    /**
     * Registra la foto del ticket y devuelve la URL con la que se consultará ({@code null} si no se envió).
     *
     * @param ownerId despacho o recepción al que pertenece la evidencia
     * @throws pe.buildshield.ordering.shared.error.ValidationException si la referencia no es válida
     */
    String registerTicketPhoto(UUID ownerId, String ticketPhotoUrl);
}
