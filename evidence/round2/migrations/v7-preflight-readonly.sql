-- Read-only copy of V7's preflight counts (V7__harden_payroll_intent_and_result_integrity.sql). It answers, without
-- changing anything, whether an existing V6 database would be accepted by V7. Run in a read-only session.
\pset footer off
SELECT current_database() AS database, current_setting('transaction_read_only') AS read_only,
       (SELECT max(version) FROM flyway_schema_history WHERE success) AS flyway_version,
       (SELECT count(*) FROM payroll_batches) AS batches, (SELECT count(*) FROM payroll_items) AS items,
       (SELECT count(*) FROM payroll_item_results) AS results;
SELECT
  (SELECT count(*) FROM payroll_items i
    WHERE NOT EXISTS (SELECT 1 FROM payroll_item_results r WHERE r.item_id = i.id)) AS items_without_results,
  (SELECT count(*) FROM payroll_batches b
    WHERE b.status <> 'DRAFT' AND NOT EXISTS (SELECT 1 FROM payroll_items i WHERE i.batch_id = b.id))
      AS non_draft_batches_without_items,
  (SELECT count(*) FROM (
      SELECT b.status,
             count(r.item_id) FILTER (WHERE r.status = 'PENDING') AS p,
             count(r.item_id) FILTER (WHERE r.status = 'SUCCEEDED') AS s,
             count(r.item_id) FILTER (WHERE r.status = 'FAILED') AS f
        FROM payroll_batches b JOIN payroll_items i ON i.batch_id = b.id JOIN payroll_item_results r ON r.item_id = i.id
       WHERE b.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED') GROUP BY b.id, b.status) t
    WHERE t.p > 0 OR (t.status = 'COMPLETED' AND t.f > 0) OR (t.status = 'FAILED' AND t.s > 0)
       OR (t.status = 'PARTIALLY_COMPLETED' AND (t.s = 0 OR t.f = 0))) AS inconsistent_terminal_batches,
  (SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
     JOIN payroll_batches b ON b.id = i.batch_id
    WHERE r.status = 'SUCCEEDED' AND NOT EXISTS (
          SELECT 1 FROM transfers t JOIN idempotency_records k ON k.transfer_id = t.id
           WHERE t.id = r.transfer_id AND t.source_account_id = b.payer_account_id
             AND t.target_account_id = i.payee_account_id AND t.amount_minor = i.amount_minor
             AND t.owner_customer_id = b.created_by_customer_id AND t.source_organization_id = b.organization_id
             AND k.owner_customer_id = b.created_by_customer_id AND k.operation = 'TRANSFER'
             AND k.idempotency_key = 'payroll-' || b.id || '-' || i.id)) AS unlinked_successes;
