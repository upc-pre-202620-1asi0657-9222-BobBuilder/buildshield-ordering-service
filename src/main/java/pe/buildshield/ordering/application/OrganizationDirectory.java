package pe.buildshield.ordering.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Puerto de lectura de las copias locales de organization (obras, almacenes, materiales y asignaciones), que
 * llegan por eventos de {@code buildshield.organization}. Reemplaza las llamadas síncronas del Core a
 * {@code OrganizationContextFacade}. Todo filtra por la organización del {@code TenantContext}.
 */
public interface OrganizationDirectory {

    String WORKSITE = "WORKSITE";
    String WAREHOUSE = "WAREHOUSE";

    Optional<Location> findLocation(UUID locationId);

    default Optional<Location> findWorksite(UUID worksiteId) {
        return findLocation(worksiteId).filter(location -> WORKSITE.equals(location.type()));
    }

    default Optional<Location> findWarehouse(UUID warehouseId) {
        return findLocation(warehouseId).filter(location -> WAREHOUSE.equals(location.type()));
    }

    Optional<Material> findMaterial(UUID materialId);

    /** Los materiales encontrados, por id (los que no existen no aparecen). */
    Map<UUID, Material> findMaterials(Collection<UUID> materialIds);

    /** ¿El usuario tiene una asignación activa a esa obra o almacén? */
    boolean isAssigned(UUID userId, UUID locationId);

    /** Obras y almacenes con asignación activa del usuario. */
    Set<UUID> assignedSites(UUID userId);

    /**
     * Obra o almacén.
     *
     * @param type WORKSITE o WAREHOUSE
     */
    record Location(UUID id, String type, String name, String address, String district, String city, boolean active) {

        /** Dirección, distrito y ciudad unidos con coma (los vacíos se omiten), como el manifiesto del Core. */
        public String fullAddress() {
            return Stream.of(address, district, city).filter(part -> part != null && !part.isBlank())
                    .collect(Collectors.joining(", "));
        }
    }

    /** Material del catálogo con su tolerancia de merma en %. */
    record Material(UUID id, String sku, String name, String unit, BigDecimal tolerancePercent, boolean active) {
    }
}
