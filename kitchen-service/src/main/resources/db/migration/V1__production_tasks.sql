CREATE TABLE production_task (
    order_id uuid PRIMARY KEY,
    status varchar(32) NOT NULL CHECK (status IN ('CONFIRMED', 'IN_COOKING', 'READY', 'COMPLETED', 'CANCELLED')),
    order_version bigint NOT NULL CHECK (order_version >= 0),
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0)
);
CREATE INDEX production_task_active_idx ON production_task(order_id)
    WHERE status IN ('CONFIRMED', 'IN_COOKING', 'READY');
