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
    CONSTRAINT fk_order_line_dish
        FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE RESTRICT,
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
