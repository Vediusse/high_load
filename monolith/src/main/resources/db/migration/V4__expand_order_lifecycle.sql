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
