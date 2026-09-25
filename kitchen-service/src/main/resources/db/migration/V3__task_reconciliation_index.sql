CREATE INDEX kitchen_task_reconciliation_idx ON kitchen_task(updated_at, order_id)
    WHERE status IN ('CONFIRMED', 'IN_COOKING', 'READY');
