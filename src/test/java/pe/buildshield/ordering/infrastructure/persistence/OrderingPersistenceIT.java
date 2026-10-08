package pe.buildshield.ordering.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.DispatchQueryService;
import pe.buildshield.ordering.application.DispatchView;
import pe.buildshield.ordering.application.OrderCommandService;
import pe.buildshield.ordering.application.OrderQueryService;
import pe.buildshield.ordering.application.OrderView;
import pe.buildshield.ordering.application.ReceptionCommandService;
import pe.buildshield.ordering.application.ReceptionQueryService;
import pe.buildshield.ordering.application.ReceptionView;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;
import pe.buildshield.ordering.shared.tenant.TenantContext;
import pe.buildshield.ordering.shared.tenant.TenantInfo;
import pe.buildshield.ordering.support.OrderingIntegrationTest;
import pe.buildshield.ordering.support.TestDatabase;
import pe.buildshield.ordering.support.TestProjections;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistencia real (PostgreSQL ordering_it): el flujo completo de un pedido hasta su cierre guarda y vuelve a leer
 * pedidos, despachos (anulación, incidencias, pesaje), recepciones (parciales, pesaje de llegada) y el outbox.
 */
@OrderingIntegrationTest
class OrderingPersistenceIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrderCommandService orderCommands;

    @Autowired
    OrderQueryService orderQueries;

    @Autowired
    DispatchCommandService dispatchCommands;

    @Autowired
    DispatchQueryService dispatchQueries;

    @Autowired
    ReceptionCommandService receptionCommands;

    @Autowired
    ReceptionQueryService receptionQueries;

    private final UUID org = UUID.randomUUID();
    private final TenantInfo rosa = new TenantInfo(org, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private final TenantInfo jorge = new TenantInfo(org, UUID.randomUUID(), "SITE_MANAGER");
    private UUID torre;
    private UUID central;
    private UUID cement;
    private UUID steel;

    @BeforeEach
    void organization() {
        TestDatabase.reset(jdbc);
        torre = TestProjections.location(jdbc, org, "WORKSITE", "Torre Norte", true);
        central = TestProjections.location(jdbc, org, "WAREHOUSE", "Almacén Central", true);
        cement = TestProjections.material(jdbc, org, "CEM-001", "Cemento", "BAG", "2.5");
        steel = TestProjections.material(jdbc, org, "FIE-012", "Fierro", "UNIT", "1");
        TestProjections.assign(jdbc, org, rosa.userId(), central, "WAREHOUSE");
        TestProjections.assign(jdbc, org, jorge.userId(), torre, "WORKSITE");
    }

    private <T> T as(TenantInfo who, Supplier<T> action) {
        return TenantContext.callAs(who, action);
    }

    @Test
    void the_whole_flow_is_stored_and_read_back() {
        OrderView order = as(jorge, () -> orderCommands.place(new OrderCommandService.PlaceOrder(torre, central, "Losa",
                LocalDate.of(2026, 11, 10), List.of(new OrderCommandService.LineRequest(cement, new BigDecimal("50")),
                new OrderCommandService.LineRequest(steel, new BigDecimal("120"))))));
        assertThat(as(jorge, () -> orderQueries.get(order.id())).requiredDate()).isEqualTo(LocalDate.of(2026, 11, 10));

        as(rosa, () -> orderCommands.approve(order.id()));
        assertThat(as(rosa, () -> orderQueries.get(order.id())).status()).isEqualTo(OrderStatus.APPROVAL_PENDING);
        assertThat(as(new TenantInfo(org, TenantContext.SYSTEM_USER_ID, "SYSTEM"),
                () -> orderCommands.confirmReservation(order.id()))).isTrue();
        OrderView approved = as(rosa, () -> orderQueries.get(order.id()));
        assertThat(approved.status()).isEqualTo(OrderStatus.IN_REVIEW);
        UUID cementLine = lineOf(approved, cement);
        UUID steelLine = lineOf(approved, steel);

        DispatchView cancelled = as(rosa, () -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(
                order.id(), List.of(new DispatchCommandService.LineRequest(cementLine, new BigDecimal("10"))))));
        DispatchView cancelledView = as(rosa, () -> dispatchCommands.cancel(cancelled.id(), "Se reprograma"));
        assertThat(cancelledView.cancellationReason()).isEqualTo("Se reprograma");
        assertThat(as(rosa, () -> dispatchQueries.get(cancelled.id())).cancelledAt()).isNotNull();
        assertThat(as(rosa, () -> orderQueries.get(order.id())).status()).isEqualTo(OrderStatus.IN_REVIEW);

        DispatchView dispatch = as(rosa, () -> dispatchCommands.create(new DispatchCommandService.CreateDispatch(
                order.id(), List.of(new DispatchCommandService.LineRequest(cementLine, new BigDecimal("50")),
                new DispatchCommandService.LineRequest(steelLine, new BigDecimal("120"))))));
        as(rosa, () -> dispatchCommands.assignCarrier(dispatch.id(), "Transportes Rímac SAC", "20555666777", "ABC-123"));
        as(rosa, () -> dispatchCommands.recordDepartureWeighing(dispatch.id(), new BigDecimal("2550.5"),
                new BigDecimal("1050.5"), "https://evidencias/t.jpg"));
        as(rosa, () -> dispatchCommands.depart(dispatch.id()));
        as(rosa, () -> dispatchCommands.reportIncident(dispatch.id(), IncidentType.DELAY, "Tráfico"));
        as(rosa, () -> dispatchCommands.reportIncident(dispatch.id(), IncidentType.ROUTE_CHANGE, "Desvío"));
        DispatchView inTransit = as(jorge, () -> dispatchQueries.byManifest(dispatch.manifestCode()));
        assertThat(inTransit.status()).isEqualTo(DispatchStatus.IN_TRANSIT);
        assertThat(inTransit.incidentCount()).isEqualTo(2);
        assertThat(inTransit.departureWeighing().netKg()).isEqualByComparingTo("1500");
        assertThat(as(jorge, () -> dispatchQueries.list(order.id(), null))).hasSize(2);
        assertThat(as(jorge, () -> dispatchQueries.incidents(dispatch.id()))).hasSize(2);
        assertThat(as(jorge, () -> dispatchQueries.manifest(dispatch.id())).worksite().address())
                .isEqualTo("Av. Principal 100, Miraflores, Lima");

        ReceptionView reception = as(jorge, () -> receptionCommands.start(null, dispatch.manifestCode()));
        UUID receivedCement = reception.lines().stream().filter(line -> line.materialId().equals(cement)).findFirst()
                .orElseThrow().id();
        UUID receivedSteel = reception.lines().stream().filter(line -> line.materialId().equals(steel)).findFirst()
                .orElseThrow().id();
        as(jorge, () -> receptionCommands.recordArrivalWeighing(reception.id(), new BigDecimal("2530"),
                new BigDecimal("1050.5"), null));
        as(jorge, () -> receptionCommands.recordLine(reception.id(), receivedCement, new BigDecimal("20")));
        ReceptionView partial = as(jorge, () -> receptionCommands.receivePartial(reception.id()));
        assertThat(partial.status()).isEqualTo(ReceptionStatus.PARTIALLY_RECEIVED);
        assertThat(partial.partialCount()).isEqualTo(1);
        as(jorge, () -> receptionCommands.recordLine(reception.id(), receivedCement, new BigDecimal("49")));
        as(jorge, () -> receptionCommands.recordLine(reception.id(), receivedSteel, new BigDecimal("120")));
        ReceptionView read = as(jorge, () -> receptionQueries.get(reception.id()));
        assertThat(read.arrivalWeighing().differencePercent()).isEqualByComparingTo("1.37");
        assertThat(read.lines()).filteredOn(line -> line.id().equals(receivedCement)).singleElement()
                .satisfies(line -> assertThat(line.stockedQty()).isEqualByComparingTo("20"));
        assertThat(as(jorge, () -> receptionQueries.comparison(reception.id())).complete()).isTrue();

        ReceptionView confirmed = as(jorge, () -> receptionCommands.confirm(reception.id()));

        assertThat(confirmed.status()).isEqualTo(ReceptionStatus.CONFIRMED);
        assertThat(as(jorge, () -> orderQueries.get(order.id())).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(as(jorge, () -> dispatchQueries.get(dispatch.id())).status()).isEqualTo(DispatchStatus.RECEIVED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM arrival_weighings", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY occurred_at, event_type",
                String.class)).contains("OrderRegistered", "OrderApproved", "DispatchPrepared", "DispatchCancelled",
                "DispatchCarrierAssigned", "DispatchDepartureWeighed", "DispatchDeparted", "TransitIncidentReported",
                "ReceptionOpened", "ArrivalWeighed", "ReceptionPartiallyReceived", "ReceptionConfirmed", "OrderClosed");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT organization_id) FROM outbox_events", Long.class))
                .isEqualTo(1);
    }

    private static UUID lineOf(OrderView order, UUID material) {
        return order.lines().stream().filter(line -> line.materialId().equals(material)).findFirst().orElseThrow().id();
    }
}
