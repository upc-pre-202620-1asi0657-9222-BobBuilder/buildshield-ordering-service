# Desviaciones frente al ERD corregido

Fuente: sección `ordering_db` de `wiki/informe-avance2/er-corregido.md` (Figura 34.4). Este archivo lista lo que el servicio tiene y el ERD no dibuja, o lo que cumple de forma distinta a como el ERD lo pide, con su motivo. El equipo decide si cada punto pasa al ERD o si se cambia la función que lo necesita. `OrderingErdAlignmentIT` falla si aparece una columna que no esté en el ERD ni en esta lista.

## Columnas que se conservan

No se pueden derivar de otra tabla, y sin ellas se rompe una función o el contrato de la API y de los eventos.

| Tabla | Columnas | Qué se rompe sin ellas |
|---|---|---|
| `orders` | `notes` | US18: `notes` se recibe, se devuelve en `GET /orders` y viaja en `OrderRegistered`. Nada más lo guarda. |
| `orders` | `decided_by`, `decided_at` | US20: la API devuelve quién decidió y cuándo. `updated_by` y `updated_at` no sirven: cambian con cada despacho y cada recepción. |
| `dispatches` | `departed_at` | `departedAt` en la API y en `DispatchDeparted`. `updated_at` solo coincide hasta que el despacho se recibe. |
| `dispatches` | `cancelled_at`, `cancelled_by`, `cancellation_reason` | US25: la anulación guarda quién, cuándo y por qué, y la API y `DispatchCancelled` lo devuelven. |
| `receptions` | `partial_count`, `last_partial_at` | US38: `partialCount` y `lastPartialAt` en la API y `partialCount` en `ReceptionPartiallyReceived`. |
| `reception_lines` | `stocked_qty` | US38 y US37: lo ya ingresado al stock. Sin esto, la conformidad no sabe qué `deltaQuantity` enviar a inventario y duplicaría stock. De aquí también sale lo recibido de cada línea de pedido. |
| `location_projection` | `active` | US18: un almacén desactivado responde 409 `SITE_INACTIVE`. Borrar la fila lo convertiría en 404 y dejaría sin manifiesto a los despachos antiguos de esa obra o almacén. |

## Tipos más amplios que el ERD

Se mantienen porque la API ya acepta esos valores. Reducirlos truncaría datos o rechazaría peticiones hoy válidas.

| Columna | ERD | Código | Motivo |
|---|---|---|---|
| `orders.rejection_reason` | `varchar(250)` | `varchar(500)` | El motivo admite hasta 500 caracteres. |
| `dispatches.carrier_name` | `varchar(120)` | `varchar(150)` | `@Size(max = 150)` en la API. |
| `dispatches.carrier_document` | `varchar(15)` | `varchar(20)` | `@Size(max = 20)` en la API. |
| `gross_kg`, `tare_kg`, `net_kg` de ambos pesajes | `numeric(12,2)` | `numeric(12,3)` | La API acepta 3 decimales. |
| `ticket_photo_url` de ambos pesajes | `varchar(255)` | `varchar(500)` | `@Size(max = 500)` en la API. |

Restricciones que el ERD no marca y se conservan porque protegen una regla: `uk_order_lines_material` (un material por pedido) y `uk_dispatch_lines_order_line` (una línea de pedido por despacho).

## Lo que cumple el ERD con una condición

- **`departure_weighings.ticket_photo_url` es NOT NULL, pero la foto sigue siendo opcional en la API.** Un pesaje sin foto guarda `sin-foto`, y la aplicación lo devuelve como nulo. Los pesajes antiguos sin foto se marcaron igual. Para exigirla de verdad hay que validar en `POST /dispatches/{id}/departure-weighing`, y eso cambia el contrato.
- **`orders.required_date` es NOT NULL, pero la fecha sigue siendo opcional en la API.** Si no se envía, se usa el día de creación (UTC), y eso es lo que devuelven la respuesta y `OrderRegistered`: `requiredDate` ya nunca es nulo. Los pedidos antiguos sin fecha se completaron igual.
- **`idempotency_keys.response_body` lleva más que la respuesta.** El ERD no tiene columnas para el estado HTTP, los encabezados, la ruta exacta ni el dueño de la clave, y sin ellos un reintento no se puede repetir ni validar. Van en ese JSON: `method`, `path`, `status`, `userId`, `role`, `contentType`, `location` y `bodyBase64`. Al vencer se quitan `bodyBase64`, `contentType` y `location`, y el resto se queda para seguir rechazando reintentos. `endpoint` guarda la ruta con `{id}` porque la exacta no cabe en 80 caracteres. `request_hash` en ceros marca un registro antiguo sin huella, que no se reproduce.
- **`outbox_events.payload` guarda el sobre completo** (`aggregateType` y `actorId` incluidos), igual que en iam, porque el ERD no tiene esas columnas. Lo que llega a Kafka no cambia.
- **`processed_events`**: los eventos anteriores a la migración se registraron para `OrganizationEventConsumer` y para `InventoryEventConsumer`, porque antes no se guardaba cuál los procesó. Así una reentrega se sigue descartando.

## Datos derivados que pueden cambiar con el tiempo

Antes eran una foto del momento. Ahora se leen de la copia local, como pide el ERD.

- `sku` y `unit` de una línea de pedido salen de `material_projection`. Si el material cambia de SKU o unidad, los pedidos antiguos muestran el valor nuevo. Los materiales que faltaban en la copia se agregaron desde las líneas, inactivos y con tolerancia 0, hasta que llegue su evento.
- `tolerancePercent` y `withinTolerance` del pesaje de llegada se calculan con la tolerancia actual de los materiales. Si cambia, cambia la respuesta de `GET /receptions/{id}`. Lo que ya se publicó en `ArrivalWeighed` no cambia. Si una tolerancia cambió antes de migrar, el valor histórico de `tolerance_pct` no se conserva en esta base.

## Copias locales

- `location_projection.address` de una obra guarda dirección, distrito y ciudad unidos, recortados a 200 caracteres. Es lo que el manifiesto ya mostraba. De un almacén se guarda solo la dirección, que es lo que mostraba: su distrito y ciudad no se usaban y se quitaron.
- `assignment_projection`: `StaffUnassigned` borra la fila. Las asignaciones ya inactivas se borraron al migrar; el historial sigue en organization.

## Lo que el esquema admite y el servicio todavía no usa

- **Despacho de retorno.** `dispatches.order_id`, `dispatch_lines.order_line_id` y el tipo `RETURN` ya están, y un retorno existente se lee, se cancela y se recibe sin tocar pedidos. No hay caso de uso ni endpoint para crearlo.
- **`transit_incidents.photo_url`.** La columna existe, siempre nula. La API de incidencias no la recibe ni la devuelve.
