package pe.buildshield.ordering.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.ordering.application.OrganizationDirectory.Material;
import pe.buildshield.ordering.domain.model.order.Order;
import pe.buildshield.ordering.domain.model.order.OrderLine;
import pe.buildshield.ordering.domain.model.order.OrderRepository;
import pe.buildshield.ordering.domain.model.reception.Reception;
import pe.buildshield.ordering.domain.model.reception.ReceptionLine;
import pe.buildshield.ordering.domain.model.reception.ReceptionRepository;
import pe.buildshield.ordering.domain.model.reception.Shrinkage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Consultas de recepciones: detalle y US35 cotejo. Las ven el administrador, los encargados de la obra de destino y
 * los del almacén de origen; para el resto no existen (404).
 */
@Service
public class ReceptionQueryService {

    private final ReceptionRepository receptions;
    private final OrderRepository orders;
    private final OrganizationDirectory directory;
    private final AccessPolicy access;

    public ReceptionQueryService(ReceptionRepository receptions, OrderRepository orders,
            OrganizationDirectory directory, AccessPolicy access) {
        this.receptions = receptions;
        this.orders = orders;
        this.directory = directory;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public ReceptionView get(UUID receptionId) {
        return ReceptionView.of(visible(receptionId));
    }

    /**
     * US35, QAS01: por material, lo solicitado (pedido), despachado, recibido, la diferencia, la merma y si está
     * dentro de la tolerancia del material. Usa un número fijo de consultas, sin importar cuántas líneas tenga.
     */
    @Transactional(readOnly = true)
    public ComparisonView comparison(UUID receptionId) {
        Reception reception = visible(receptionId);
        Order order = orders.findById(reception.orderId())
                .orElseThrow(() -> OrderingErrors.receptionNotFound(receptionId));
        Map<UUID, Material> materials = directory.findMaterials(
                reception.lines().stream().map(ReceptionLine::materialId).toList());
        List<ComparisonView.Line> lines = reception.lines().stream().map(line -> {
            Material material = materials.get(line.materialId());
            BigDecimal requested = order.lines().stream().filter(candidate -> Objects.equals(candidate.id(),
                    line.orderLineId())).findFirst().map(OrderLine::requested).orElse(null);
            BigDecimal tolerance = material == null ? BigDecimal.ZERO : material.tolerancePercent();
            Boolean within = line.isRecorded() ? Shrinkage.withinTolerance(line.shrinkagePercent(), tolerance) : null;
            return new ComparisonView.Line(line.id(), line.materialId(), material == null ? null : material.sku(),
                    material == null ? null : material.name(), material == null ? null : material.unit(), requested,
                    line.dispatchedQty(), line.receivedQty(), line.difference(), line.shrinkagePercent(), tolerance,
                    within);
        }).toList();
        boolean complete = lines.stream().allMatch(line -> line.received() != null);
        boolean allWithin = complete && lines.stream().allMatch(line -> Boolean.TRUE.equals(line.withinTolerance()));
        return new ComparisonView(reception.id(), reception.dispatchId(), reception.orderId(), reception.status(),
                complete, allWithin, lines);
    }

    private Reception visible(UUID receptionId) {
        return receptions.findById(receptionId)
                .filter(reception -> access.canWorkWorksite(reception.worksiteId())
                        || access.isAssigned(reception.warehouseId()))
                .orElseThrow(() -> OrderingErrors.receptionNotFound(receptionId));
    }
}
