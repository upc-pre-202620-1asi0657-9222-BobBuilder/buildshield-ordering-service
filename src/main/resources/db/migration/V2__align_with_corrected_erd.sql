-- Alineación de ordering_db con el ERD corregido (wiki/informe-avance2/er-corregido.md, Figura 34.4). Conserva los
-- datos: lo que se quita de una tabla se copia antes a su lugar definitivo o se deriva del dueño del dato. Las columnas
-- que el ERD no dibuja y que no se pueden derivar se quedan y están justificadas en DESVIACIONES.md.

-- ---------------------------------------------------------------------------------------------------
-- Antes de quitar copias de datos, se comprueba que coincidan con su origen. Si no coinciden, la migración
-- se detiene: borrarlas perdería información sin avisar.
-- ---------------------------------------------------------------------------------------------------
DO $$
DECLARE
    bad_receptions       BIGINT;
    bad_reception_lines  BIGINT;
    bad_received         BIGINT;
    bad_received_at      BIGINT;
    bad_departure_net    BIGINT;
BEGIN
    SELECT count(*) INTO bad_receptions
    FROM receptions r JOIN dispatches d ON d.id = r.dispatch_id
    WHERE r.order_id IS DISTINCT FROM d.order_id OR r.warehouse_id IS DISTINCT FROM d.warehouse_id
       OR r.worksite_id IS DISTINCT FROM d.worksite_id OR r.manifest_code IS DISTINCT FROM d.manifest_code;

    SELECT count(*) INTO bad_reception_lines
    FROM reception_lines rl JOIN dispatch_lines dl ON dl.id = rl.dispatch_line_id
    WHERE rl.order_line_id IS DISTINCT FROM dl.order_line_id OR rl.material_id IS DISTINCT FROM dl.material_id
       OR rl.dispatched_qty IS DISTINCT FROM dl.quantity;

    SELECT count(*) INTO bad_received
    FROM order_lines ol
    LEFT JOIN (SELECT dl.order_line_id, sum(rl.stocked_qty) AS stocked
               FROM reception_lines rl JOIN dispatch_lines dl ON dl.id = rl.dispatch_line_id
               GROUP BY dl.order_line_id) s ON s.order_line_id = ol.id
    WHERE ol.received_qty <> COALESCE(s.stocked, 0);

    SELECT count(*) INTO bad_received_at
    FROM dispatches d LEFT JOIN receptions r ON r.dispatch_id = d.id
    WHERE d.received_at IS DISTINCT FROM r.confirmed_at;

    SELECT count(*) INTO bad_departure_net
    FROM arrival_weighings a
    JOIN receptions r ON r.id = a.reception_id
    LEFT JOIN departure_weighings w ON w.dispatch_id = r.dispatch_id
    WHERE a.departure_net_kg IS DISTINCT FROM w.net_kg;

    IF bad_receptions + bad_reception_lines + bad_received + bad_received_at + bad_departure_net > 0 THEN
        RAISE EXCEPTION 'Datos que no se pueden derivar: receptions=%, reception_lines=%, order_lines.received_qty=%, dispatches.received_at=%, arrival_weighings.departure_net_kg=%',
            bad_receptions, bad_reception_lines, bad_received, bad_received_at, bad_departure_net;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------
-- Llaves foráneas: solo las del catálogo, simples. Se quitan las compuestas con organization_id.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE order_lines DROP CONSTRAINT fk_order_lines_org_order;
ALTER TABLE dispatch_lines DROP CONSTRAINT fk_dispatch_lines_org_dispatch;
ALTER TABLE departure_weighings DROP CONSTRAINT fk_departure_weighings_org_dispatch;
ALTER TABLE transit_incidents DROP CONSTRAINT fk_transit_incidents_org_dispatch;
ALTER TABLE reception_lines DROP CONSTRAINT fk_reception_lines_org_reception;
ALTER TABLE arrival_weighings DROP CONSTRAINT fk_arrival_weighings_org_reception;
ALTER TABLE orders DROP CONSTRAINT uk_orders_org_id;
ALTER TABLE dispatches DROP CONSTRAINT uk_dispatches_org_id;
ALTER TABLE receptions DROP CONSTRAINT uk_receptions_org_id;

