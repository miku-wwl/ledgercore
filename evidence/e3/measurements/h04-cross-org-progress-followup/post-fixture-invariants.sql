\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() <> 'ledgercore_e3_perf' THEN
        RAISE EXCEPTION 'H04 validation is restricted to ledgercore_e3_perf';
    END IF;
END
$$;

SELECT current_database() AS database_name,
       (SELECT count(*) FROM organizations WHERE display_name LIKE 'E3 H04 Org %') AS h04_organizations,
       (SELECT count(*) FROM payroll_batches WHERE name LIKE 'E3 H04 Batch %' AND status = 'DRAFT') AS h04_draft_batches,
       (SELECT count(*) FROM payroll_items i JOIN payroll_batches b ON b.id = i.batch_id
         WHERE b.name LIKE 'E3 H04 Batch %') AS h04_items,
       (SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
         JOIN payroll_batches b ON b.id = i.batch_id
         WHERE b.name LIKE 'E3 H04 Batch %' AND r.status = 'PENDING') AS h04_pending_results,
       (SELECT count(*) FROM postings p JOIN ledger_accounts la ON la.id = p.ledger_account_id
         JOIN accounts a ON a.id = la.customer_account_id
         WHERE a.display_name LIKE 'E3 H04 query fixture payer %') AS h04_postings;

WITH balances AS (
    SELECT la.id,
           COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0) AS ledger_minor
    FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
    GROUP BY la.id, la.normal_side
), unbalanced AS (
    SELECT j.id
    FROM journal_entries j LEFT JOIN postings p ON p.journal_entry_id = j.id
    GROUP BY j.id
    HAVING count(p.id) < 2 OR
           COALESCE(sum(p.amount_minor) FILTER (WHERE p.side = 'DEBIT'), 0) <>
           COALESCE(sum(p.amount_minor) FILTER (WHERE p.side = 'CREDIT'), 0)
)
SELECT json_build_object(
    'unbalancedJournalEntries', (SELECT count(*) FROM unbalanced),
    'projectionMismatches', (SELECT count(*) FROM balances b
        JOIN account_balance_projection p ON p.ledger_account_id = b.id
        WHERE b.ledger_minor <> p.balance_minor),
    'negativeLedgerBalances', (SELECT count(*) FROM balances WHERE ledger_minor < 0),
    'transferReceiptMismatches', (SELECT count(*) FROM transfers t
        JOIN journal_entries j ON j.id = t.journal_entry_id
        WHERE j.entry_type <> 'TRANSFER' OR
        (SELECT count(*) FROM postings p WHERE p.journal_entry_id = j.id) <> 2 OR
        (SELECT count(*) FROM idempotency_records r WHERE r.transfer_id = t.id
          AND r.status = 'SUCCEEDED' AND r.response_payload->>'transferId' = t.id::text) <> 1),
    'duplicateIdempotencyKeys', (SELECT count(*) FROM (
        SELECT owner_customer_id, operation, idempotency_key
        FROM idempotency_records GROUP BY owner_customer_id, operation, idempotency_key
        HAVING count(*) > 1) duplicates)
) AS global_financial_invariants;
