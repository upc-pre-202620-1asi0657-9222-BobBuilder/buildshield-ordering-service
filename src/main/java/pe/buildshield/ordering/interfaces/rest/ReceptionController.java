package pe.buildshield.ordering.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.ordering.application.ComparisonView;
import pe.buildshield.ordering.application.ReceptionCommandService;
import pe.buildshield.ordering.application.ReceptionQueryService;
import pe.buildshield.ordering.application.ReceptionView;
import pe.buildshield.ordering.application.ReceptionView.ArrivalWeighingView;
import pe.buildshield.ordering.application.ReceptionView.LineView;
import pe.buildshield.ordering.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receptions")
@Tag(name = "Recepciones", description = "US34 apertura por código, US35 recepción y cotejo, US37 conformidad, "
        + "US38 recepción parcial, US29 pesaje de llegada")
@SecurityRequirement(name = "bearer")
class ReceptionController {

    private final ReceptionCommandService commands;
    private final ReceptionQueryService queries;

    ReceptionController(ReceptionCommandService commands, ReceptionQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'SITE_MANAGER')")
    @Operation(summary = "Abrir la recepción de un despacho",
            description = "El administrador o el encargado de la obra de destino abre la recepción de un despacho en "
                    + "tránsito, indicado por dispatchId o por el código del manifiesto (manifestCode, US34; exactamente "
                    + "uno). Queda EnCurso con una línea por cada línea del despacho y se publica ReceptionOpened. Hay "
                    + "una sola recepción por despacho: si ya existe responde 409 RECEPTION_ALREADY_EXISTS con su id en "
                    + "los detalles.")
    @ApiResponse(responseCode = "201", description = "Recepción abierta",
            content = @Content(schema = @Schema(implementation = ReceptionResource.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.RECEPTION_STARTED)))
    @ApiResponse(responseCode = "400", description = "Falta el despacho o vienen dispatchId y manifestCode a la vez "
            + "(INVALID_RECEPTION_REQUEST)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El despacho no existe o no va a una obra del usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Ya existe la recepción (RECEPTION_ALREADY_EXISTS) o el despacho "
            + "no está en tránsito (DISPATCH_NOT_IN_TRANSIT)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<ReceptionResource> start(@Valid @RequestBody StartReceptionRequest request) {
        ReceptionView started = commands.start(request.dispatchId(), request.manifestCode());
        return ResponseEntity.created(URI.create("/api/v1/receptions/" + started.id()))
                .body(ReceptionResource.of(started));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar una recepción",
            description = "Estado, lo recibido por línea, recepciones parciales y pesaje de llegada. La ven el "
                    + "administrador y los encargados de la obra de destino y del almacén de origen.")
    @ApiResponse(responseCode = "200", description = "Recepción",
            content = @Content(schema = @Schema(implementation = ReceptionResource.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.RECEPTION_RECORDED)))
    @ApiResponse(responseCode = "404", description = "No existe, es de otra organización o no corresponde al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReceptionResource get(@Parameter(description = "Id de la recepción", example = ReceptionApiExamples.RECEPTION_ID)
            @PathVariable UUID id) {
        return ReceptionResource.of(queries.get(id));
    }

