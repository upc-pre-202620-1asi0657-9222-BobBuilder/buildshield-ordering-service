-- Gestión de Pedidos y Entregas (ordering_db). Portado de V10, V17 y V18 del Core (esquemas ordering,
-- dispatch y reception) más los borradores del Sprint 3 (V19 y V20), ahora en el esquema public de su
-- propia base. worksite_id, warehouse_id, material_id y los usuarios viven en otros servicios: sin llave
-- foránea; sus datos llegan por eventos a las tablas *_projection.

-- ---------------------------------------------------------------------------------------------------
-- Pedidos (US18, US20, US21) y saga de reserva de stock (APPROVAL_PENDING mientras inventario responde).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE orders (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL,
    worksite_id      UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    requested_by     UUID         NOT NULL,
    notes            VARCHAR(500),
    required_date    DATE,
    placed_at        TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    rejection_reason VARCHAR(500),
    decided_by       UUID,
    decided_at       TIMESTAMPTZ,
    version          BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID         NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       UUID         NOT NULL,
    CONSTRAINT uk_orders_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_orders_status CHECK (status IN ('REGISTERED', 'APPROVAL_PENDING', 'IN_REVIEW', 'PARTIALLY_FULFILLED',
                                                  'FULFILLED', 'CLOSED', 'CANCELLED'))
);

CREATE INDEX idx_orders_worksite ON orders (organization_id, worksite_id);
CREATE INDEX idx_orders_warehouse ON orders (organization_id, warehouse_id);

CREATE TABLE order_lines (
    id              UUID PRIMARY KEY,
    organization_id UUID           NOT NULL,
    order_id        UUID           NOT NULL,
    material_id     UUID           NOT NULL,
    sku             VARCHAR(40)    NOT NULL,
    unit            VARCHAR(10)    NOT NULL,
    requested_qty   NUMERIC(14, 3) NOT NULL,
    dispatched_qty  NUMERIC(14, 3) NOT NULL,
    cancelled_qty   NUMERIC(14, 3) NOT NULL,
    received_qty    NUMERIC(14, 3) NOT NULL,
    CONSTRAINT uk_order_lines_material UNIQUE (order_id, material_id),
    CONSTRAINT fk_order_lines_org_order FOREIGN KEY (organization_id, order_id) REFERENCES orders (organization_id, id),
    -- pendiente = solicitado - despachado - cancelado, nunca negativo.
    CONSTRAINT ck_order_lines_quantities CHECK (
        requested_qty > 0 AND dispatched_qty >= 0 AND cancelled_qty >= 0 AND received_qty >= 0
        AND dispatched_qty + cancelled_qty <= requested_qty AND received_qty <= dispatched_qty)
);

-- ---------------------------------------------------------------------------------------------------
-- Despachos (US22, US23, US24, US27, US30) con anulación (US25) e incidencias en ruta (US32).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dispatches (
    id                  UUID PRIMARY KEY,
    organization_id     UUID         NOT NULL,
    order_id            UUID         NOT NULL,
    warehouse_id        UUID         NOT NULL,
    worksite_id         UUID         NOT NULL,
    type                VARCHAR(10)  NOT NULL,
    status              VARCHAR(15)  NOT NULL,
    manifest_code       VARCHAR(30)  NOT NULL,
    carrier_name        VARCHAR(150),
    carrier_document    VARCHAR(20),
    plate               VARCHAR(10),
    prepared_at         TIMESTAMPTZ  NOT NULL,
    departed_at         TIMESTAMPTZ,
    received_at         TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancelled_by        UUID,
    cancellation_reason VARCHAR(300),
    version             BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          UUID         NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          UUID         NOT NULL,
    CONSTRAINT uk_dispatches_manifest_code UNIQUE (manifest_code),
    CONSTRAINT uk_dispatches_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_dispatches_type CHECK (type IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_dispatches_status CHECK (status IN ('PREPARED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')),
    -- Un despacho no sale sin transportista.
    CONSTRAINT ck_dispatches_carrier CHECK (status IN ('PREPARED', 'CANCELLED')
        OR (carrier_name IS NOT NULL AND carrier_document IS NOT NULL AND plate IS NOT NULL AND departed_at IS NOT NULL)),
    -- Un despacho anulado guarda quién, cuándo y por qué.
    CONSTRAINT ck_dispatches_cancellation CHECK (status <> 'CANCELLED'
        OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND cancellation_reason IS NOT NULL))
);

