package pe.buildshield.ordering.domain.model.reception;

import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ResourceNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recepción en obra de un despacho en tránsito (US35, US37). Hay una sola por despacho. Lo recibido de
 * cada línea se registra de a poco (EnCurso); una recepción parcial (US38) ingresa al stock lo recibido hasta
 * ahora (RecibidaParcialmente) y la conformidad la cierra (Confirmada): después ya no cambia (QAS10). Admite un
 * pesaje de llegada (US29) mientras no está confirmada.
 */
public class Reception {

    public static final String RECEPTION_ALREADY_CONFIRMED = "RECEPTION_ALREADY_CONFIRMED";
    public static final String RECEPTION_INCOMPLETE = "RECEPTION_INCOMPLETE";
    public static final String RECEPTION_LINE_NOT_FOUND = "RECEPTION_LINE_NOT_FOUND";
    public static final String NOTHING_TO_RECEIVE = "NOTHING_TO_RECEIVE";
    public static final String ARRIVAL_WEIGHING_ALREADY_RECORDED = "ARRIVAL_WEIGHING_ALREADY_RECORDED";

    private final UUID id;
    private final UUID dispatchId;
    private final UUID orderId;
    private final UUID warehouseId;
    private final UUID worksiteId;
    private final String manifestCode;
    private final List<ReceptionLine> lines;
    private ReceptionStatus status;
    private int partialCount;
    private Instant lastPartialAt;
    private UUID confirmedBy;
    private Instant confirmedAt;
    private ArrivalWeighing arrivalWeighing;
    private final Long version;

    private Reception(UUID id, UUID dispatchId, UUID orderId, UUID warehouseId, UUID worksiteId, String manifestCode,
            List<ReceptionLine> lines, ReceptionStatus status, int partialCount, Instant lastPartialAt, UUID confirmedBy,
            Instant confirmedAt, ArrivalWeighing arrivalWeighing, Long version) {
        this.id = id;
        this.dispatchId = Objects.requireNonNull(dispatchId, "dispatchId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.worksiteId = Objects.requireNonNull(worksiteId, "worksiteId");
        this.manifestCode = Objects.requireNonNull(manifestCode, "manifestCode");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalStateException("Una recepción tiene al menos una línea");
        }
        if (partialCount < 0) {
            throw new IllegalStateException("La cantidad de parciales no puede ser negativa");
        }
        this.lines = new ArrayList<>(lines);
        this.status = Objects.requireNonNull(status, "status");
        this.partialCount = partialCount;
        this.lastPartialAt = lastPartialAt;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = confirmedAt;
        this.arrivalWeighing = arrivalWeighing;
        this.version = version;
    }

    /** Abre la recepción con una línea por cada línea del despacho, sin nada recibido todavía. */
    public static Reception start(UUID dispatchId, UUID orderId, UUID warehouseId, UUID worksiteId, String manifestCode,
            List<ReceptionLine> lines) {
        return new Reception(null, dispatchId, orderId, warehouseId, worksiteId, manifestCode, lines,
                ReceptionStatus.IN_PROGRESS, 0, null, null, null, null, null);
    }

    public static Reception restore(UUID id, UUID dispatchId, UUID orderId, UUID warehouseId, UUID worksiteId,
            String manifestCode, List<ReceptionLine> lines, ReceptionStatus status, int partialCount,
            Instant lastPartialAt, UUID confirmedBy, Instant confirmedAt, ArrivalWeighing arrivalWeighing,
            Long version) {
        return new Reception(Objects.requireNonNull(id, "id"), dispatchId, orderId, warehouseId, worksiteId,
                manifestCode, lines, status, partialCount, lastPartialAt, confirmedBy, confirmedAt, arrivalWeighing,
                version);
    }

    /** US35: registra lo recibido (acumulado) de una línea mientras la recepción no está confirmada. */
    public void recordReceived(UUID lineId, BigDecimal quantity) {
        requireOpen();
        line(lineId).recordReceived(quantity);
    }

