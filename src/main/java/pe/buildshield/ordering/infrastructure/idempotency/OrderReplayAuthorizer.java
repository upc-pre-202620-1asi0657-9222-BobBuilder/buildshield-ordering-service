package pe.buildshield.ordering.infrastructure.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pe.buildshield.ordering.application.AccessPolicy;
import pe.buildshield.ordering.application.OrganizationDirectory;

/**
 * Reproducción de crear, aprobar o rechazar un pedido: crear exige un encargado de obra asignado todavía a la obra
 * del pedido; aprobar y rechazar, el administrador o un encargado de almacén asignado al almacén de origen.
 */
@Component
public class OrderReplayAuthorizer extends SiteReplayAuthorizer {

    private static final String ORDERS = "/api/v1/orders";

    public OrderReplayAuthorizer(OrganizationDirectory directory, ObjectMapper json) {
        super(directory, json);
    }

    @Override
    public boolean supports(String path) {
        return path.equals(ORDERS) || path.matches("/api/v1/orders/[^/]+/(approve|reject)");
    }

    @Override
    public boolean allowed(String path, byte[] responseBody) {
        if (path.equals(ORDERS)) {
            return assignedTo(path, responseBody, AccessPolicy.SITE_MANAGER, "worksiteId");
        }
        return isAdministrator() || assignedTo(path, responseBody, AccessPolicy.WAREHOUSE_MANAGER, "warehouseId");
    }
}