    @PutMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'SITE_MANAGER')")
    @Operation(summary = "Registrar lo recibido de una línea",
            description = "US35: cantidad recibida acumulada (cero o más, sin superar lo despachado). Se guarda de a "
                    + "poco y se puede corregir mientras la recepción no está confirmada, sin bajar de lo ya ingresado "
                    + "en recepciones parciales; calcula la merma de la línea.")
    @ApiResponse(responseCode = "200", description = "Línea registrada",
            content = @Content(schema = @Schema(implementation = ReceptionResource.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.RECEPTION_RECORDED)))
    @ApiResponse(responseCode = "400", description = "Cantidad negativa, con más de 3 decimales o mayor que lo "
            + "despachado (RECEIVED_EXCEEDS_DISPATCHED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La recepción o la línea no existen o no corresponden al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La recepción ya fue confirmada (RECEPTION_ALREADY_CONFIRMED) o la "
            + "cantidad es menor que lo ya ingresado en parciales (RECEIVED_BELOW_STOCKED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReceptionResource recordLine(
            @Parameter(description = "Id de la recepción", example = ReceptionApiExamples.RECEPTION_ID) @PathVariable UUID id,
            @Parameter(description = "Id de la línea de la recepción", example = ReceptionApiExamples.LINE_ID)
            @PathVariable UUID lineId,
            @Valid @RequestBody RecordLineRequest request) {
        return ReceptionResource.of(commands.recordLine(id, lineId, request.receivedQty()));
    }

    @GetMapping("/{id}/comparison")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cotejar lo pedido, despachado y recibido",
            description = "US35, QAS01: por material, lo solicitado, despachado y recibido, la diferencia (despachado - "
                    + "recibido), la merma en % y si está dentro de la tolerancia del material.")
    @ApiResponse(responseCode = "200", description = "Cotejo por material",
            content = @Content(schema = @Schema(implementation = ComparisonResource.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.COMPARISON)))
    @ApiResponse(responseCode = "404", description = "No existe, es de otra organización o no corresponde al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ComparisonResource comparison(
            @Parameter(description = "Id de la recepción", example = ReceptionApiExamples.RECEPTION_ID) @PathVariable UUID id) {
        return ComparisonResource.of(queries.comparison(id));
    }

    @PostMapping("/{id}/partial")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'SITE_MANAGER')")
    @Operation(summary = "Guardar una recepción parcial",
            description = "US38: ingresa lo recibido hasta ahora que aún no se había ingresado (la diferencia respecto "
                    + "del último parcial), lo registra en el pedido y publica ReceptionPartiallyReceived: inventario "
                    + "suma esa diferencia al stock de la obra. La recepción queda RecibidaParcialmente y el despacho "
                    + "sigue EnTransito. Requiere Idempotency-Key: repetir con la misma clave devuelve la misma "
                    + "respuesta.")
    @ApiResponse(responseCode = "200", description = "Recepción parcial guardada",
            content = @Content(schema = @Schema(implementation = ReceptionResource.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.RECEPTION_PARTIAL)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es de la obra de destino",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No hay nada nuevo que ingresar (NOTHING_TO_RECEIVE) o ya fue "
            + "confirmada (RECEPTION_ALREADY_CONFIRMED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReceptionResource partial(@Parameter(description = "Id de la recepción", example = ReceptionApiExamples.RECEPTION_ID)
            @PathVariable UUID id) {
        return ReceptionResource.of(commands.receivePartial(id));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'SITE_MANAGER')")
    @Operation(summary = "Dar conformidad a la recepción",
            description = "US37, QAS10: exige lo recibido de todas las líneas. En una transacción registra en el pedido "
                    + "lo que faltaba ingresar, marca la recepción Confirmada y el despacho Recibido y publica "
                    + "ReceptionConfirmed (inventario suma el deltaQuantity a la obra; discrepancias abre las líneas fuera "
                    + "de tolerancia). Si el pedido queda completo se cierra (OrderClosed). Requiere Idempotency-Key: "
                    + "repetir con la misma clave devuelve la misma respuesta; otra clave responde 409 "
                    + "RECEPTION_ALREADY_CONFIRMED.")
    @ApiResponse(responseCode = "200", description = "Recepción confirmada",
            content = @Content(schema = @Schema(implementation = ReceptionResource.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.RECEPTION_CONFIRMED)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es de la obra de destino",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Faltan líneas por registrar (RECEPTION_INCOMPLETE) o ya fue "
            + "confirmada (RECEPTION_ALREADY_CONFIRMED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReceptionResource confirm(@Parameter(description = "Id de la recepción", example = ReceptionApiExamples.RECEPTION_ID)
            @PathVariable UUID id) {
        return ReceptionResource.of(commands.confirm(id));
    }

    @PostMapping("/{id}/arrival-weighing")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'SITE_MANAGER')")
    @Operation(summary = "Registrar el pesaje de llegada",
            description = "US29: peso bruto y tara en kg (el bruto debe superar la tara) y foto opcional del ticket. Se "
                    + "compara con el neto de salida: diferencia = neto de salida - neto de llegada, en kg y en %, y "
                    + "está dentro de la tolerancia si |diferencia %| no supera la menor tolerancia de los materiales del "
                    + "despacho. Sin pesaje de salida la comparación es null. Uno por recepción, mientras no esté "
                    + "confirmada. Se publica ArrivalWeighed.")
    @ApiResponse(responseCode = "201", description = "Pesaje de llegada registrado",
            content = @Content(schema = @Schema(implementation = ArrivalWeighingView.class),
                    examples = @ExampleObject(value = ReceptionApiExamples.ARRIVAL_WEIGHING)))
    @ApiResponse(responseCode = "400", description = "Pesos inválidos o tara mayor o igual al bruto (INVALID_WEIGHING), "
            + "URL de evidencia inválida", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No existe o el usuario no es de la obra de destino",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Ya tiene pesaje de llegada (ARRIVAL_WEIGHING_ALREADY_RECORDED) o "
            + "ya fue confirmada (RECEPTION_ALREADY_CONFIRMED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<ArrivalWeighingView> arrivalWeighing(
            @Parameter(description = "Id de la recepción", example = ReceptionApiExamples.RECEPTION_ID) @PathVariable UUID id,
            @Valid @RequestBody ArrivalWeighingRequest request) {
        ArrivalWeighingView weighing = commands.recordArrivalWeighing(id, request.grossKg(), request.tareKg(),
                request.ticketPhotoUrl());
        return ResponseEntity.created(URI.create("/api/v1/receptions/" + id)).body(weighing);
    }

    @Schema(description = "Despacho a recibir: por id o por el código del manifiesto (exactamente uno)")
    record StartReceptionRequest(
            @Schema(example = "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f") UUID dispatchId,
            @Schema(description = "Código del manifiesto (lo que codifica el QR)", example = "MAN-20261103-7KQ2M9XA")
            @Size(max = 30) String manifestCode) {
    }

    @Schema(description = "Cantidad recibida acumulada de la línea")
    record RecordLineRequest(
            @Schema(example = "29.5") @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal receivedQty) {
    }

    @Schema(description = "Pesaje de llegada en kg")
    record ArrivalWeighingRequest(
            @Schema(example = "2530") @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal grossKg,
            @Schema(example = "1050.5") @NotNull @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal tareKg,
            @Schema(description = "URL http(s) de la foto del ticket de balanza (opcional)",
                    example = "https://evidencias.buildshield.pe/tickets/llegada-0042.jpg")
            @Size(max = 500) String ticketPhotoUrl) {
    }

    @Schema(description = "Recepción con lo recibido por línea, las recepciones parciales y el pesaje de llegada")
    record ReceptionResource(UUID id, UUID dispatchId, UUID orderId, UUID warehouseId, UUID worksiteId,
            @Schema(example = "IN_PROGRESS") String status,
            @Schema(description = "Estado en español", example = "EnCurso") String statusLabel,
            UUID confirmedBy, Instant confirmedAt,
            @Schema(description = "Recepciones parciales guardadas (US38)", example = "0") int partialCount,
            @Schema(description = "Cuándo se guardó la última recepción parcial") Instant lastPartialAt,
            @Schema(description = "Pesaje de llegada (US29); null si no se registró") ArrivalWeighingView arrivalWeighing,
            List<LineView> lines) {

        static ReceptionResource of(ReceptionView view) {
            return new ReceptionResource(view.id(), view.dispatchId(), view.orderId(), view.warehouseId(),
                    view.worksiteId(), view.status().name(), view.status().label(), view.confirmedBy(),
                    view.confirmedAt(), view.partialCount(), view.lastPartialAt(), view.arrivalWeighing(), view.lines());
        }
    }

    @Schema(description = "Cotejo por material: complete indica que todas las líneas tienen lo recibido; "
            + "withinTolerance, que además ninguna supera la tolerancia")
    record ComparisonResource(UUID receptionId, UUID dispatchId, UUID orderId, String status, String statusLabel,
            boolean complete, boolean withinTolerance, List<ComparisonView.Line> lines) {

        static ComparisonResource of(ComparisonView view) {
            return new ComparisonResource(view.receptionId(), view.dispatchId(), view.orderId(), view.status().name(),
                    view.status().label(), view.complete(), view.withinTolerance(), view.lines());
        }
    }
}
