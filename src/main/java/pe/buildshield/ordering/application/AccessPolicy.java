package pe.buildshield.ordering.application;

import org.springframework.stereotype.Component;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;

import java.util.Set;
import java.util.UUID;

/**
 * Quién puede ver o trabajar un pedido, despacho o recepción, según el rol del token y las asignaciones activas
 * (copia local {@code assignment_projection}). Lo que el usuario no puede ver responde 404, no 403.
 *
 * <ul>
 *   <li>Administrador: todo lo de su organización.</li>
 *   <li>Encargado de almacén: lo que sale de sus almacenes asignados.</li>
 *   <li>Encargado de obra: lo que va a sus obras asignadas.</li>
 * </ul>
 */
@Component
public class AccessPolicy {

    public static final String ADMINISTRATOR = "ADMINISTRATOR";
    public static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    public static final String SITE_MANAGER = "SITE_MANAGER";

    private final OrganizationDirectory directory;

    public AccessPolicy(OrganizationDirectory directory) {
        this.directory = directory;
    }

    public TenantInfo requester() {
        return TenantContext.require();
    }

    public boolean isAdministrator() {
        return ADMINISTRATOR.equals(requester().role());
    }

    public boolean isAssigned(UUID siteId) {
        return directory.isAssigned(requester().userId(), siteId);
    }

    /** Obras y almacenes asignados al usuario. */
    public Set<UUID> assignedSites() {
        return directory.assignedSites(requester().userId());
    }

    /** Ve el pedido, despacho o recepción: administrador o asignado al almacén de origen o a la obra de destino. */
    public boolean canSee(UUID warehouseId, UUID worksiteId) {
        return isAdministrator() || isAssigned(warehouseId) || isAssigned(worksiteId);
    }

    /** Aprueba o rechaza pedidos de ese almacén: administrador o asignado al almacén. */
    public boolean canDecide(UUID warehouseId) {
        return isAdministrator() || isAssigned(warehouseId);
    }

    /** Prepara, completa, despacha y anula: administrador o encargado de almacén asignado al almacén de origen. */
    public boolean canManageWarehouse(UUID warehouseId) {
        return isAdministrator() || (WAREHOUSE_MANAGER.equals(requester().role()) && isAssigned(warehouseId));
    }

    /** Recibe en obra: administrador o encargado de obra asignado a la obra de destino. */
    public boolean canWorkWorksite(UUID worksiteId) {
        return isAdministrator() || (SITE_MANAGER.equals(requester().role()) && isAssigned(worksiteId));
    }
}