    /**
     * US38: recepción parcial. Ingresa al stock lo recibido hasta ahora que aún no se había ingresado; la
     * recepción queda RecibidaParcialmente y el despacho sigue en tránsito.
     *
     * @return solo las líneas con algo nuevo (diferencia mayor que cero)
     * @throws ConflictException {@code NOTHING_TO_RECEIVE} si ninguna línea tiene algo nuevo
     */
    public List<LineDelta> receivePartial(Instant now) {
        requireOpen();
        boolean anything = lines.stream()
                .anyMatch(line -> line.isRecorded() && line.receivedQty().compareTo(line.stockedQty()) > 0);
        if (!anything) {
            throw new ConflictException(NOTHING_TO_RECEIVE,
                    "No hay nada nuevo que ingresar: registra lo recibido antes de la recepción parcial");
        }
        List<LineDelta> deltas = new ArrayList<>();
        for (ReceptionLine line : lines) {
            BigDecimal delta = line.stockReceived();
            if (delta.signum() > 0) {
                deltas.add(new LineDelta(line, delta));
            }
        }
        status = ReceptionStatus.PARTIALLY_RECEIVED;
        partialCount++;
        lastPartialAt = Objects.requireNonNull(now, "now");
        return List.copyOf(deltas);
    }

    /**
     * US37: conformidad. Todas las líneas deben tener lo recibido; ingresa lo que faltaba respecto de los parciales
     * y después la recepción no cambia.
     *
     * @return todas las líneas con la diferencia ingresada ahora (puede ser cero)
     */
    public List<LineDelta> confirm(UUID by, Instant now) {
        requireOpen();
        List<String> missing = lines.stream().filter(line -> !line.isRecorded())
                .map(line -> String.valueOf(line.id())).toList();
        if (!missing.isEmpty()) {
            throw new ConflictException(RECEPTION_INCOMPLETE, "Falta registrar lo recibido de " + missing.size()
                    + " línea(s)", missing.stream().map(line -> new ErrorDetail("lineId", line)).toList());
        }
        List<LineDelta> deltas = lines.stream().map(line -> new LineDelta(line, line.stockReceived())).toList();
        status = ReceptionStatus.CONFIRMED;
        confirmedBy = Objects.requireNonNull(by, "by");
        confirmedAt = Objects.requireNonNull(now, "now");
        return deltas;
    }

    /** US29: un solo pesaje de llegada, mientras la recepción no está confirmada. */
    public void recordArrivalWeighing(ArrivalWeighing weighing) {
        requireOpen();
        if (arrivalWeighing != null) {
            throw new ConflictException(ARRIVAL_WEIGHING_ALREADY_RECORDED, "La recepción ya tiene su pesaje de llegada");
        }
        arrivalWeighing = Objects.requireNonNull(weighing, "weighing");
    }

    public ReceptionLine line(UUID lineId) {
        return lines.stream().filter(line -> line.id() != null && line.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(RECEPTION_LINE_NOT_FOUND,
                        "La línea " + lineId + " no es de la recepción"));
    }

    private void requireOpen() {
        if (status == ReceptionStatus.CONFIRMED) {
            throw new ConflictException(RECEPTION_ALREADY_CONFIRMED, "La recepción ya fue confirmada y no cambia");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID dispatchId() {
        return dispatchId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID warehouseId() {
        return warehouseId;
    }

    public UUID worksiteId() {
        return worksiteId;
    }

    public String manifestCode() {
        return manifestCode;
    }

    public List<ReceptionLine> lines() {
        return List.copyOf(lines);
    }

    public ReceptionStatus status() {
        return status;
    }

    public int partialCount() {
        return partialCount;
    }

    public Instant lastPartialAt() {
        return lastPartialAt;
    }

    public UUID confirmedBy() {
        return confirmedBy;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Optional<ArrivalWeighing> arrivalWeighing() {
        return Optional.ofNullable(arrivalWeighing);
    }

    public Long version() {
        return version;
    }

    /** Lo ingresado al stock de una línea en una recepción parcial o en la conformidad. */
    public record LineDelta(ReceptionLine line, BigDecimal delta) {
    }
}