CREATE INDEX idx_dispatches_order ON dispatches (organization_id, order_id);
CREATE INDEX idx_dispatches_warehouse ON dispatches (organization_id, warehouse_id);
CREATE INDEX idx_dispatches_worksite ON dispatches (organization_id, worksite_id);

CREATE TABLE dispatch_lines (
    id              UUID PRIMARY KEY,
    organization_id UUID           NOT NULL,
    dispatch_id     UUID           NOT NULL,
    order_line_id   UUID           NOT NULL,
    material_id     UUID           NOT NULL,
    quantity        NUMERIC(14, 3) NOT NULL,
    CONSTRAINT uk_dispatch_lines_order_line UNIQUE (dispatch_id, order_line_id),
    CONSTRAINT fk_dispatch_lines_org_dispatch
        FOREIGN KEY (organization_id, dispatch_id) REFERENCES dispatches (organization_id, id),
    CONSTRAINT ck_dispatch_lines_quantity CHECK (quantity > 0)
);

-- Pesaje de salida: uno por despacho. La foto del ticket es una referencia a la evidencia guardada.
CREATE TABLE departure_weighings (
    id               UUID PRIMARY KEY,
    organization_id  UUID           NOT NULL,
    dispatch_id      UUID           NOT NULL,
    gross_kg         NUMERIC(12, 3) NOT NULL,
    tare_kg          NUMERIC(12, 3) NOT NULL,
    net_kg           NUMERIC(12, 3) NOT NULL,
    ticket_photo_url VARCHAR(500),
    weighed_at       TIMESTAMPTZ    NOT NULL,
    weighed_by       UUID           NOT NULL,
    CONSTRAINT uk_departure_weighings_dispatch UNIQUE (dispatch_id),
    CONSTRAINT fk_departure_weighings_org_dispatch
        FOREIGN KEY (organization_id, dispatch_id) REFERENCES dispatches (organization_id, id),
    CONSTRAINT ck_departure_weighings_weights CHECK (gross_kg > 0 AND tare_kg >= 0 AND net_kg > 0
        AND net_kg = gross_kg - tare_kg)
);

-- Incidencias registradas mientras el despacho está en tránsito; no se modifican.
CREATE TABLE transit_incidents (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    dispatch_id     UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    observation     VARCHAR(500) NOT NULL,
    reported_by     UUID         NOT NULL,
    reported_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_transit_incidents_org_dispatch
        FOREIGN KEY (organization_id, dispatch_id) REFERENCES dispatches (organization_id, id),
    CONSTRAINT ck_transit_incidents_type CHECK (type IN ('DELAY', 'VEHICLE_FAILURE', 'DAMAGE', 'ACCIDENT',
                                                         'ROUTE_CHANGE', 'OTHER'))
);

CREATE INDEX idx_transit_incidents_dispatch ON transit_incidents (organization_id, dispatch_id, reported_at);

-- ---------------------------------------------------------------------------------------------------
-- Recepción en obra (US35, US37, QAS01, QAS10), recepción parcial (US38) y pesaje de llegada (US29).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE receptions (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    dispatch_id     UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    warehouse_id    UUID        NOT NULL,
    worksite_id     UUID        NOT NULL,
    manifest_code   VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    partial_count   INTEGER     NOT NULL DEFAULT 0,
    last_partial_at TIMESTAMPTZ,
    confirmed_by    UUID,
    confirmed_at    TIMESTAMPTZ,
    version         BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      UUID        NOT NULL,
    -- Una sola recepción por despacho (QAS10).
    CONSTRAINT uk_receptions_dispatch UNIQUE (dispatch_id),
    CONSTRAINT uk_receptions_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_receptions_status CHECK (status IN ('IN_PROGRESS', 'PARTIALLY_RECEIVED', 'CONFIRMED')),
    CONSTRAINT ck_receptions_partial_count CHECK (partial_count >= 0),
    CONSTRAINT ck_receptions_confirmation CHECK (status <> 'CONFIRMED'
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL))
);

CREATE INDEX idx_receptions_worksite ON receptions (organization_id, worksite_id);

