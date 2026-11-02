package pe.buildshield.ordering.infrastructure.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.EvidenceStorage;
import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;

/**
 * {@code EvidenceStorageAdapter} del C4 (v4), versión local: no sube archivos ni llama a AWS. Acepta una URL http(s)
 * de la foto del ticket o de la incidencia ya subida por el cliente (por ejemplo, a la URL firmada que entregará el adaptador S3) y la
 * conserva tal cual. En producción lo reemplaza el adaptador de Amazon S3 (no implementado todavía: requiere las
 * credenciales de la cuenta AWS del equipo); el puerto {@link EvidenceStorage} no cambia.
 */
@Component
public class EvidenceStorageAdapter implements EvidenceStorage {

    public static final String INVALID_EVIDENCE_URL = "INVALID_EVIDENCE_URL";
    static final int TICKET_MAX_LENGTH = 500;
    static final int INCIDENT_MAX_LENGTH = 255;
    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageAdapter.class);

    @Override
    public String registerTicketPhoto(UUID ownerId, String ticketPhotoUrl) {
        return register(ownerId, ticketPhotoUrl, "La foto del ticket", "ticketPhotoUrl", TICKET_MAX_LENGTH);
    }

    @Override
    public String registerIncidentPhoto(UUID dispatchId, String photoUrl) {
        return register(dispatchId, photoUrl, "La foto de la incidencia", "photoUrl", INCIDENT_MAX_LENGTH);
    }

    private static String register(UUID ownerId, String photoUrl, String label, String field, int maxLength) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return null;
        }
        String url = photoUrl.trim();
        if (url.length() > maxLength || !isHttp(url)) {
            throw new ValidationException(INVALID_EVIDENCE_URL, label + " debe ser una URL http o https",
                    List.of(new ErrorDetail(field, "URL http(s), máximo " + maxLength + " caracteres")));
        }
        log.debug("Evidencia de {} registrada localmente", ownerId);
        return url;
    }

    private static boolean isHttp(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
