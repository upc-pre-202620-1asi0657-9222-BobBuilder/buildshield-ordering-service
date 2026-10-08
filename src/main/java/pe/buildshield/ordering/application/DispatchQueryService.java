package pe.buildshield.ordering.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrganizationDirectory.Location;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.dispatch.Dispatch;
import pe.buildshield.ordering.domain.model.dispatch.DispatchLine;
import pe.buildshield.ordering.domain.model.dispatch.DispatchRepository;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.TransitIncidentRepository;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderRepository;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consultas de despachos: detalle, lista (US31 seguimiento con {@code incidentCount}), manifiesto con QR (US24),
 * consulta por código de manifiesto (US26) e incidencias (US32). Ven un despacho el administrador y los encargados
 * asignados al almacén de origen o a la obra de destino; para el resto no existe (404).
 */
@Service
public class DispatchQueryService {

    private final DispatchRepository dispatches;
    private final TransitIncidentRepository incidents;
    private final OrderRepository orders;
    private final OrganizationDirectory directory;
    private final AccessPolicy access;
    private final ManifestQrCode qrCode;

    public DispatchQueryService(DispatchRepository dispatches, TransitIncidentRepository incidents,
            OrderRepository orders, OrganizationDirectory directory, AccessPolicy access, ManifestQrCode qrCode) {
        this.dispatches = dispatches;
        this.incidents = incidents;
        this.orders = orders;
        this.directory = directory;
        this.access = access;
        this.qrCode = qrCode;
    }

    @Transactional(readOnly = true)
    public DispatchView get(UUID dispatchId) {
        return view(visible(dispatchId));
    }

    /** US26: por código de manifiesto; código inexistente, de otra organización o no visible: 404. */
    @Transactional(readOnly = true)
    public DispatchView byManifest(String manifestCode) {
        return dispatches.findByManifestCode(manifestCode == null ? "" : manifestCode.trim().toUpperCase())
                .filter(this::canSee)
                .map(this::view)
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(manifestCode));
    }

    /** Despachos visibles para el usuario, opcionalmente de un pedido y en un estado. */
    @Transactional(readOnly = true)
    public List<DispatchView> list(UUID orderId, DispatchStatus status) {
        List<Dispatch> candidates = access.isAdministrator() ? dispatches.findAll()
                : dispatches.findBySites(access.assignedSites());
        List<Dispatch> visible = candidates.stream()
                .filter(dispatch -> orderId == null || dispatch.orderId().equals(orderId))
                .filter(dispatch -> status == null || dispatch.status() == status)
                .toList();
        Map<UUID, Integer> counts = incidents.countByDispatchIds(visible.stream().map(Dispatch::id).toList());
        return visible.stream().map(dispatch -> DispatchView.of(dispatch, counts.getOrDefault(dispatch.id(), 0)))
                .toList();
    }

    /** US32: incidencias del despacho, ordenadas por {@code reportedAt}. */
    @Transactional(readOnly = true)
    public List<IncidentView> incidents(UUID dispatchId) {
        Dispatch dispatch = visible(dispatchId);
        return incidents.findByDispatchId(dispatch.id()).stream().map(IncidentView::of).toList();
    }

    /** US24: manifiesto con pedido, obra, almacén, materiales, transportista, pesaje y el QR del código. */
    @Transactional(readOnly = true)
    public ManifestView manifest(UUID dispatchId) {
        Dispatch dispatch = visible(dispatchId);
        Location worksite = directory.findWorksite(dispatch.worksiteId())
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(dispatchId));
        Location warehouse = directory.findWarehouse(dispatch.warehouseId())
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(dispatchId));
        Instant orderPlacedAt = orders.findById(dispatch.orderId()).map(Order::placedAt).orElse(null);
        Map<UUID, Material> materials = directory.findMaterials(
                dispatch.lines().stream().map(DispatchLine::materialId).toList());
        List<ManifestView.Line> lines = dispatch.lines().stream().map(line -> {
            Material material = materials.get(line.materialId());
            return new ManifestView.Line(line.materialId(), material == null ? null : material.sku(),
                    material == null ? null : material.name(), material == null ? null : material.unit(),
                    line.quantity());
        }).toList();
        String code = dispatch.manifestCode().value();
        return new ManifestView(code, view(dispatch), dispatch.orderId(), orderPlacedAt,
                new ManifestView.Site(worksite.id(), worksite.name(), worksite.fullAddress()),
                new ManifestView.Site(warehouse.id(), warehouse.name(), warehouse.address()), lines,
                Base64.getEncoder().encodeToString(qrCode.png(code)), code);
    }

    private Dispatch visible(UUID dispatchId) {
        return dispatches.findById(dispatchId).filter(this::canSee)
                .orElseThrow(() -> OrderingErrors.dispatchNotFound(dispatchId));
    }

    private boolean canSee(Dispatch dispatch) {
        return access.canSee(dispatch.warehouseId(), dispatch.worksiteId());
    }

    private DispatchView view(Dispatch dispatch) {
        return DispatchView.of(dispatch, incidents.countByDispatchIds(List.of(dispatch.id()))
                .getOrDefault(dispatch.id(), 0));
    }
}