ALTER TABLE order_lines ADD CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders (id);
ALTER TABLE dispatches ADD CONSTRAINT fk_dispatches_order FOREIGN KEY (order_id) REFERENCES orders (id);
ALTER TABLE dispatch_lines ADD CONSTRAINT fk_dispatch_lines_dispatch FOREIGN KEY (dispatch_id) REFERENCES dispatches (id);
ALTER TABLE dispatch_lines ADD CONSTRAINT fk_dispatch_lines_order_line FOREIGN KEY (order_line_id) REFERENCES order_lines (id);
ALTER TABLE departure_weighings ADD CONSTRAINT fk_departure_weighings_dispatch FOREIGN KEY (dispatch_id) REFERENCES dispatches (id);
ALTER TABLE transit_incidents ADD CONSTRAINT fk_transit_incidents_dispatch FOREIGN KEY (dispatch_id) REFERENCES dispatches (id);
ALTER TABLE receptions ADD CONSTRAINT fk_receptions_dispatch FOREIGN KEY (dispatch_id) REFERENCES dispatches (id);
ALTER TABLE reception_lines ADD CONSTRAINT fk_reception_lines_reception FOREIGN KEY (reception_id) REFERENCES receptions (id);
ALTER TABLE reception_lines ADD CONSTRAINT fk_reception_lines_dispatch_line FOREIGN KEY (dispatch_line_id) REFERENCES dispatch_lines (id);
ALTER TABLE arrival_weighings ADD CONSTRAINT fk_arrival_weighings_reception FOREIGN KEY (reception_id) REFERENCES receptions (id);

-- ---------------------------------------------------------------------------------------------------
-- orders: quien pidió y cuándo pasan a la auditoría. La fecha requerida es obligatoria; a los pedidos sin
-- ella se les pone el día en que se crearon.
-- ---------------------------------------------------------------------------------------------------
UPDATE orders SET created_at = placed_at, created_by = requested_by,
                  required_date = COALESCE(required_date, (placed_at AT TIME ZONE 'UTC')::date);
ALTER TABLE orders ALTER COLUMN required_date SET NOT NULL;
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(25);
ALTER TABLE orders DROP COLUMN placed_at;
ALTER TABLE orders DROP COLUMN requested_by;

-- ---------------------------------------------------------------------------------------------------
-- order_lines: SKU y unidad salen de material_projection; lo recibido, de reception_lines.
-- Un material que falte en la copia local se agrega con lo que guardaba la línea, para no perderlo.
-- ---------------------------------------------------------------------------------------------------
INSERT INTO material_projection (material_id, organization_id, sku, name, unit, tolerance_percent, active, updated_at)
SELECT DISTINCT ON (material_id) material_id, organization_id, sku, sku, unit, 0, FALSE, now()
FROM order_lines ORDER BY material_id
ON CONFLICT (material_id) DO NOTHING;

ALTER TABLE order_lines DROP CONSTRAINT ck_order_lines_quantities;
ALTER TABLE order_lines DROP COLUMN sku;
ALTER TABLE order_lines DROP COLUMN unit;
ALTER TABLE order_lines DROP COLUMN received_qty;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_quantities CHECK (
    requested_qty > 0 AND dispatched_qty >= 0 AND cancelled_qty >= 0
    AND dispatched_qty + cancelled_qty <= requested_qty);

-- ---------------------------------------------------------------------------------------------------
-- dispatches: un despacho de retorno no tiene pedido. La preparación pasa a created_at y la recepción se
-- lee de receptions.confirmed_at. departed_at y los datos de la anulación se quedan (DESVIACIONES.md).
-- ---------------------------------------------------------------------------------------------------
UPDATE dispatches SET created_at = prepared_at;
ALTER TABLE dispatches ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE dispatches ALTER COLUMN status TYPE VARCHAR(25);
ALTER TABLE dispatches ALTER COLUMN manifest_code TYPE VARCHAR(40);
ALTER TABLE dispatches DROP COLUMN prepared_at;
ALTER TABLE dispatches DROP COLUMN received_at;
ALTER TABLE dispatches DROP CONSTRAINT ck_dispatches_type;
ALTER TABLE dispatches ADD CONSTRAINT ck_dispatches_type CHECK (type IN ('COMPLETE', 'PARTIAL', 'RETURN'));

