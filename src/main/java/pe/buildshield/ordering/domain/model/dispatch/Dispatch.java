package pe.buildshield.ordering.domain.model.dispatch;

import pe.buildshield.ordering.shared.error.ConflictException;
import pe.buildshield.ordering.shared.error.ErrorDetail;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Despacho de materiales de un pedido, del almacén de origen a la obra (US22, US23). Un pedido puede
 * tener varios despachos. Las transiciones las decide {@link DispatchState}; el identificador lo asigna
 * la persistencia.
 *
 * <p>Para salir (Preparado → EnTransito) necesita transportista (US30) y pesaje de salida (US27); ambos
 * solo se registran mientras está Preparado. Mientras está Preparado se puede anular con motivo (US25); en
 * tránsito se registran incidencias (US32).
 */
public class Dispatch {

    public static final String CARRIER_REQUIRED = "CARRIER_REQUIRED";
    public static final String DEPARTURE_WEIGHING_REQUIRED = "DEPARTURE_WEIGHING_REQUIRED";
    public static final String DEPARTURE_WEIGHING_ALREADY_RECORDED = "DEPARTURE_WEIGHING_ALREADY_RECORDED";
    public static final String INVALID_CANCELLATION_REASON = "INVALID_CANCELLATION_REASON";
    static final int MIN_REASON = 3;
    static final int MAX_REASON = 300;

    private final UUID id;
    private final UUID orderId;
    private final UUID warehouseId;
    private final UUID worksiteId;
    private final DispatchType type;
    private final ManifestCode manifestCode;
    private final List<DispatchLine> lines;
    private final Instant preparedAt;
    private DispatchState state;
    private Carrier carrier;
    private DepartureWeighing departureWeighing;
    private Instant departedAt;
    private Instant receivedAt;
    private Cancellation cancellation;
    private final Long version;

    private Dispatch(UUID id, UUID orderId, UUID warehouseId, UUID worksiteId, DispatchType type,
            ManifestCode manifestCode, List<DispatchLine> lines, Instant preparedAt, DispatchState state, Carrier carrier,
            DepartureWeighing departureWeighing, Instant departedAt, Instant receivedAt, Cancellation cancellation,
            Long version) {
        this.id = id;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.worksiteId = Objects.requireNonNull(worksiteId, "worksiteId");
        this.type = Objects.requireNonNull(type, "type");
        this.manifestCode = Objects.requireNonNull(manifestCode, "manifestCode");
        this.lines = List.copyOf(lines);
        this.preparedAt = Objects.requireNonNull(preparedAt, "preparedAt");
        this.state = Objects.requireNonNull(state, "state");
        this.carrier = carrier;
        this.departureWeighing = departureWeighing;
        this.departedAt = departedAt;
        this.receivedAt = receivedAt;
        this.cancellation = cancellation;
        this.version = version;
    }

    /** Prepara el despacho: al menos una línea y sin líneas de pedido repetidas. */
    public static Dispatch prepare(UUID orderId, UUID warehouseId, UUID worksiteId, DispatchType type,
            ManifestCode manifestCode, List<DispatchLine> lines, Instant now) {
        requireLines(lines);
        return new Dispatch(null, orderId, warehouseId, worksiteId, type, manifestCode, lines, now,
                new DispatchState.Prepared(), null, null, null, null, null, null);
    }

    public static Dispatch restore(UUID id, UUID orderId, UUID warehouseId, UUID worksiteId, DispatchType type,
            ManifestCode manifestCode, List<DispatchLine> lines, Instant preparedAt, DispatchStatus status,
            Carrier carrier, DepartureWeighing departureWeighing, Instant departedAt, Instant receivedAt,
            Cancellation cancellation, Long version) {
        return new Dispatch(Objects.requireNonNull(id, "id"), orderId, warehouseId, worksiteId, type, manifestCode,
                lines, preparedAt, DispatchState.of(status), carrier, departureWeighing, departedAt, receivedAt,
                cancellation, version);
    }

