# Esquema de `ordering_db` frente al ERD

Fuente: sección `ordering_db` de `wiki/informe-avance2/er-corregido.md` (Figura 34.4), más la ampliación que aceptó el equipo. `OrderingErdAlignmentIT` falla si el esquema tiene una columna que no esté en el ERD ampliado o le falta una.

## Ampliación aceptada del ERD

Estas columnas y tipos ya son parte del ERD. Se listan para pasarlos a la figura.

| Tabla | Columnas | Para qué sirven |
|---|---|---|
| `orders` | `notes` | US18: se recibe, se devuelve en `GET /orders` y viaja en `OrderRegistered`. |
| `orders` | `decided_by`, `decided_at` | US20: quién decidió y cuándo. `updated_by` y `updated_at` cambian con cada despacho y cada recepción. |
| `dispatches` | `departed_at` | `departedAt` en la API y en `DispatchDeparted`. |
| `dispatches` | `cancelled_at`, `cancelled_by`, `cancellation_reason` | US25: quién anuló, cuándo y por qué, en la API y en `DispatchCancelled`. |
| `receptions` | `partial_count`, `last_partial_at` | US38: `partialCount` y `lastPartialAt` en la API, y `partialCount` en `ReceptionPartiallyReceived`. |
| `reception_lines` | `stocked_qty` | US38 y US37: lo ya ingresado al stock, para calcular el `deltaQuantity` que se envía a inventario. |
| `location_projection` | `active` | US18: un almacén desactivado responde 409 `SITE_INACTIVE` y los despachos antiguos conservan su manifiesto. |
| `arrival_weighings` | `tolerance_pct` (`numeric(5,2)`, nula) | US29: la tolerancia vigente al pesar. Es nula solo en los pesajes anteriores a V3. |

| Columna | Tipo aceptado | Motivo |
|---|---|---|
| `orders.rejection_reason` | `varchar(500)` | El motivo admite hasta 500 caracteres. |
| `dispatches.carrier_name` | `varchar(150)` | `@Size(max = 150)` en la API. |
| `dispatches.carrier_document` | `varchar(20)` | `@Size(max = 20)` en la API. |
| `gross_kg`, `tare_kg`, `net_kg` de ambos pesajes | `numeric(12,3)` | La API acepta 3 decimales. |
| `ticket_photo_url` de ambos pesajes | `varchar(500)`, nula | `@Size(max = 500)` en la API, y la foto es opcional. |
| `orders.required_date` | `date`, nula | La fecha es opcional en la API. |

Restricciones que la figura no marca y protegen una regla: `uk_order_lines_material` (un material por pedido) y `uk_dispatch_lines_order_line` (una línea de pedido por despacho).

## Datos que quedaron de migraciones anteriores

`required_date` no se distingue en los pedidos anteriores a V3: V2 completó las nulas con el día de creación, y ya no se sabe cuáles eran reales. Por eso V3 no las cambia.

## Cómo se usan algunas columnas

- **`idempotency_keys.response_body` lleva más que la respuesta.** El ERD no tiene columnas para el estado HTTP, los encabezados, la ruta exacta ni el dueño de la clave, y sin ellos un reintento no se puede repetir ni validar. Van en ese JSON: `method`, `path`, `status`, `userId`, `role`, `contentType`, `location` y `bodyBase64`. Al vencer se quitan `bodyBase64`, `contentType` y `location`, y el resto se queda para seguir rechazando reintentos. `endpoint` guarda la ruta con `{id}` porque la exacta no cabe en 80 caracteres. `request_hash` en ceros marca un registro antiguo sin huella, que no se reproduce.
- **`outbox_events.payload` guarda el sobre completo** (`aggregateType` y `actorId` incluidos), igual que en iam, porque el ERD no tiene esas columnas. Lo que llega a Kafka no cambia.
- **`processed_events`**: los eventos anteriores a V2 se registraron para los dos consumidores de entonces, y V4 los pasa al nombre actual, `StockEventConsumer`. Así una reentrega se sigue descartando.

## Datos que pueden cambiar con el tiempo

Se leen de la copia local, como pide el ERD.

- `sku` y `unit` de una línea de pedido salen de `material_projection`. Si el material cambia de SKU o unidad, los pedidos antiguos muestran el valor nuevo. Los materiales que faltaban en la copia se agregaron desde las líneas, inactivos y con tolerancia 0, hasta que llegue su evento.
- La tolerancia de cada material en `GET /receptions/{id}/comparison` y en `ReceptionConfirmed` es la actual, porque `reception_lines` no la guarda. El resultado del pesaje de llegada en `GET /receptions/{id}` no depende de esto: usa `tolerance_pct`. Solo un pesaje anterior a V3, sin ese valor, usa la menor tolerancia actual de los materiales.

## Copias locales

- `location_projection.address` de una obra guarda dirección, distrito y ciudad unidos, recortados a 200 caracteres. Es lo que el manifiesto ya mostraba. De un almacén se guarda solo la dirección, que es lo que mostraba: su distrito y ciudad no se usaban y se quitaron.
- `assignment_projection`: `StaffUnassigned` borra la fila. Las asignaciones ya inactivas se borraron al migrar; el historial sigue en organization.

## Despachos de retorno

Salen de los eventos de la devolución de inventario; ver el README. No agregan columnas al ERD:

- **`dispatches.id` deriva de la devolución.** Un UUID versión 3 de `balance-return:<returnId>`. El ERD no tiene dónde guardar la devolución en `dispatches`, y así la llave primaria impide dos despachos para una devolución. De la devolución al despacho se llega por `balance_returns.return_dispatch_id`, en inventory.
- **`receivedAt` de un retorno es `dispatches.updated_at`.** No tiene recepción de donde leerlo y el ERD no tiene esa columna. Un retorno recibido no vuelve a cambiar. Es la hora en que ordering procesó el evento; la de la verificación está en `balance_returns.received_at`.
- **`carrier_*` son nulos y no hay pesaje en un retorno.** La devolución de inventario no los registra. Por eso V5 quita a los `RETURN` la exigencia de transportista de `ck_dispatches_carrier`; los demás despachos siguen sin poder salir sin él, y `departed_at` se sigue exigiendo a todos.
- **Incidencias en un retorno**: las registran, como en cualquier despacho, el administrador y el encargado del almacén, aunque el material venga de la obra.
- **Devoluciones que ya estaban en tránsito antes de este cambio** no tienen despacho: su `MaterialReturnReceived` se registra en el log y se ignora.

## Componentes frente a la tabla de la tarea

`OrdersController`, `DispatchesController`, `ReceptionsController`, `OrdersCommandService`, `OrdersQueryService`, `OrdersOutboxPublisher`, `EvidenceStorageAdapter` y `StockEventConsumer` tienen el nombre de la tabla.

**Partición que sigue**: los casos de uso de despachos y recepciones están en `DispatchCommandService`, `DispatchQueryService`, `ReceptionCommandService` y `ReceptionQueryService`, y los repositorios son uno por agregado. La tarea permite partir el controlador y el repositorio, pero no menciona los servicios. No se juntaron en `OrdersCommandService` y `OrdersQueryService` porque daría una clase de más de 600 líneas sin ganar nada.

## Abierto

- **Foto de la incidencia**: `photo_url` guarda la URL que manda el cliente, como las fotos de los pesajes. `EvidenceStorageAdapter` todavía no sube a Amazon S3.
