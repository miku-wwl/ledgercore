-- Run only in the disposable ledgercore_perf database after baseline queries.
-- Each comparison drops one application's read index, measures the same query,
-- then recreates the original index before proceeding.
\set ON_ERROR_STOP on
DO $guard$
BEGIN
    IF current_database() <> 'ledgercore_perf' OR current_schema() <> 'public' THEN
        RAISE EXCEPTION 'Refusing to change indexes outside public.ledgercore_perf';
    END IF;
    IF to_regclass('public.ix_accounts_owner_created') IS NULL
       OR to_regclass('public.ix_transfers_source_created') IS NULL
       OR to_regclass('public.ix_transfers_target_created') IS NULL
       OR to_regclass('public.ix_postings_ledger_account') IS NULL THEN
        RAISE EXCEPTION 'Expected V1-V4 performance indexes are not all present';
    END IF;
END
$guard$;

BEGIN;

\echo 'Account list without ix_accounts_owner_created'
DROP INDEX ix_accounts_owner_created;
ANALYZE accounts;
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.id, a.display_name, a.status, a.currency, p.balance_minor, a.created_at
FROM accounts a
JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
JOIN account_balance_projection p ON p.ledger_account_id = la.id
WHERE a.owner_customer_id = md5('perf-customer-0001')::uuid
ORDER BY a.created_at DESC, a.id DESC;
CREATE INDEX ix_accounts_owner_created ON accounts (owner_customer_id, created_at DESC, id DESC);
ANALYZE accounts;

\echo 'Transaction history without the source/target created-at indexes'
DROP INDEX ix_transfers_source_created;
DROP INDEX ix_transfers_target_created;
ANALYZE transfers;
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.source_account_id, t.target_account_id, t.amount_minor, t.currency,
       t.created_at, source.display_name AS source_name, target.display_name AS target_name
FROM transfers t
JOIN accounts current_account
  ON current_account.id = md5('perf-account-000001')::uuid
 AND current_account.owner_customer_id = md5('perf-customer-0001')::uuid
JOIN accounts source ON source.id = t.source_account_id
JOIN accounts target ON target.id = t.target_account_id
WHERE (t.source_account_id = md5('perf-account-000001')::uuid
       OR t.target_account_id = md5('perf-account-000001')::uuid)
ORDER BY t.created_at DESC, t.id DESC
LIMIT 51;
CREATE INDEX ix_transfers_source_created ON transfers (source_account_id, created_at DESC, id DESC);
CREATE INDEX ix_transfers_target_created ON transfers (target_account_id, created_at DESC, id DESC);
ANALYZE transfers;

\echo 'Ledger balance without ix_postings_ledger_account'
DROP INDEX ix_postings_ledger_account;
ANALYZE postings;
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side
                         THEN p.amount_minor ELSE -p.amount_minor END), 0)
FROM ledger_accounts la
LEFT JOIN postings p ON p.ledger_account_id = la.id
WHERE la.id = md5('perf-ledger-000001')::uuid
GROUP BY la.id, la.normal_side;
CREATE INDEX ix_postings_ledger_account ON postings (ledger_account_id, journal_entry_id);
ANALYZE postings;

COMMIT;
