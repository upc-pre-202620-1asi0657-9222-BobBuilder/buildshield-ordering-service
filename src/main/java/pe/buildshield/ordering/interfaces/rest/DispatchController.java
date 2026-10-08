package pe.buildshield.ordering.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.ordering.application.DispatchCommandService;
import pe.buildshield.ordering.application.DispatchQueryService;
import pe.buildshield.ordering.application.DispatchView;
import pe.buildshield.ordering.application.DispatchView.CarrierView;
import pe.buildshield.ordering.application.DispatchView.LineView;
import pe.buildshield.ordering.application.DispatchView.WeighingView;
import pe.buildshield.ordering.application.IncidentView;
import pe.buildshield.ordering.application.ManifestView;
import pe.buildshield.ordering.domain.model.dispatch.DispatchStatus;
import pe.buildshield.ordering.domain.model.dispatch.IncidentType;
import pe.buildshield.ordering.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dispatches")
@Tag(name = "Despachos", description = "US22 generar, US23 partición, US24 manifiesto, US25 anular, US26 consulta por "
        + "código, US27 pesaje de salida, US30 transportista, US31 seguimiento, US32 incidencias")
@SecurityRequirement(name = "bearer")
class DispatchController {

    private final DispatchCommandService commands;
    private final DispatchQueryService queries;

