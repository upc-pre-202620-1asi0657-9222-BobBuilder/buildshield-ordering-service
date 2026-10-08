package pe.buildshield.ordering.interfaces.rest;

/** Ejemplos de respuesta y de parámetros para la documentación OpenAPI de los despachos. */
final class DispatchApiExamples {

    static final String DISPATCH_ID = "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f";
    static final String ORDER_ID = "6f7a8b9c-0d1e-4f2a-9b3c-4d5e6f7a8b9c";
    static final String MANIFEST_CODE = "MAN-20261103-7KQ2M9XA";

    private static final String HEAD = """
            {
              "id": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "orderId": "6f7a8b9c-0d1e-4f2a-9b3c-4d5e6f7a8b9c",
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "worksiteId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              "type": "PARTIAL",
              "typeLabel": "Parcial",
            """;

    private static final String LINES = """
              "lines": [
                {
                  "id": "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b",
                  "orderLineId": "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d",
                  "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                  "quantity": 30.000
                }
              ]
            }""";

    private static final String CARRIER = """
              "carrier": {
                "name": "Transportes Rímac SAC",
                "document": "20555666777",
                "plate": "ABC-123"
              },
            """;

    private static final String WEIGHING = """
              "departureWeighing": {
                "grossKg": 2550.500,
                "tareKg": 1050.500,
                "netKg": 1500.000,
                "ticketPhotoUrl": "https://evidencias.buildshield.pe/tickets/ticket-0042.jpg",
                "weighedAt": "2026-11-03T13:40:00Z",
                "weighedBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5"
              },
            """;

    private static final String NOT_CANCELLED = """
              "cancelledAt": null,
              "cancellationReason": null,
            """;

    static final String DISPATCH_PREPARED = HEAD + """
              "status": "PREPARED",
              "statusLabel": "Preparado",
              "manifestCode": "MAN-20261103-7KQ2M9XA",
              "preparedAt": "2026-11-03T13:00:00Z",
              "departedAt": null,
              "receivedAt": null,
            """ + NOT_CANCELLED + """
              "incidentCount": 0,
              "carrier": null,
              "departureWeighing": null,
            """ + LINES;

    static final String DISPATCHES = "[" + DISPATCH_PREPARED + "]";

    static final String DISPATCH_WITH_CARRIER = HEAD + """
              "status": "PREPARED",
              "statusLabel": "Preparado",
              "manifestCode": "MAN-20261103-7KQ2M9XA",
              "preparedAt": "2026-11-03T13:00:00Z",
              "departedAt": null,
              "receivedAt": null,
            """ + NOT_CANCELLED + """
              "incidentCount": 0,
            """ + CARRIER + """
              "departureWeighing": null,
            """ + LINES;

    static final String DISPATCH_WEIGHED = HEAD + """
              "status": "PREPARED",
              "statusLabel": "Preparado",
              "manifestCode": "MAN-20261103-7KQ2M9XA",
              "preparedAt": "2026-11-03T13:00:00Z",
              "departedAt": null,
              "receivedAt": null,
            """ + NOT_CANCELLED + """
              "incidentCount": 0,
            """ + CARRIER + WEIGHING + LINES;

    static final String DISPATCH_IN_TRANSIT = HEAD + """
              "status": "IN_TRANSIT",
              "statusLabel": "EnTransito",
              "manifestCode": "MAN-20261103-7KQ2M9XA",
              "preparedAt": "2026-11-03T13:00:00Z",
              "departedAt": "2026-11-03T14:00:00Z",
              "receivedAt": null,
            """ + NOT_CANCELLED + """
              "incidentCount": 1,
            """ + CARRIER + WEIGHING + LINES;

    static final String DISPATCH_CANCELLED = HEAD + """
              "status": "CANCELLED",
              "statusLabel": "Anulado",
              "manifestCode": "MAN-20261103-7KQ2M9XA",
              "preparedAt": "2026-11-03T13:00:00Z",
              "departedAt": null,
              "receivedAt": null,
              "cancelledAt": "2026-11-03T13:30:00Z",
              "cancellationReason": "El camión no llegó; se reprograma el envío",
              "incidentCount": 0,
              "carrier": null,
              "departureWeighing": null,
            """ + LINES;

    static final String INCIDENT = """
            {
              "id": "0e1f2a3b-4c5d-4e6f-8a7b-9c0d1e2f3a4b",
              "dispatchId": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "type": "DELAY",
              "typeLabel": "Retraso",
              "observation": "Tráfico detenido en la Panamericana Sur",
              "reportedBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5",
              "reportedAt": "2026-11-03T14:40:00Z"
            }""";

    static final String INCIDENTS = "[" + INCIDENT + "]";

    static final String MANIFEST = """
            {
              "manifestCode": "MAN-20261103-7KQ2M9XA",
              "dispatchId": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "status": "IN_TRANSIT",
              "statusLabel": "EnTransito",
              "type": "PARTIAL",
              "typeLabel": "Parcial",
              "preparedAt": "2026-11-03T13:00:00Z",
              "departedAt": "2026-11-03T14:00:00Z",
              "order": {
                "id": "6f7a8b9c-0d1e-4f2a-9b3c-4d5e6f7a8b9c",
                "placedAt": "2026-11-02T15:00:00Z"
              },
              "worksite": {
                "id": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                "name": "Torre Norte",
                "address": "Av. Principal 100, Miraflores, Lima"
              },
              "warehouse": {
                "id": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
                "name": "Almacén Central",
                "address": "Av. Argentina 2500, Callao"
              },
              "lines": [
                {
                  "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                  "sku": "CEM-001",
                  "name": "Cemento Portland tipo I",
                  "unit": "BAG",
                  "quantity": 30.000
                }
              ],
            """ + CARRIER + WEIGHING + """
              "qrContent": "MAN-20261103-7KQ2M9XA",
              "qrCodePngBase64": "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAIAAADTED8xAAAA..."
            }""";

    private DispatchApiExamples() {
    }
}
