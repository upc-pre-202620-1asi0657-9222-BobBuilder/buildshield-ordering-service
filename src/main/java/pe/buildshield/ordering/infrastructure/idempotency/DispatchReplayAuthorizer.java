package pe.buildshield.ordering.infrastructure.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.AccessPolicy;
import pe.buildshield.ordering.application.OrganizationDirectory;

/**
 * Reproducción de crear o hacer salir un despacho: el actor debe seguir siendo administrador o encargado asignado al
 * almacén de origen (el de la respuesta).
 */
@Component
public class DispatchReplayAuthorizer extends SiteReplayAuthorizer {

    public DispatchReplayAuthorizer(OrganizationDirectory directory, ObjectMapper json) {
        super(directory, json);
    }

    @Override
    public boolean supports(String path) {
        return path.equals("/api/v1/dispatches") || path.matches("/api/v1/dispatches/[^/]+/depart");
    }

    @Override
    public boolean allowed(String path, byte[] responseBody) {
        return isAdministrator() || assignedTo(path, responseBody, AccessPolicy.WAREHOUSE_MANAGER, "warehouseId");
    }
}