-- stocked_qty: lo que ya se sumó a la obra en parciales; la conformidad suma solo lo que falta.
CREATE TABLE reception_lines (
    id               UUID PRIMARY KEY,
    organization_id  UUID           NOT NULL,
    reception_id     UUID           NOT NULL,
    dispatch_line_id UUID           NOT NULL,
    order_line_id    UUID           NOT NULL,
    material_id      UUID           NOT NULL,
    dispatched_qty   NUMERIC(14, 3) NOT NULL,
    received_qty     NUMERIC(14, 3),
    stocked_qty      NUMERIC(14, 3) NOT NULL DEFAULT 0,
    shrinkage_pct    NUMERIC(6, 2),
    CONSTRAINT uk_reception_lines_dispatch_line UNIQUE (reception_id, dispatch_line_id),
    CONSTRAINT fk_reception_lines_org_reception
        FOREIGN KEY (organization_id, reception_id) REFERENCES receptions (organization_id, id),
    CONSTRAINT ck_reception_lines_quantities CHECK (dispatched_qty > 0
        AND (received_qty IS NULL OR (received_qty >= 0 AND received_qty <= dispatched_qty))),
    CONSTRAINT ck_reception_lines_stocked CHECK (stocked_qty >= 0 AND stocked_qty <= dispatched_qty
        AND (received_qty IS NULL OR stocked_qty <= received_qty))
);

-- Pesaje de llegada: uno por recepción, comparado con el neto de salida.
CREATE TABLE arrival_weighings (
    id               UUID PRIMARY KEY,
    organization_id  UUID           NOT NULL,
    reception_id     UUID           NOT NULL,
    gross_kg         NUMERIC(12, 3) NOT NULL,
    tare_kg          NUMERIC(12, 3) NOT NULL,
    net_kg           NUMERIC(12, 3) NOT NULL,
    departure_net_kg NUMERIC(12, 3),
    tolerance_pct    NUMERIC(5, 2)  NOT NULL,
    ticket_photo_url VARCHAR(500),
    weighed_at       TIMESTAMPTZ    NOT NULL,
    weighed_by       UUID           NOT NULL,
    CONSTRAINT uk_arrival_weighings_reception UNIQUE (reception_id),
    CONSTRAINT fk_arrival_weighings_org_reception
        FOREIGN KEY (organization_id, reception_id) REFERENCES receptions (organization_id, id),
    CONSTRAINT ck_arrival_weighings_weights CHECK (gross_kg > 0 AND tare_kg >= 0 AND net_kg > 0
        AND net_kg = gross_kg - tare_kg)
);

-- ---------------------------------------------------------------------------------------------------
-- Copias locales (proyecciones) de organization, alimentadas por buildshield.organization. No son outbox.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE location_projection (
    location_id     UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    address         VARCHAR(200),
    district        VARCHAR(100),
    city            VARCHAR(100),
    active          BOOLEAN      NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_location_projection_type CHECK (type IN ('WORKSITE', 'WAREHOUSE'))
);

CREATE INDEX idx_location_projection_organization ON location_projection (organization_id);

CREATE TABLE material_projection (
    material_id       UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL,
    sku               VARCHAR(40)   NOT NULL,
    name              VARCHAR(150)  NOT NULL,
    unit              VARCHAR(10)   NOT NULL,
    tolerance_percent NUMERIC(5, 2) NOT NULL,
    active            BOOLEAN       NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_material_projection_organization ON material_projection (organization_id);

CREATE TABLE assignment_projection (
    assignment_id   UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    location_id     UUID        NOT NULL,
    location_type   VARCHAR(20) NOT NULL,
    active          BOOLEAN     NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_assignment_projection_user ON assignment_projection (organization_id, user_id) WHERE active;

-- ---------------------------------------------------------------------------------------------------
-- Kernel compartido: idempotencia HTTP (Idempotency-Key), Transactional Outbox y consumidor idempotente.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    organization_id       UUID         NOT NULL,
    idempotency_key       UUID         NOT NULL,
    request_method        VARCHAR(10)  NOT NULL,
    request_path          VARCHAR(500) NOT NULL,
    response_status       INT          NOT NULL,
    response_content_type VARCHAR(200),
    response_body         BYTEA,
    created_at            TIMESTAMPTZ  NOT NULL,
    user_id               UUID,
    actor_role            VARCHAR(50),
    request_fingerprint   VARCHAR(64),
    response_location     VARCHAR(500),
    response_expired      BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (organization_id, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);

-- Transactional Outbox: eventos escritos en la misma transacción que el cambio de negocio;
-- OrderingOutboxPublisher los envía a buildshield.ordering y marca published_at.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    organization_id UUID         NOT NULL,
    actor_id        UUID,
    correlation_id  VARCHAR(64),
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_pending ON outbox_events (occurred_at, id) WHERE published_at IS NULL;

-- Consumidor idempotente: un evento se procesa una sola vez.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
