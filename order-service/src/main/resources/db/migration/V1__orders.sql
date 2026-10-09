COMMENT ON SCHEMA public IS 'Order service schema';
CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_organization_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_organization_phone_format CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE TABLE delivery_point (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) NOT NULL,
    contact_name VARCHAR(200) NOT NULL,
    contact_phone VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_delivery_point_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT uq_delivery_point_organization_name UNIQUE (organization_id, name),
    CONSTRAINT ck_delivery_point_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_delivery_point_address_not_blank CHECK (btrim(address) <> ''),
    CONSTRAINT ck_delivery_point_contact_name_not_blank CHECK (btrim(contact_name) <> ''),
    CONSTRAINT ck_delivery_point_phone_format CHECK (contact_phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE TABLE corporate_order (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    delivery_point_id UUID NOT NULL,
    requested_delivery_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_corporate_order_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_corporate_order_delivery_point
        FOREIGN KEY (delivery_point_id) REFERENCES delivery_point (id) ON DELETE RESTRICT,
    CONSTRAINT ck_corporate_order_status CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT ck_corporate_order_total_non_negative CHECK (total_amount >= 0)
);

CREATE TABLE order_line (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    dish_id UUID NOT NULL,
    dish_name_snapshot VARCHAR(200),
    quantity INTEGER NOT NULL,
    unit_price_snapshot NUMERIC(19, 2),
    CONSTRAINT fk_order_line_order
        FOREIGN KEY (order_id) REFERENCES corporate_order (id) ON DELETE RESTRICT,
    CONSTRAINT uq_order_line_order_dish UNIQUE (order_id, dish_id),
    CONSTRAINT ck_order_line_quantity CHECK (quantity BETWEEN 1 AND 100000),
    CONSTRAINT ck_order_line_price_snapshot
        CHECK (unit_price_snapshot IS NULL OR unit_price_snapshot > 0),
    CONSTRAINT ck_order_line_snapshots_together
        CHECK ((dish_name_snapshot IS NULL) = (unit_price_snapshot IS NULL))
);

CREATE TABLE order_status_history (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_order_status_history_order
        FOREIGN KEY (order_id) REFERENCES corporate_order (id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_status_history_from CHECK (from_status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT ck_order_status_history_to CHECK (to_status IN ('DRAFT', 'SUBMITTED'))
);

CREATE INDEX idx_corporate_order_organization_created
    ON corporate_order (organization_id, created_at DESC, id DESC);
CREATE INDEX idx_corporate_order_status_delivery
    ON corporate_order (status, requested_delivery_at, id);
CREATE INDEX idx_corporate_order_delivery_point ON corporate_order (delivery_point_id);
CREATE INDEX idx_order_line_order ON order_line (order_id);
CREATE INDEX idx_order_status_history_order_changed
    ON order_status_history (order_id, changed_at, id);

CREATE FUNCTION reject_order_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'order_status_history is append-only';
END;
$$;

CREATE TRIGGER trg_order_status_history_append_only
BEFORE UPDATE OR DELETE ON order_status_history
FOR EACH ROW
EXECUTE FUNCTION reject_order_status_history_mutation();
ALTER TABLE corporate_order
    DROP CONSTRAINT ck_corporate_order_status;

ALTER TABLE corporate_order
    ADD CONSTRAINT ck_corporate_order_status CHECK (status IN (
        'DRAFT',
        'SUBMITTED',
        'CONFIRMED',
        'REJECTED',
        'CANCELLED',
        'IN_COOKING',
        'READY',
        'COMPLETED'
    ));

ALTER TABLE order_status_history
    DROP CONSTRAINT ck_order_status_history_from,
    DROP CONSTRAINT ck_order_status_history_to;

ALTER TABLE order_status_history
    ADD CONSTRAINT ck_order_status_history_from CHECK (from_status IN (
        'DRAFT',
        'SUBMITTED',
        'CONFIRMED',
        'REJECTED',
        'CANCELLED',
        'IN_COOKING',
        'READY',
        'COMPLETED'
    )),
    ADD CONSTRAINT ck_order_status_history_to CHECK (to_status IN (
        'DRAFT',
        'SUBMITTED',
        'CONFIRMED',
        'REJECTED',
        'CANCELLED',
        'IN_COOKING',
        'READY',
        'COMPLETED'
    )),
    ADD CONSTRAINT ck_order_status_history_transition CHECK (
        (from_status = 'DRAFT' AND to_status IN ('SUBMITTED', 'CANCELLED'))
        OR (from_status = 'SUBMITTED' AND to_status IN ('CONFIRMED', 'REJECTED', 'CANCELLED'))
        OR (from_status = 'CONFIRMED' AND to_status IN ('IN_COOKING', 'CANCELLED'))
        OR (from_status = 'IN_COOKING' AND to_status = 'READY')
        OR (from_status = 'READY' AND to_status = 'COMPLETED')
    ),
    ADD CONSTRAINT ck_order_status_history_reason CHECK (
        (to_status IN ('REJECTED', 'CANCELLED') AND reason IS NOT NULL AND btrim(reason) <> '')
        OR (to_status NOT IN ('REJECTED', 'CANCELLED') AND reason IS NULL)
    );