ALTER TABLE dispatch_lines ALTER COLUMN order_line_id DROP NOT NULL;

-- La foto del ticket es obligatoria en el ERD, pero el pesaje histórico y la API la admiten vacía: se marca
-- con un valor fijo que la aplicación devuelve como nulo (DESVIACIONES.md).
UPDATE departure_weighings SET ticket_photo_url = 'sin-foto' WHERE ticket_photo_url IS NULL;
ALTER TABLE departure_weighings ALTER COLUMN ticket_photo_url SET NOT NULL;

-- ---------------------------------------------------------------------------------------------------
-- transit_incidents: nombres del ERD y foto opcional.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE transit_incidents RENAME COLUMN reported_at TO created_at;
ALTER TABLE transit_incidents RENAME COLUMN reported_by TO created_by;
ALTER TABLE transit_incidents ADD COLUMN photo_url VARCHAR(255);

-- ---------------------------------------------------------------------------------------------------
-- receptions y reception_lines: lo copiado del despacho se lee con JOIN. partial_count, last_partial_at y
-- stocked_qty se quedan: sin ellos la recepción parcial no sabe qué falta ingresar (DESVIACIONES.md).
-- ---------------------------------------------------------------------------------------------------
DROP INDEX idx_receptions_worksite;
ALTER TABLE receptions DROP COLUMN order_id;
ALTER TABLE receptions DROP COLUMN warehouse_id;
ALTER TABLE receptions DROP COLUMN worksite_id;
ALTER TABLE receptions DROP COLUMN manifest_code;

ALTER TABLE reception_lines DROP CONSTRAINT ck_reception_lines_quantities;
ALTER TABLE reception_lines DROP CONSTRAINT ck_reception_lines_stocked;
ALTER TABLE reception_lines DROP COLUMN order_line_id;
ALTER TABLE reception_lines DROP COLUMN material_id;
ALTER TABLE reception_lines DROP COLUMN dispatched_qty;
ALTER TABLE reception_lines ALTER COLUMN shrinkage_pct TYPE NUMERIC(5, 2);
ALTER TABLE reception_lines ADD CONSTRAINT ck_reception_lines_quantities CHECK (received_qty IS NULL OR received_qty >= 0);
ALTER TABLE reception_lines ADD CONSTRAINT ck_reception_lines_stocked CHECK (stocked_qty >= 0
    AND (received_qty IS NULL OR stocked_qty <= received_qty));

-- arrival_weighings: el neto de salida se lee del pesaje de salida y la tolerancia, de material_projection.
ALTER TABLE arrival_weighings DROP COLUMN departure_net_kg;
ALTER TABLE arrival_weighings DROP COLUMN tolerance_pct;

-- ---------------------------------------------------------------------------------------------------
-- Copias locales. Las obras guardan distrito y ciudad dentro de address, que es lo que muestra el manifiesto;
-- el almacén siempre mostró solo su dirección. Una asignación retirada ya no existe: se borra.
-- location_projection.active se queda (DESVIACIONES.md).
-- ---------------------------------------------------------------------------------------------------
UPDATE location_projection
SET address = NULLIF(left(concat_ws(', ', NULLIF(trim(address), ''), NULLIF(trim(district), ''), NULLIF(trim(city), '')), 200), '')
WHERE type = 'WORKSITE';
ALTER TABLE location_projection DROP COLUMN district;
ALTER TABLE location_projection DROP COLUMN city;

DELETE FROM assignment_projection WHERE NOT active;
DROP INDEX idx_assignment_projection_user;
ALTER TABLE assignment_projection DROP COLUMN active;
CREATE INDEX idx_assignment_projection_user ON assignment_projection (organization_id, user_id);

-- ---------------------------------------------------------------------------------------------------
-- outbox_events: columnas del ERD. El sobre completo del evento (con aggregateType y actorId) va en payload,
-- así lo que llega a Kafka no cambia. correlation_id sí está en el ERD.
-- ---------------------------------------------------------------------------------------------------
UPDATE outbox_events SET payload = jsonb_build_object(
        'eventId', id, 'eventType', event_type,
        'occurredAt', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'organizationId', organization_id, 'aggregateType', aggregate_type, 'aggregateId', aggregate_id,
        'actorId', actor_id, 'version', 1, 'payload', payload);
