-- Query shapes copied from the account, transaction-history, ledger, and reconciliation services.
-- Run after seed-performance-data.sql in the disposable ledgercore_perf database.
\set ON_ERROR_STOP on
DO $guard$
BEGIN
    IF current_database() <> 'ledgercore_perf' OR current_schema() <> 'public' THEN
        RAISE EXCEPTION 'Refusing to run outside public.ledgercore_perf';
    END IF;
END
$guard$;

\echo 'Account balance: owned account list and projection joins'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.id, a.display_name, a.status, a.currency, p.balance_minor, a.created_at
FROM accounts a
JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
JOIN account_balance_projection p ON p.ledger_account_id = la.id
WHERE a.owner_customer_id = md5('perf-customer-0001')::uuid
ORDER BY a.created_at DESC, a.id DESC;

\echo 'Transaction history: source or target account, newest first, first 51 rows'
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

\echo 'Ledger postings: signed balance aggregate for one customer ledger'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side
                         THEN p.amount_minor ELSE -p.amount_minor END), 0)
FROM ledger_accounts la
LEFT JOIN postings p ON p.ledger_account_id = la.id
WHERE la.id = md5('perf-ledger-000001')::uuid
GROUP BY la.id, la.normal_side;

\echo 'Reconciliation: verify the customer owns the account'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM accounts
WHERE owner_customer_id = md5('perf-customer-0001')::uuid
  AND id = md5('perf-account-000001')::uuid;

\echo 'Reconciliation: authoritative ledger aggregate'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side
                         THEN p.amount_minor ELSE -p.amount_minor END), 0)
FROM ledger_accounts la
LEFT JOIN postings p ON p.ledger_account_id = la.id
WHERE la.id = md5('perf-ledger-000001')::uuid
GROUP BY la.id, la.normal_side;

\echo 'Reconciliation: balance projection lookup'
EXPLAIN (ANALYZE, BUFFERS)
SELECT balance_minor
FROM account_balance_projection
WHERE ledger_account_id = md5('perf-ledger-000001')::uuid;
