# buildshield-ordering-service

Gestión de Pedidos y Entregas de BuildShield (BobBuilder), el contexto core: pedidos de materiales de la obra al
almacén, despachos (con manifiesto y QR, transportista, pesaje de salida, anulación e incidencias en ruta) y recepción
en obra (cotejo, recepción parcial, pesaje de llegada y conformidad). Contrato de la arquitectura:
`wiki/informe-tf/plan-microservicios.md`; nombres exactos de los eventos: `wiki/informe-tf/notas-etapa-b1.md`.

| Dato | Valor |
|---|---|
| Puerto | 8084 |
| Base | `ordering_db` (PostgreSQL 16+), rol `ordering_service` |
| Paquete | `pe.buildshield.ordering` (kernel compartido copiado de iam en `pe.buildshield.ordering.shared`) |
| Publica | `buildshield.ordering` (outbox, clave = `aggregateId`) |
| Consume | `buildshield.organization` (copias locales) y `buildshield.inventory` (respuesta de la saga) |

## Componentes (C4 de la v4)

`OrderController`, `DispatchController`, `ReceptionController` (REST) → `OrderCommandService`, `OrderQueryService`,
`DispatchCommandService`, `DispatchQueryService`, `ReceptionCommandService`, `ReceptionQueryService` → agregados
`Order`, `Dispatch`, `Reception` → `*Repository` (Spring Data JPA detrás de puertos del dominio). Además:
`OrderingOutboxPublisher` (cada 500 ms), `OrganizationEventConsumer`, `InventoryEventConsumer`,
`EvidenceStorageAdapter` (local: valida y guarda la URL http(s) de la foto del ticket; en producción lo reemplaza un
adaptador de Amazon S3, todavía no implementado) y `ZxingManifestQrCode` (QR del manifiesto).

## Eventos

| Publica (`buildshield.ordering`) | Consume |
|---|---|
| `OrderRegistered`, `OrderApproved`, `OrderRejected`, `OrderClosed` | `buildshield.organization`: `LocationRegistered`, `LocationUpdated` → `location_projection` |
| `DispatchPrepared`, `DispatchCarrierAssigned`, `DispatchDepartureWeighed`, `DispatchDeparted`, `DispatchCancelled`, `TransitIncidentReported` | `MaterialRegistered`, `MaterialUpdated` → `material_projection` |
| `ReceptionOpened`, `ArrivalWeighed`, `ReceptionPartiallyReceived`, `ReceptionConfirmed` | `StaffAssigned`, `StaffUnassigned` → `assignment_projection` |
| | `buildshield.inventory`: `StockReserved`, `StockReservationRejected` (saga) |

Los campos de cada payload están en `notas-etapa-b1.md` §2 y los prueba `OrderingEventsJsonTest` y `OrderingMessagingIT`.
Cada consumo es idempotente (`processed_events`).

## Saga de reserva de stock (coreografiada)

1. `POST /orders/{id}/approve`: Registrado → **Aprobando** (`APPROVAL_PENDING`) y se publica `OrderApproved`.
2. Inventario reserva en el almacén de origen y responde.
3. `StockReserved` → **EnRevision** (`IN_REVIEW`, aprobado: ya se despacha). `StockReservationRejected` → vuelve a
   **Registrado** con `rejectionReason` = el motivo de inventario. Si el pedido ya no está Aprobando, el evento se ignora.
4. `DispatchDeparted`: inventario consume la reserva y descuenta el almacén.
5. `ReceptionPartiallyReceived` y `ReceptionConfirmed`: inventario suma a la obra solo `deltaQuantity`.
6. `OrderRejected` y `OrderClosed` (al confirmar la última recepción de un pedido Atendido): inventario libera lo que
   quedó reservado. Anular un despacho (US25) no toca la reserva: lo despachado vuelve a lo pendiente del pedido.

## Endpoints (`/api/v1`)

- Pedidos: `POST /orders` (encargado de obra, con `requiredDate` opcional), `GET /orders`, `GET /orders/{id}`,
  `POST /orders/{id}/approve`, `POST /orders/{id}/reject`.
- Despachos: `POST /dispatches`, `GET /dispatches?orderId=&status=` (cada uno con `incidentCount`, US31),
  `GET /dispatches/{id}`, `GET /dispatches/by-manifest/{code}` (US26), `PATCH /dispatches/{id}/carrier`,
  `POST /dispatches/{id}/departure-weighing`, `POST /dispatches/{id}/depart`, `POST /dispatches/{id}/cancel` (US25),
  `POST` y `GET /dispatches/{id}/incidents` (US32), `GET /dispatches/{id}/manifest`.
- Recepciones: `POST /receptions` (`{"dispatchId"}` o `{"manifestCode"}`, US34), `GET /receptions/{id}`,
  `PUT /receptions/{id}/lines/{lineId}`, `GET /receptions/{id}/comparison`, `POST /receptions/{id}/partial` (US38, sin
  cuerpo), `POST /receptions/{id}/confirm`, `POST /receptions/{id}/arrival-weighing` (US29).
- `Idempotency-Key` obligatoria en crear y decidir pedidos, crear y hacer salir despachos, parcial y conformidad.
- Lo que el usuario no puede ver responde 404. OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html`.
- `GET /dispatches/{id}/custody` no está aquí: lo sirve audit-service.

## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`),
`BUILDSHIELD_JWT_PUBLIC_KEY` y `SERVER_PORT` (por defecto 8084). Perfiles `local`, `test` y `prod`.

## Ejecutar en local (clúster PostgreSQL 127.0.0.1:5433 con autenticación trust)

```bash
mvn -B package -DskipTests
BUILDSHIELD_JWT_PUBLIC_KEY="$(cat ~/.buildshield/jwt-public.pem)" \
java -jar target/buildshield-ordering-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Si Kafka no está levantado el servicio arranca igual: los eventos quedan en el outbox y se envían al volver.

## Pruebas

```bash
mvn -B verify -Dbuildshield.test.external-database=true
```

- Unitarias de dominio y aplicación (servicios reales sobre dobles en memoria), matriz de autorización por rol y
  ArchUnit.
- `*IT` contra la base temporal `ordering_it` en `127.0.0.1:5433` (usuario `buildshield_core`) y Kafka embebido:
  salud, OpenAPI completo, persistencia, aislamiento multiempresa y mensajería (outbox, saga, duplicados, proyecciones).
- Cucumber (`features/`, en español): historias US18 a US38 del Sprint 2 y del Sprint 3. Los datos de organization
  llegan por eventos a Kafka y la saga la responde un inventario simulado (`FakeInventory`, solo en pruebas).
- JaCoCo exige 80 % de líneas en `domain` y `application`.
