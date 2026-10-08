package pe.buildshield.ordering;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pe.buildshield.ordering.application.ComparisonView;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.DispatchQueryService;
import pe.buildshield.ordering.application.DispatchView;
import pe.buildshield.ordering.application.IncidentView;
import pe.buildshield.ordering.application.ManifestView;
import pe.buildshield.ordering.application.OrderCommandService;
import pe.buildshield.ordering.application.OrderQueryService;
import pe.buildshield.ordering.application.OrderView;
import pe.buildshield.ordering.application.ReceptionCommandService;
import pe.buildshield.ordering.application.ReceptionQueryService;
import pe.buildshield.ordering.application.ReceptionView;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.DispatchType;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.domain.model.order.OrderStatus;
import pe.buildshield.ordering.domain.model.reception.ReceptionStatus;
import pe.buildshield.ordering.shared.security.JwtTokenIssuer;
import pe.buildshield.ordering.shared.security.RevokedTokenStore;
import pe.buildshield.ordering.shared.tenant.TenantInfo;
import pe.buildshield.ordering.support.WebSliceTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz de autorización de toda la API de ordering: cada endpoint contra usuario anónimo y cada rol (los servicios
 * están simulados). Además verifica que ningún endpoint quede sin una regla explícita de {@code @PreAuthorize}.
 */
@WebMvcTest
@WebSliceTest
class RoleAuthorizationTest {

