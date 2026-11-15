-- E12 independent financial grader (read-only). Run against a bank database after any E12 case:
--   psql -X -q -t -A -v ON_ERROR_STOP=1 -h 127.0.0.1 -p <port> -U <role> -d <database> -f financial-hard-gates.sql
-- Every value below must be 0 for the hard gates to pass. It reads PostgreSQL facts only; no application code, HTTP
-- response or test exit code contributes to the verdict.
WITH ledger AS (
    SELECT la.id, la.kind, la.normal_side,
           COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0) AS ledger_minor,
           proj.balance_minor AS projection_minor
    FROM ledger_accounts la
    LEFT JOIN postings p ON p.ledger_account_id = la.id
    LEFT JOIN account_balance_projection proj ON proj.ledger_account_id = la.id
    GROUP BY la.id, la.kind, la.normal_side, proj.balance_minor
),
payment_journals AS (
    SELECT substring(reference FROM '^corporate-payment:([0-9a-f-]{36}):') AS payment_id,
           substring(reference FROM ':(booking|settlement|refund)$') AS action, count(*) AS journals
    FROM journal_entries WHERE reference LIKE 'corporate-payment:%'
    GROUP BY 1, 2
)
SELECT json_build_object(
  'database', current_database(),
  'capturedAt', clock_timestamp(),
  'journals', (SELECT count(*) FROM journal_entries),
  'postings', (SELECT count(*) FROM postings),
  'unbalancedJournals', (SELECT count(*) FROM (
      SELECT j.id FROM journal_entries j LEFT JOIN postings p ON p.journal_entry_id = j.id GROUP BY j.id
      HAVING count(p.id) < 2
          OR COALESCE(sum(p.amount_minor) FILTER (WHERE p.side = 'DEBIT'), 0)
             <> COALESCE(sum(p.amount_minor) FILTER (WHERE p.side = 'CREDIT'), 0)) x),
  'nonPositiveOrForeignCurrencyPostings', (SELECT count(*) FROM postings WHERE amount_minor <= 0 OR currency <> 'NZD'),
  'projectionMismatches', (SELECT count(*) FROM ledger WHERE projection_minor IS DISTINCT FROM ledger_minor),
  'negativeNormalBalances', (SELECT count(*) FROM ledger WHERE ledger_minor < 0),
  'balanceSheetDifferenceMinor', (SELECT COALESCE(sum(ledger_minor) FILTER (WHERE normal_side = 'DEBIT'), 0)
                                        - COALESCE(sum(ledger_minor) FILTER (WHERE normal_side = 'CREDIT'), 0) FROM ledger),
  'duplicatePaymentJournals', (SELECT count(*) FROM payment_journals WHERE journals > 1),
  'completedWithoutSettlement', (SELECT count(*) FROM corporate_payment_intents
      WHERE status = 'COMPLETED' AND (settlement_journal_entry_id IS NULL OR external_status IS DISTINCT FROM 'SETTLED')),
  'settlementWithoutSettledOutcome', (SELECT count(*) FROM corporate_payment_intents
      WHERE settlement_journal_entry_id IS NOT NULL AND (external_status IS DISTINCT FROM 'SETTLED' OR status <> 'COMPLETED')),
  'refundAfterSettlementOrWithoutRejection', (SELECT count(*) FROM corporate_payment_intents
      WHERE reversal_journal_entry_id IS NOT NULL
        AND (settlement_journal_entry_id IS NOT NULL OR external_status IS DISTINCT FROM 'REJECTED' OR status <> 'REJECTED')),
  'bookedWithoutIndependentApproval', (SELECT count(*) FROM corporate_payment_intents
      WHERE booking_journal_entry_id IS NOT NULL
        AND (approved_by_customer_id IS NULL OR approved_by_customer_id = created_by_customer_id
             OR approved_by_customer_id = submitted_by_customer_id OR approved_digest IS DISTINCT FROM intent_digest)),
  'bookedPaymentJournalMismatches', (SELECT count(*) FROM corporate_payment_intents p
      WHERE p.booking_journal_entry_id IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM postings d JOIN ledger_accounts la ON la.id = d.ledger_account_id
          WHERE d.journal_entry_id = p.booking_journal_entry_id AND d.side = 'DEBIT' AND d.amount_minor = p.amount_minor
            AND la.customer_account_id = p.source_account_id)),
  'transfersWithoutExactJournal', (SELECT count(*) FROM transfers t WHERE (
      SELECT count(*) FROM postings p WHERE p.journal_entry_id = t.journal_entry_id AND p.amount_minor = t.amount_minor) <> 2),
  'transfersWithoutOneSucceededReceipt', (SELECT count(*) FROM transfers t WHERE (
      SELECT count(*) FROM idempotency_records r WHERE r.transfer_id = t.id AND r.status = 'SUCCEEDED') <> 1),
  'payrollSucceededWithoutTransfer', (SELECT count(*) FROM payroll_item_results WHERE status = 'SUCCEEDED' AND transfer_id IS NULL),
  'payrollItemsPaidMoreThanOnce', (SELECT count(*) FROM (SELECT r.transfer_id FROM payroll_item_results r
      WHERE r.transfer_id IS NOT NULL GROUP BY r.transfer_id HAVING count(*) > 1) x),
  'completedPayrollWithUnfinishedItems', (SELECT count(*) FROM payroll_batches b WHERE b.status = 'COMPLETED' AND EXISTS (
      SELECT 1 FROM payroll_item_results r WHERE r.batch_id = b.id AND r.status <> 'SUCCEEDED')),
  'callbackInboxDuplicateSequences', (SELECT count(*) FROM (SELECT operation_id, sequence_no FROM clearing_callback_inbox
      GROUP BY operation_id, sequence_no HAVING count(*) > 1) x),
  'deliveredWorkMaskingOwedEffect', (SELECT count(*) FROM corporate_payment_intents p
      WHERE p.status IN ('PENDING_CLEARING', 'UNKNOWN')
        AND ((p.external_status = 'SETTLED' AND p.settlement_journal_entry_id IS NULL)
          OR (p.external_status = 'REJECTED' AND p.reversal_journal_entry_id IS NULL))
        AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.aggregate_id = p.operation_id
                          AND e.event_type IN ('clearing.callback.accepted', 'clearing.financial-effect.backfill')
                          AND e.status IN ('PENDING', 'PROCESSING', 'DEAD'))),
  'informationalOwedEffects', (SELECT count(*) FROM corporate_payment_intents p
      WHERE p.status IN ('PENDING_CLEARING', 'UNKNOWN')
        AND ((p.external_status = 'SETTLED' AND p.settlement_journal_entry_id IS NULL)
          OR (p.external_status = 'REJECTED' AND p.reversal_journal_entry_id IS NULL))),
  'informationalReserveMinor', (SELECT ledger_minor FROM ledger WHERE id = '00000000-0000-5000-8000-000000000001'),
  'informationalPayableMinor', (SELECT ledger_minor FROM ledger WHERE id = '00000000-0000-5000-8000-000000000002')
)::text;
