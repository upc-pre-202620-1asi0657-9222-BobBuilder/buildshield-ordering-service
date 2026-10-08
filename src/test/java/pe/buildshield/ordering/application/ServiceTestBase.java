package pe.buildshield.ordering.application;

import org.junit.jupiter.api.AfterEach;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;
import pe.buildshield.ordering.support.MutableClock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Organización de prueba para los casos de uso: administradora Ana, encargadas de almacén Rosa (Almacén Central) y
 * María (Almacén Callao), encargados de obra Jorge (Torre Norte) y Lucía (Torre Sur). Servicios reales sobre dobles
 * en memoria ({@link Fakes}).
 */
abstract class ServiceTestBase {

    static final Instant NOW = Instant.parse("2026-11-03T13:00:00Z");
    static final UUID ORG = UUID.randomUUID();
    static final UUID ANA = UUID.randomUUID();
    static final UUID ROSA = UUID.randomUUID();
    static final UUID MARIA = UUID.randomUUID();
    static final UUID JORGE = UUID.randomUUID();
    static final UUID LUCIA = UUID.randomUUID();

    final MutableClock clock = new MutableClock(NOW);
    final Fakes.Orders orders = new Fakes.Orders();
    final Fakes.Dispatches dispatches = new Fakes.Dispatches();
    final Fakes.Incidents incidents = new Fakes.Incidents();
    final Fakes.Receptions receptions = new Fakes.Receptions();
    final Fakes.Directory directory = new Fakes.Directory();
    final Fakes.Outbox outbox = new Fakes.Outbox();
    final AccessPolicy access = new AccessPolicy(directory);
    final EvidenceStorage evidence = (owner, url) -> url == null || url.isBlank() ? null : url.trim();

    final OrderCommandService orderCommands = new OrderCommandService(orders, directory, access, outbox, clock);
    final OrderQueryService orderQueries = new OrderQueryService(orders, access);
    final DispatchCommandService dispatchCommands = new DispatchCommandService(dispatches, incidents, orders, access,
            evidence, outbox, clock, new Random(7));
    final DispatchQueryService dispatchQueries = new DispatchQueryService(dispatches, incidents, orders, directory,
            access, content -> new byte[]{1, 2, 3});
    final ReceptionCommandService receptionCommands = new ReceptionCommandService(receptions, dispatches, orders,
            directory, access, evidence, outbox, clock);
    final ReceptionQueryService receptionQueries = new ReceptionQueryService(receptions, orders, directory, access);

    final UUID torre = directory.worksite("Torre Norte");
    final UUID sur = directory.worksite("Torre Sur");
    final UUID central = directory.warehouse("Almacén Central", true);
    final UUID callao = directory.warehouse("Almacén Callao", true);
    final UUID cement = directory.material("CEM-001", "Cemento Portland tipo I", "BAG", "2.5", true);
    final UUID steel = directory.material("FIE-012", "Fierro corrugado 1/2", "UNIT", "1", true);

    {
        directory.assign(ROSA, central);
        directory.assign(MARIA, callao);
        directory.assign(JORGE, torre);
        directory.assign(LUCIA, sur);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    static void as(UUID user, String role) {
        TenantContext.set(new TenantInfo(ORG, user, role));
    }

    static void asAdmin() {
        as(ANA, "ADMINISTRATOR");
    }

    static void asRosa() {
        as(ROSA, "WAREHOUSE_MANAGER");
    }

    static void asMaria() {
        as(MARIA, "WAREHOUSE_MANAGER");
    }

    static void asJorge() {
        as(JORGE, "SITE_MANAGER");
    }

    static void asLucia() {
        as(LUCIA, "SITE_MANAGER");
    }

    static BigDecimal qty(String value) {
        return new BigDecimal(value);
    }

    /** Pedido Registrado de Jorge: 50 de cemento y 120 de fierro al Almacén Central. */
    OrderView placed() {
        asJorge();
        return orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, "Losa", null, List.of(
                new OrderCommandService.LineRequest(cement, qty("50")),
                new OrderCommandService.LineRequest(steel, qty("120")))));
    }

    /** Pedido aprobado por Rosa con el stock reservado (EnRevision). */
    OrderView approved() {
        OrderView order = placed();
        asRosa();
        orderCommands.approve(order.id());
        orderCommands.confirmReservation(order.id());
        return orderQueries.get(order.id());
    }

    UUID line(OrderView order, UUID material) {
        return order.lines().stream().filter(line -> line.materialId().equals(material)).findFirst().orElseThrow().id();
    }

    /** Rosa despacha del pedido las cantidades indicadas (cemento, fierro; null para omitir). */
    DispatchView dispatched(OrderView order, String cementQty, String steelQty) {
        asRosa();
        List<DispatchCommandService.LineRequest> lines = new java.util.ArrayList<>();
        if (cementQty != null) {
            lines.add(new DispatchCommandService.LineRequest(line(order, cement), qty(cementQty)));
        }
        if (steelQty != null) {
            lines.add(new DispatchCommandService.LineRequest(line(order, steel), qty(steelQty)));
        }
        return dispatchCommands.create(new DispatchCommandService.CreateDispatch(order.id(), lines));
    }

    /** Despacho con transportista, pesaje de salida (neto 1500 kg) y salido. */
    DispatchView departed(DispatchView dispatch) {
        asRosa();
        dispatchCommands.assignCarrier(dispatch.id(), "Transportes Rímac SAC", "20555666777", "ABC-123");
        dispatchCommands.recordDepartureWeighing(dispatch.id(), qty("2550.5"), qty("1050.5"), null);
        return dispatchCommands.depart(dispatch.id());
    }

    /** Recepción abierta por Jorge. */
    ReceptionView opened(DispatchView dispatch) {
        asJorge();
        return receptionCommands.start(dispatch.id(), null);
    }

    UUID receptionLine(ReceptionView reception, UUID material) {
        return reception.lines().stream().filter(line -> line.materialId().equals(material)).findFirst().orElseThrow()
                .id();
    }
}
