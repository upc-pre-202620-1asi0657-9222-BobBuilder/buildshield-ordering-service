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
 * de la foto del ticket ya subida por el cliente (por ejemplo, a la URL firmada que entregará el adaptador S3) y la
 * conserva tal cual. En producción lo reemplaza el adaptador de Amazon S3 (no implementado todavía: requiere las
 * credenciales de la cuenta AWS del equipo); el puerto {@link EvidenceStorage} no cambia.
 */
@Component
public class EvidenceStorageAdapter implements EvidenceStorage {

    public static final String INVALID_EVIDENCE_URL = "INVALID_EVIDENCE_URL";
    static final int MAX_LENGTH = 500;
    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageAdapter.class);

    @Override
    public String registerTicketPhoto(UUID ownerId, String ticketPhotoUrl) {
        if (ticketPhotoUrl == null || ticketPhotoUrl.isBlank()) {
            return null;
        }
        String url = ticketPhotoUrl.trim();
        if (url.length() > MAX_LENGTH || !isHttp(url)) {
            throw new ValidationException(INVALID_EVIDENCE_URL, "La foto del ticket debe ser una URL http o https",
                    List.of(new ErrorDetail("ticketPhotoUrl", "URL http(s), máximo 500 caracteres")));
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
