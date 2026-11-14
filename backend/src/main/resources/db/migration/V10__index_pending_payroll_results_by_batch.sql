-- Keep the queue index proportional to work still available in each batch. V1-V9 remain immutable.
CREATE INDEX ix_payroll_item_results_pending_batch_item
    ON payroll_item_results (batch_id, item_id)
    WHERE status = 'PENDING';