ALTER TABLE outbox_events DROP COLUMN aggregate_type;
ALTER TABLE outbox_events DROP COLUMN actor_id;
ALTER TABLE outbox_events ALTER COLUMN aggregate_id TYPE UUID USING aggregate_id::uuid;
ALTER TABLE outbox_events ALTER COLUMN event_type TYPE VARCHAR(60);

-- ---------------------------------------------------------------------------------------------------
-- processed_events: la llave incluye al consumidor. Antes no se guardaba quién procesó cada evento, así que
-- se registra para los dos: una reentrega se sigue descartando sin importar de qué tópico venga.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE processed_events ADD COLUMN consumer VARCHAR(60);
UPDATE processed_events SET consumer = 'OrganizationEventConsumer';
ALTER TABLE processed_events ALTER COLUMN consumer SET NOT NULL;
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id, consumer);
INSERT INTO processed_events (event_id, consumer, processed_at)
SELECT event_id, 'InventoryEventConsumer', processed_at FROM processed_events;

-- ---------------------------------------------------------------------------------------------------
-- idempotency_keys: columnas del ERD. response_body guarda en JSON lo necesario para repetir la respuesta y
-- para reconocer la solicitud y a su dueño (DESVIACIONES.md). request_hash en ceros marca un registro antiguo
-- sin huella: no se reproduce, igual que antes.
-- ---------------------------------------------------------------------------------------------------
DROP INDEX idx_idempotency_keys_created_at;
ALTER TABLE idempotency_keys RENAME COLUMN response_body TO legacy_body;
ALTER TABLE idempotency_keys RENAME COLUMN idempotency_key TO key;
ALTER TABLE idempotency_keys ADD COLUMN endpoint VARCHAR(80);
ALTER TABLE idempotency_keys ADD COLUMN request_hash CHAR(64);
ALTER TABLE idempotency_keys ADD COLUMN status VARCHAR(12);
ALTER TABLE idempotency_keys ADD COLUMN response_body JSONB;
ALTER TABLE idempotency_keys ADD COLUMN expires_at TIMESTAMPTZ;
UPDATE idempotency_keys SET
    endpoint = left(request_method || ' ' || regexp_replace(request_path,
        '[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', '{id}', 'g'), 80),
    request_hash = COALESCE(request_fingerprint, repeat('0', 64)),
    status = CASE WHEN response_expired THEN 'EXPIRED' ELSE 'COMPLETED' END,
    expires_at = created_at + INTERVAL '24 hours',
    response_body = jsonb_strip_nulls(jsonb_build_object(
        'method', request_method, 'path', request_path, 'status', response_status,
        'userId', user_id, 'role', actor_role,
        'contentType', CASE WHEN response_expired THEN NULL ELSE response_content_type END,
        'location', CASE WHEN response_expired THEN NULL ELSE response_location END,
        'bodyBase64', CASE WHEN response_expired THEN NULL ELSE replace(encode(legacy_body, 'base64'), E'\n', '') END));
ALTER TABLE idempotency_keys ALTER COLUMN endpoint SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN request_hash SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN status SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (key, organization_id);
ALTER TABLE idempotency_keys DROP COLUMN legacy_body;
ALTER TABLE idempotency_keys DROP COLUMN request_method;
ALTER TABLE idempotency_keys DROP COLUMN request_path;
ALTER TABLE idempotency_keys DROP COLUMN response_status;
ALTER TABLE idempotency_keys DROP COLUMN response_content_type;
ALTER TABLE idempotency_keys DROP COLUMN created_at;
ALTER TABLE idempotency_keys DROP COLUMN user_id;
ALTER TABLE idempotency_keys DROP COLUMN actor_role;
ALTER TABLE idempotency_keys DROP COLUMN request_fingerprint;
ALTER TABLE idempotency_keys DROP COLUMN response_location;
ALTER TABLE idempotency_keys DROP COLUMN response_expired;
ALTER TABLE idempotency_keys ADD CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('COMPLETED', 'EXPIRED'));
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
