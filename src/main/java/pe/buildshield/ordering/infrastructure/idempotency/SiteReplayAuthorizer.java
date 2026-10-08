package pe.buildshield.ordering.infrastructure.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import pe.buildshield.ordering.application.AccessPolicy;
import pe.buildshield.ordering.application.OrganizationDirectory;
import pe.buildshield.ordering.shared.idempotency.ReplayAuthorizer;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;

import java.io.IOException;
import java.util.UUID;

/**
 * Base de los autorizadores de reproducción: antes de devolver la respuesta guardada de una operación idempotente,
 * revisa que el actor siga teniendo permiso sobre la obra o el almacén de esa respuesta, con las asignaciones
 * vigentes de la copia local ({@code assignment_projection}).
 */
abstract class SiteReplayAuthorizer implements ReplayAuthorizer {

    private final OrganizationDirectory directory;
    private final ObjectMapper json;

    SiteReplayAuthorizer(OrganizationDirectory directory, ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    /** ¿El actor (con {@code role}) sigue asignado al lugar del campo {@code siteField} de la respuesta? */
    boolean assignedTo(String path, byte[] responseBody, String role, String siteField) {
        TenantInfo actor = TenantContext.require();
        if (!role.equals(actor.role())) {
            return false;
        }
        try {
            UUID site = UUID.fromString(json.readTree(responseBody).path(siteField).asText());
            return directory.isAssigned(actor.userId(), site);
        } catch (IOException | IllegalArgumentException ex) {
            return false;
        }
    }

    static boolean isAdministrator() {
        return AccessPolicy.ADMINISTRATOR.equals(TenantContext.require().role());
    }
}