    private static final String ID = "00000000-0000-0000-0000-0000000000aa";
    private static final UUID ANY_ID = UUID.fromString(ID);
    private static final Set<String> ROLES = Set.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER");
    private static final Set<String> ALL = ROLES;
    private static final Set<String> WAREHOUSE = Set.of("ADMINISTRATOR", "WAREHOUSE_MANAGER");
    private static final Set<String> WORKSITE = Set.of("ADMINISTRATOR", "SITE_MANAGER");
    private static final Set<String> SITE_ONLY = Set.of("SITE_MANAGER");

    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("order", "{\"worksiteId\":\"" + ID + "\",\"warehouseId\":\"" + ID + "\",\"requiredDate\":\"2026-11-10\","
                    + "\"lines\":[{\"materialId\":\"" + ID + "\",\"quantity\":5}]}"),
            Map.entry("reject", "{\"reason\":\"Sin transporte\"}"),
            Map.entry("dispatch", "{\"orderId\":\"" + ID + "\",\"lines\":[{\"orderLineId\":\"" + ID + "\",\"quantity\":5}]}"),
            Map.entry("carrier", "{\"carrierName\":\"Rímac\",\"carrierDocument\":\"20555666777\",\"plate\":\"ABC-123\"}"),
            Map.entry("weighing", "{\"grossKg\":2550.5,\"tareKg\":1050.5}"),
            Map.entry("cancel", "{\"reason\":\"No llegó el camión\"}"),
            Map.entry("incident", "{\"type\":\"DELAY\",\"observation\":\"Tráfico\"}"),
            Map.entry("reception", "{\"dispatchId\":\"" + ID + "\"}"),
            Map.entry("line", "{\"receivedQty\":4}"));

    /** método, ruta, cuerpo, roles permitidos, estado de éxito. */
    private static final List<Object[]> ENDPOINTS = List.of(
            new Object[]{"POST", "/api/v1/orders", "order", SITE_ONLY, 201},
            new Object[]{"GET", "/api/v1/orders", null, ALL, 200},
            new Object[]{"GET", "/api/v1/orders/" + ID, null, ALL, 200},
            new Object[]{"POST", "/api/v1/orders/" + ID + "/approve", null, WAREHOUSE, 200},
            new Object[]{"POST", "/api/v1/orders/" + ID + "/reject", "reject", WAREHOUSE, 200},
            new Object[]{"POST", "/api/v1/dispatches", "dispatch", WAREHOUSE, 201},
            new Object[]{"GET", "/api/v1/dispatches", null, ALL, 200},
            new Object[]{"GET", "/api/v1/dispatches/" + ID, null, ALL, 200},
            new Object[]{"GET", "/api/v1/dispatches/by-manifest/MAN-20261103-7KQ2M9XA", null, ALL, 200},
            new Object[]{"PATCH", "/api/v1/dispatches/" + ID + "/carrier", "carrier", WAREHOUSE, 200},
            new Object[]{"POST", "/api/v1/dispatches/" + ID + "/departure-weighing", "weighing", WAREHOUSE, 200},
            new Object[]{"POST", "/api/v1/dispatches/" + ID + "/depart", null, WAREHOUSE, 200},
            new Object[]{"POST", "/api/v1/dispatches/" + ID + "/cancel", "cancel", WAREHOUSE, 200},
            new Object[]{"POST", "/api/v1/dispatches/" + ID + "/incidents", "incident", WAREHOUSE, 201},
            new Object[]{"GET", "/api/v1/dispatches/" + ID + "/incidents", null, ALL, 200},
            new Object[]{"GET", "/api/v1/dispatches/" + ID + "/manifest", null, ALL, 200},
            new Object[]{"POST", "/api/v1/receptions", "reception", WORKSITE, 201},
            new Object[]{"GET", "/api/v1/receptions/" + ID, null, ALL, 200},
            new Object[]{"PUT", "/api/v1/receptions/" + ID + "/lines/" + ID, "line", WORKSITE, 200},
            new Object[]{"GET", "/api/v1/receptions/" + ID + "/comparison", null, ALL, 200},
            new Object[]{"POST", "/api/v1/receptions/" + ID + "/partial", null, WORKSITE, 200},
            new Object[]{"POST", "/api/v1/receptions/" + ID + "/confirm", null, WORKSITE, 200},
            new Object[]{"POST", "/api/v1/receptions/" + ID + "/arrival-weighing", "weighing", WORKSITE, 201});

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    RevokedTokenStore revokedTokens;

    @MockitoBean
    OrderCommandService orderCommands;

    @MockitoBean
    OrderQueryService orderQueries;

    @MockitoBean
    DispatchCommandService dispatchCommands;

    @MockitoBean
    DispatchQueryService dispatchQueries;

    @MockitoBean
    ReceptionCommandService receptionCommands;

    @MockitoBean
    ReceptionQueryService receptionQueries;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (Object[] endpoint : ENDPOINTS) {
            cases.add(Arguments.of(endpoint[0], endpoint[1], endpoint[2], "ANONYMOUS", 401));
            for (String role : List.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER")) {
                @SuppressWarnings("unchecked")
                Set<String> allowed = (Set<String>) endpoint[3];
                cases.add(Arguments.of(endpoint[0], endpoint[1], endpoint[2], role,
                        allowed.contains(role) ? endpoint[4] : 403));
            }
        }
        return cases.stream();
    }

    @BeforeEach
    void stubServices() {
        Instant now = Instant.parse("2026-11-03T13:00:00Z");
        OrderView order = new OrderView(ANY_ID, ANY_ID, ANY_ID, ANY_ID, OrderStatus.REGISTERED, null, null, null, now,
                null, null, List.of(new OrderView.LineView(ANY_ID, ANY_ID, "CEM-001", "BAG", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN)));
        when(orderCommands.place(any())).thenReturn(order);
        when(orderCommands.approve(any())).thenReturn(order);
        when(orderCommands.reject(any(), any())).thenReturn(order);
        when(orderQueries.get(any())).thenReturn(order);
        when(orderQueries.list()).thenReturn(List.of(order));

        DispatchView dispatch = new DispatchView(ANY_ID, ANY_ID, ANY_ID, ANY_ID, DispatchType.COMPLETE,
                DispatchStatus.PREPARED, "MAN-20261103-7KQ2M9XA", now, null, null, null, null, 0, null, null,
                List.of(new DispatchView.LineView(ANY_ID, ANY_ID, ANY_ID, BigDecimal.TEN)));
        when(dispatchCommands.create(any())).thenReturn(dispatch);
        when(dispatchCommands.assignCarrier(any(), any(), any(), any())).thenReturn(dispatch);
        when(dispatchCommands.recordDepartureWeighing(any(), any(), any(), any())).thenReturn(dispatch);
        when(dispatchCommands.depart(any())).thenReturn(dispatch);
        when(dispatchCommands.cancel(any(), any())).thenReturn(dispatch);
        IncidentView incident = new IncidentView(ANY_ID, ANY_ID, IncidentType.DELAY, "Tráfico", ANY_ID, now);
        when(dispatchCommands.reportIncident(any(), any(), any())).thenReturn(incident);
        when(dispatchQueries.get(any())).thenReturn(dispatch);
        when(dispatchQueries.byManifest(any())).thenReturn(dispatch);
        when(dispatchQueries.list(any(), any())).thenReturn(List.of(dispatch));
        when(dispatchQueries.incidents(any())).thenReturn(List.of(incident));
        when(dispatchQueries.manifest(any())).thenReturn(new ManifestView("MAN-20261103-7KQ2M9XA", dispatch, ANY_ID,
                now, new ManifestView.Site(ANY_ID, "Torre", "Av. 1"), new ManifestView.Site(ANY_ID, "Central", "Av. 2"),
                List.of(), "iVBOR", "MAN-20261103-7KQ2M9XA"));

        ReceptionView reception = new ReceptionView(ANY_ID, ANY_ID, ANY_ID, ANY_ID, ANY_ID, ReceptionStatus.IN_PROGRESS,
                null, null, 0, null, null, List.of());
        when(receptionCommands.start(any(), any())).thenReturn(reception);
        when(receptionCommands.recordLine(any(), any(), any())).thenReturn(reception);
        when(receptionCommands.receivePartial(any())).thenReturn(reception);
        when(receptionCommands.confirm(any())).thenReturn(reception);
        when(receptionCommands.recordArrivalWeighing(any(), any(), any(), any())).thenReturn(
                new ReceptionView.ArrivalWeighingView(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("9"), null, null,
                        null, BigDecimal.ONE, null));
        when(receptionQueries.get(any())).thenReturn(reception);
        when(receptionQueries.comparison(any())).thenReturn(new ComparisonView(ANY_ID, ANY_ID, ANY_ID,
                ReceptionStatus.IN_PROGRESS, false, false, List.of()));
    }

    @ParameterizedTest(name = "{0} {1} como {3} -> {4}")
    @MethodSource("matrix")
    void endpoint_is_allowed_only_for_its_roles(String method, String path, String body, String who, int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "PATCH" -> patch(path);
            case "PUT" -> put(path);
            default -> post(path);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(BODIES.get(body));
        }
        if (!"ANONYMOUS".equals(who)) {
            String token = issuer.issue(new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), who)).value();
            request.header("Authorization", "Bearer " + token);
        }

        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    @Test
    void the_matrix_covers_every_endpoint() {
        long handlers = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("pe.buildshield.ordering"))
                .count();
        assertThat(ENDPOINTS).hasSize((int) handlers);
    }

    @Test
    void every_endpoint_declares_its_authorization_rule() {
        List<String> withoutRule = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("pe.buildshield.ordering"))
                .filter(handler -> !hasPreAuthorize(handler))
                .map(handler -> handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName())
                .toList();

        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
        assertThat(withoutRule).as("endpoints sin @PreAuthorize").isEmpty();
    }

    private static boolean hasPreAuthorize(HandlerMethod handler) {
        return handler.hasMethodAnnotation(PreAuthorize.class)
                || handler.getBeanType().isAnnotationPresent(PreAuthorize.class);
    }
}
