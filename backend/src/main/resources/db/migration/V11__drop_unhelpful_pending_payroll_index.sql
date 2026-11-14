-- V10 did not change the claim plan: PostgreSQL continued scanning payroll items in sequence order
-- and probing the original pending-result index for each item. Remove its write overhead until a
-- separately measured query shape demonstrates a reliable benefit.
DROP INDEX IF EXISTS ix_payroll_item_results_pending_batch_item;