    /** Valida las líneas de un despacho nuevo: al menos una y sin repetir la línea de pedido. */
    public static void requireLines(List<DispatchLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("DISPATCH_WITHOUT_LINES", "El despacho debe tener al menos una línea",
                    List.of(new ErrorDetail("lines", "al menos una línea")));
        }
        Set<UUID> orderLines = new HashSet<>();
        for (DispatchLine line : lines) {
            if (!orderLines.add(line.orderLineId())) {
                throw new ValidationException("DUPLICATE_ORDER_LINE",
                        "La línea de pedido " + line.orderLineId() + " aparece más de una vez en el despacho",
                        List.of(new ErrorDetail("lines", "línea repetida: " + line.orderLineId())));
            }
        }
    }

    /** US30: registra o corrige el transportista mientras el despacho está Preparado. */
    public void assignCarrier(Carrier newCarrier) {
        requireEditable("registrar el transportista de");
        carrier = Objects.requireNonNull(newCarrier, "carrier");
    }

    /** US27: un solo pesaje de salida, mientras el despacho está Preparado. */
    public void recordDepartureWeighing(DepartureWeighing weighing) {
        requireEditable("pesar");
        if (departureWeighing != null) {
            throw new ConflictException(DEPARTURE_WEIGHING_ALREADY_RECORDED, "El despacho ya tiene su pesaje de salida");
        }
        departureWeighing = Objects.requireNonNull(weighing, "weighing");
    }

    /** Sale del almacén: exige transportista y pesaje de salida. Preparado → EnTransito. */
    public void depart(Instant now) {
        requireEditable("despachar");
        if (carrier == null) {
            throw new ConflictException(CARRIER_REQUIRED, "Registra el transportista antes de despachar");
        }
        if (departureWeighing == null) {
            throw new ConflictException(DEPARTURE_WEIGHING_REQUIRED, "Registra el pesaje de salida antes de despachar");
        }
        state = state.depart();
        departedAt = now;
    }

    /** US37: la obra confirmó la recepción. EnTransito → Recibido. */
    public void markReceived(Instant now) {
        state = state.receive();
        receivedAt = now;
    }

    /**
     * US25: anula un despacho Preparado con un motivo de 3 a 300 caracteres. Preparado → Anulado. Lo despachado
     * vuelve a lo pendiente del pedido (lo hace el caso de uso con {@link #quantitiesByOrderLine()}).
     */
    public void cancel(UUID by, String reason, Instant now) {
        String text = reason == null ? "" : reason.trim();
        if (text.length() < MIN_REASON || text.length() > MAX_REASON) {
            throw new ValidationException(INVALID_CANCELLATION_REASON, "El motivo de la anulación debe tener de 3 a 300 caracteres",
                    List.of(new ErrorDetail("reason", "de 3 a 300 caracteres")));
        }
        state = state.cancel();
        cancellation = new Cancellation(Objects.requireNonNull(now, "now"), Objects.requireNonNull(by, "by"), text);
    }

    /** US32: incidencia en ruta; solo mientras el despacho está EnTransito. */
    public TransitIncident reportIncident(IncidentType type, String observation, UUID by, Instant now) {
        if (!state.acceptsIncidents()) {
            throw new InvalidDispatchTransitionException(state.status(), "registrar una incidencia de");
        }
        return new TransitIncident(UUID.randomUUID(), Objects.requireNonNull(id, "id"), type, observation, by, now);
    }

    /** Cantidad de cada línea de pedido que lleva el despacho. */
    public Map<UUID, BigDecimal> quantitiesByOrderLine() {
        Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
        lines.forEach(line -> quantities.merge(line.orderLineId(), line.quantity(), BigDecimal::add));
        return quantities;
    }

    private void requireEditable(String operation) {
        if (!state.isEditable()) {
            throw new InvalidDispatchTransitionException(state.status(), operation);
        }
    }

    public UUID id() {
        return id;
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

    public DispatchType type() {
        return type;
    }

    public ManifestCode manifestCode() {
        return manifestCode;
    }

    public List<DispatchLine> lines() {
        return new ArrayList<>(lines);
    }

    public Instant preparedAt() {
        return preparedAt;
    }

    public DispatchStatus status() {
        return state.status();
    }

    public Optional<Carrier> carrier() {
        return Optional.ofNullable(carrier);
    }

    public Optional<DepartureWeighing> departureWeighing() {
        return Optional.ofNullable(departureWeighing);
    }

    public Instant departedAt() {
        return departedAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Optional<Cancellation> cancellation() {
        return Optional.ofNullable(cancellation);
    }

    public Long version() {
        return version;
    }

    /** Quién anuló el despacho, cuándo y por qué (US25). */
    public record Cancellation(Instant at, UUID by, String reason) {

        public Cancellation {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