    DispatchController(DispatchCommandService commands, DispatchQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Generar un despacho de un pedido aprobado",
            description = "El administrador o el encargado del almacén de origen despacha una o varias líneas del pedido "
                    + "(US22). Un pedido admite varios despachos (US23): cada cantidad es mayor que cero y no supera lo "
                    + "pendiente de su línea. Lo despachado se suma al pedido, que pasa a ParcialmenteAtendido o Atendido. "
                    + "Se genera el código único del manifiesto. Queda Preparado y se publica DispatchPrepared. Requiere "
                    + "Idempotency-Key.")
    @ApiResponse(responseCode = "201", description = "Despacho preparado",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_PREPARED)))
    @ApiResponse(responseCode = "400", description = "Sin líneas, línea repetida o que no es del pedido, cantidad no positiva",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El pedido no existe o el usuario no es del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Pedido no aprobado o aún reservando stock (ORDER_NOT_DISPATCHABLE), "
            + "cantidad mayor que lo pendiente (DISPATCH_EXCEEDS_PENDING) o despacho simultáneo del mismo pedido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<DispatchResource> create(@Valid @RequestBody CreateDispatchRequest request) {
        DispatchView created = commands.create(new DispatchCommandService.CreateDispatch(request.orderId(),
                request.lines().stream()
                        .map(line -> new DispatchCommandService.LineRequest(line.orderLineId(), line.quantity()))
                        .toList()));
        return ResponseEntity.created(URI.create("/api/v1/dispatches/" + created.id())).body(DispatchResource.of(created));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar despachos",
            description = "Administrador: todos; encargado de almacén: los de sus almacenes; encargado de obra: los que "
                    + "van a sus obras. Se filtran por pedido y por estado (US31: status=IN_TRANSIT para el seguimiento). "
                    + "Cada despacho trae incidentCount.")
    @ApiResponse(responseCode = "200", description = "Despachos visibles para el usuario",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = DispatchResource.class)),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCHES)))
    @ApiResponse(responseCode = "400", description = "Estado o id con formato incorrecto",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    List<DispatchResource> list(
            @Parameter(description = "Id del pedido; si se omite, todos", example = DispatchApiExamples.ORDER_ID)
            @RequestParam(required = false) UUID orderId,
            @Parameter(description = "Estado: PREPARED, IN_TRANSIT, RECEIVED o CANCELLED", example = "IN_TRANSIT")
            @RequestParam(required = false) DispatchStatus status) {
        return queries.list(orderId, status).stream().map(DispatchResource::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar un despacho",
            description = "Estado, líneas, transportista, pesaje de salida, anulación e incidencias. Lo ven el "
                    + "administrador y los encargados del almacén de origen y de la obra de destino.")
    @ApiResponse(responseCode = "200", description = "Despacho",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_IN_TRANSIT)))
    @ApiResponse(responseCode = "404", description = "No existe, es de otra organización o no corresponde al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DispatchResource get(@Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID)
            @PathVariable UUID id) {
        return DispatchResource.of(queries.get(id));
    }

    @GetMapping("/by-manifest/{code}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar un despacho por el código del manifiesto",
            description = "US26: el código que codifica el QR del manifiesto. Cualquier rol de la organización; el "
                    + "encargado de obra solo si la obra de destino está asignada a él y el de almacén solo si el "
                    + "almacén de origen lo está. Código inexistente, de otra organización o no visible: 404 (no revela "
                    + "si existe).")
    @ApiResponse(responseCode = "200", description = "Despacho",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_IN_TRANSIT)))
    @ApiResponse(responseCode = "404", description = "No existe, es de otra organización o no corresponde al usuario "
            + "(DISPATCH_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DispatchResource byManifest(@Parameter(description = "Código del manifiesto", example = DispatchApiExamples.MANIFEST_CODE)
            @PathVariable String code) {
        return DispatchResource.of(queries.byManifest(code));
    }

    @PatchMapping("/{id}/carrier")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Registrar el transportista",
            description = "US30: nombre, documento y placa del vehículo. Solo mientras el despacho está Preparado; se "
                    + "puede corregir hasta que salga. Se publica DispatchCarrierAssigned.")
    @ApiResponse(responseCode = "200", description = "Transportista registrado",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_WITH_CARRIER)))
    @ApiResponse(responseCode = "400", description = "Nombre, documento o placa inválidos (INVALID_CARRIER)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El despacho ya salió o fue anulado (INVALID_DISPATCH_TRANSITION)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DispatchResource assignCarrier(@Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID)
            @PathVariable UUID id, @Valid @RequestBody CarrierRequest request) {
        return DispatchResource.of(commands.assignCarrier(id, request.carrierName(), request.carrierDocument(),
                request.plate()));
    }

    @PostMapping("/{id}/departure-weighing")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Registrar el pesaje de salida",
            description = "US27: peso bruto y tara en kg; el neto (bruto - tara) debe ser mayor que cero. Uno por "
                    + "despacho y solo mientras está Preparado. La foto del ticket es una URL http(s) de la evidencia. "
                    + "Se publica DispatchDepartureWeighed.")
    @ApiResponse(responseCode = "200", description = "Pesaje registrado",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_WEIGHED)))
    @ApiResponse(responseCode = "400", description = "Pesos inválidos o tara mayor o igual al bruto (INVALID_WEIGHING), "
            + "URL de evidencia inválida", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Ya tiene pesaje (DEPARTURE_WEIGHING_ALREADY_RECORDED), ya salió o "
            + "fue anulado", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DispatchResource recordDepartureWeighing(
            @Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID) @PathVariable UUID id,
            @Valid @RequestBody WeighingRequest request) {
        return DispatchResource.of(commands.recordDepartureWeighing(id, request.grossKg(), request.tareKg(),
                request.ticketPhotoUrl()));
    }

    @PostMapping("/{id}/depart")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Despachar (salida del almacén)",
            description = "Preparado → EnTransito. Exige transportista y pesaje de salida. Se publica DispatchDeparted: "
                    + "inventario consume la reserva de cada línea y descuenta el stock del almacén. Requiere "
                    + "Idempotency-Key.")
    @ApiResponse(responseCode = "200", description = "Despacho en tránsito",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_IN_TRANSIT)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Falta transportista (CARRIER_REQUIRED) o pesaje "
            + "(DEPARTURE_WEIGHING_REQUIRED), ya salió o fue anulado (INVALID_DISPATCH_TRANSITION)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DispatchResource depart(@Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID)
            @PathVariable UUID id) {
        return DispatchResource.of(commands.depart(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Anular un despacho preparado",
            description = "US25: el administrador o el encargado del almacén de origen anula un despacho Preparado con "
                    + "un motivo de 3 a 300 caracteres. Preparado → Anulado. Lo despachado vuelve a lo pendiente de cada "
                    + "línea del pedido (EnRevision si ya no queda nada despachado, ParcialmenteAtendido si hay otros "
                    + "despachos); la reserva de stock del pedido no cambia. Se publica DispatchCancelled.")
    @ApiResponse(responseCode = "200", description = "Despacho anulado",
            content = @Content(schema = @Schema(implementation = DispatchResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.DISPATCH_CANCELLED)))
    @ApiResponse(responseCode = "400", description = "Falta el motivo o no tiene de 3 a 300 caracteres",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El despacho ya salió, fue recibido o ya estaba anulado "
            + "(INVALID_DISPATCH_TRANSITION)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DispatchResource cancel(@Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID)
            @PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return DispatchResource.of(commands.cancel(id, request.reason()));
    }

    @PostMapping("/{id}/incidents")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Registrar una incidencia en ruta",
            description = "US32: el administrador o el encargado del almacén de origen registra una incidencia (retraso, "
                    + "falla del vehículo, daño de la carga, accidente, cambio de ruta u otra) con una observación de 3 "
                    + "a 500 caracteres, solo mientras el despacho está EnTransito. Se publica TransitIncidentReported.")
    @ApiResponse(responseCode = "201", description = "Incidencia registrada",
            content = @Content(schema = @Schema(implementation = IncidentResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.INCIDENT)))
    @ApiResponse(responseCode = "400", description = "Tipo desconocido u observación vacía o de más de 500 caracteres",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El despacho no está en tránsito (INVALID_DISPATCH_TRANSITION)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<IncidentResource> reportIncident(
            @Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID) @PathVariable UUID id,
            @Valid @RequestBody IncidentRequest request) {
        IncidentView incident = commands.reportIncident(id, request.type(), request.observation());
        return ResponseEntity.created(URI.create("/api/v1/dispatches/" + id + "/incidents"))
                .body(IncidentResource.of(incident));
    }

    @GetMapping("/{id}/incidents")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar las incidencias en ruta de un despacho",
            description = "US32: incidencias ordenadas por la hora en que se registraron. Las ve quien puede ver el despacho.")
    @ApiResponse(responseCode = "200", description = "Incidencias del despacho",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = IncidentResource.class)),
                    examples = @ExampleObject(value = DispatchApiExamples.INCIDENTS)))
    @ApiResponse(responseCode = "404", description = "No existe, es de otra organización o no corresponde al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    List<IncidentResource> incidents(@Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID)
            @PathVariable UUID id) {
        return queries.incidents(id).stream().map(IncidentResource::of).toList();
    }

    @GetMapping("/{id}/manifest")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Obtener el manifiesto del despacho",
            description = "US24: código del manifiesto, pedido, obra, almacén, materiales con SKU, nombre y unidad, "
                    + "transportista y pesaje de salida, y un QR (PNG en base64) que codifica el código del manifiesto. "
                    + "El QR solo es un acceso rápido para ubicar el despacho.")
    @ApiResponse(responseCode = "200", description = "Manifiesto",
            content = @Content(schema = @Schema(implementation = ManifestResource.class),
                    examples = @ExampleObject(value = DispatchApiExamples.MANIFEST)))
    @ApiResponse(responseCode = "404", description = "No existe, es de otra organización o no corresponde al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ManifestResource manifest(@Parameter(description = "Id del despacho", example = DispatchApiExamples.DISPATCH_ID)
            @PathVariable UUID id) {
        return ManifestResource.of(queries.manifest(id));
    }

    @Schema(description = "Despacho de una o varias líneas de un pedido")
    record CreateDispatchRequest(
            @Schema(example = DispatchApiExamples.ORDER_ID) @NotNull UUID orderId,
            @NotEmpty List<@Valid LineRequest> lines) {
    }

    @Schema(description = "Línea del pedido y cantidad a despachar")
    record LineRequest(
            @Schema(description = "Id de la línea del pedido", example = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d")
            @NotNull UUID orderLineId,
            @Schema(example = "30") @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity) {
    }

    @Schema(description = "Transportista del despacho")
    record CarrierRequest(
            @Schema(example = "Transportes Rímac SAC") @NotBlank @Size(max = 150) String carrierName,
            @Schema(description = "DNI, RUC o carné", example = "20555666777") @NotBlank @Size(max = 20) String carrierDocument,
            @Schema(example = "ABC-123") @NotBlank @Size(max = 10) String plate) {
    }

    @Schema(description = "Pesaje de salida en kg")
    record WeighingRequest(
            @Schema(example = "2550.5") @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal grossKg,
            @Schema(example = "1050.5") @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal tareKg,
            @Schema(description = "URL http(s) de la foto del ticket de balanza (opcional)",
                    example = "https://evidencias.buildshield.pe/tickets/ticket-0042.jpg")
            @Size(max = 500) String ticketPhotoUrl) {
    }

    @Schema(description = "Motivo de la anulación")
    record CancelRequest(
            @Schema(example = "El camión no llegó; se reprograma el envío") @NotBlank @Size(min = 3, max = 300)
            String reason) {
    }

    @Schema(description = "Incidencia en ruta")
    record IncidentRequest(
            @Schema(description = "DELAY, VEHICLE_FAILURE, DAMAGE, ACCIDENT, ROUTE_CHANGE u OTHER", example = "DELAY")
            @NotNull IncidentType type,
            @Schema(example = "Tráfico detenido en la Panamericana Sur") @NotBlank @Size(min = 3, max = 500)
            String observation) {
    }

    @Schema(description = "Despacho con su estado, líneas, transportista, pesaje de salida, anulación e incidencias")
    record DispatchResource(UUID id, UUID orderId, UUID warehouseId, UUID worksiteId,
            @Schema(example = "PARTIAL") String type,
            @Schema(description = "Tipo en español", example = "Parcial") String typeLabel,
            @Schema(example = "PREPARED") String status,
            @Schema(description = "Estado en español", example = "Preparado") String statusLabel,
            @Schema(example = "MAN-20261103-7KQ2M9XA") String manifestCode,
            Instant preparedAt, Instant departedAt, Instant receivedAt,
            @Schema(description = "Cuándo se anuló (US25); null si no está anulado") Instant cancelledAt,
            @Schema(description = "Motivo de la anulación; null si no está anulado") String cancellationReason,
            @Schema(description = "Incidencias en ruta registradas (US32)", example = "0") int incidentCount,
            CarrierView carrier, WeighingView departureWeighing, List<LineView> lines) {

        static DispatchResource of(DispatchView view) {
            return new DispatchResource(view.id(), view.orderId(), view.warehouseId(), view.worksiteId(),
                    view.type().name(), view.type().label(), view.status().name(), view.status().label(),
                    view.manifestCode(), view.preparedAt(), view.departedAt(), view.receivedAt(), view.cancelledAt(),
                    view.cancellationReason(), view.incidentCount(), view.carrier(), view.departureWeighing(),
                    view.lines());
        }
    }

    @Schema(description = "Incidencia en ruta de un despacho")
    record IncidentResource(UUID id, UUID dispatchId,
            @Schema(example = "DELAY") String type,
            @Schema(description = "Tipo en español", example = "Retraso") String typeLabel,
            String observation, UUID reportedBy, Instant reportedAt) {

        static IncidentResource of(IncidentView view) {
            return new IncidentResource(view.id(), view.dispatchId(), view.type().name(), view.type().label(),
                    view.observation(), view.reportedBy(), view.reportedAt());
        }
    }

    @Schema(description = "Manifiesto del despacho con su QR")
    record ManifestResource(
            @Schema(example = "MAN-20261103-7KQ2M9XA") String manifestCode,
            UUID dispatchId, String status, String statusLabel, String type, String typeLabel,
            Instant preparedAt, Instant departedAt, ManifestOrder order, ManifestView.Site worksite,
            ManifestView.Site warehouse, List<ManifestView.Line> lines, CarrierView carrier,
            WeighingView departureWeighing,
            @Schema(description = "Contenido del QR: el código del manifiesto") String qrContent,
            @Schema(description = "Imagen PNG del QR en base64") String qrCodePngBase64) {

        static ManifestResource of(ManifestView view) {
            DispatchView dispatch = view.dispatch();
            return new ManifestResource(view.manifestCode(), dispatch.id(), dispatch.status().name(),
                    dispatch.status().label(), dispatch.type().name(), dispatch.type().label(), dispatch.preparedAt(),
                    dispatch.departedAt(), new ManifestOrder(view.orderId(), view.orderPlacedAt()), view.worksite(),
                    view.warehouse(), view.lines(), dispatch.carrier(), dispatch.departureWeighing(), view.qrContent(),
                    view.qrCodePngBase64());
        }
    }

    @Schema(description = "Pedido del manifiesto")
    record ManifestOrder(UUID id, Instant placedAt) {
    }
}
