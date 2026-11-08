package pe.buildshield.ordering.infrastructure.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.AccessPolicy;
import pe.buildshield.ordering.application.OrganizationDirectory;

/**
 * Reproducción de una conformidad (US37) o de una recepción parcial (US38): el actor debe seguir siendo
 * administrador o encargado asignado a la obra de destino (la de la respuesta).
 */
@Component
public class ReceptionReplayAuthorizer extends SiteReplayAuthorizer {

    public ReceptionReplayAuthorizer(OrganizationDirectory directory, ObjectMapper json) {
        super(directory, json);
    }

    @Override
    public boolean supports(String path) {
        return path.matches("/api/v1/receptions/[^/]+/(confirm|partial)");
    }

    @Override
    public boolean allowed(String path, byte[] responseBody) {
        return isAdministrator() || assignedTo(path, responseBody, AccessPolicy.SITE_MANAGER, "worksiteId");
    }
}
