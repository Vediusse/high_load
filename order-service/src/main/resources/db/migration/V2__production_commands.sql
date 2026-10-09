CREATE TABLE production_command (
    command_id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES corporate_order(id),
    action varchar(32) NOT NULL CHECK (action IN ('START_COOKING', 'MARK_READY', 'COMPLETE')),
    expected_status varchar(32) NOT NULL,
    expected_version bigint NOT NULL CHECK (expected_version >= 0),
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((action = 'START_COOKING' AND expected_status = 'CONFIRMED')
        OR (action = 'MARK_READY' AND expected_status = 'IN_COOKING')
        OR (action = 'COMPLETE' AND expected_status = 'READY'))
);
CREATE INDEX production_command_order_idx ON production_command(order_id);
