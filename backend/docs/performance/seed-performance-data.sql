-- Run only in a fresh, disposable LedgerCore database after Flyway reaches V4.
-- The generated application is deliberately synthetic and carries no user data.
\set ON_ERROR_STOP on
DO $guard$
BEGIN
    IF current_database() <> 'ledgercore_perf' OR current_schema() <> 'public' THEN
        RAISE EXCEPTION 'Refusing to seed outside public.ledgercore_perf';
    END IF;
    IF EXISTS (SELECT 1 FROM customers)
       OR EXISTS (SELECT 1 FROM accounts)
       OR EXISTS (SELECT 1 FROM journal_entries)
       OR EXISTS (SELECT 1 FROM postings)
       OR EXISTS (SELECT 1 FROM transfers) THEN
        RAISE EXCEPTION 'Refusing to seed a non-empty performance database';
    END IF;
END
$guard$;

BEGIN;

INSERT INTO customers (id, email_normalized, password_hash, display_name, created_at)
SELECT md5('perf-customer-' || lpad(s.customer_number::text, 4, '0'))::uuid,
       'perf-' || lpad(s.customer_number::text, 4, '0') || '@example.test',
       'synthetic-not-a-login-hash',
       'Performance customer ' || s.customer_number,
       TIMESTAMPTZ '2026-01-01 00:00:00+00' + s.customer_number * INTERVAL '1 second'
FROM generate_series(1, 1000) AS s(customer_number);

INSERT INTO accounts (id, owner_customer_id, display_name, status, currency, created_at)
SELECT md5('perf-account-' || lpad(s.account_number::text, 6, '0'))::uuid,
       md5('perf-customer-' || lpad((((s.account_number - 1) / 10) + 1)::text, 4, '0'))::uuid,
       'Performance account ' || s.account_number,
       'OPEN', 'NZD',
       TIMESTAMPTZ '2026-01-02 00:00:00+00' + s.account_number * INTERVAL '1 second'
FROM generate_series(1, 10000) AS s(account_number);

INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
SELECT md5('perf-ledger-' || lpad(s.account_number::text, 6, '0'))::uuid,
       md5('perf-account-' || lpad(s.account_number::text, 6, '0'))::uuid,
       'CUSTOMER_DEPOSIT', 'CREDIT', FALSE, 'NZD'
FROM generate_series(1, 10000) AS s(account_number);

INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
SELECT md5('perf-ledger-' || lpad(s.account_number::text, 6, '0'))::uuid, 'NZD', 100000000
FROM generate_series(1, 10000) AS s(account_number);

INSERT INTO journal_entries (id, entry_type, currency, reference, created_at)
SELECT md5('perf-opening-' || lpad(s.account_number::text, 6, '0'))::uuid,
       'OPENING_FUNDING', 'NZD', 'performance:opening:' || s.account_number,
       TIMESTAMPTZ '2026-01-02 00:00:00+00' + s.account_number * INTERVAL '1 second'
FROM generate_series(1, 10000) AS s(account_number);

INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
SELECT md5('perf-opening-reserve-posting-' || lpad(s.account_number::text, 6, '0'))::uuid,
       md5('perf-opening-' || lpad(s.account_number::text, 6, '0'))::uuid,
       '00000000-0000-5000-8000-000000000001'::uuid,
       'DEBIT', 100000000, 'NZD'
FROM generate_series(1, 10000) AS s(account_number)
UNION ALL
SELECT md5('perf-opening-customer-posting-' || lpad(s.account_number::text, 6, '0'))::uuid,
       md5('perf-opening-' || lpad(s.account_number::text, 6, '0'))::uuid,
       md5('perf-ledger-' || lpad(s.account_number::text, 6, '0'))::uuid,
       'CREDIT', 100000000, 'NZD'
FROM generate_series(1, 10000) AS s(account_number);

UPDATE account_balance_projection
SET balance_minor = 1000000000000, updated_at = clock_timestamp()
WHERE ledger_account_id = '00000000-0000-5000-8000-000000000001'::uuid;

INSERT INTO journal_entries (id, entry_type, currency, reference, created_at)
SELECT md5('perf-journal-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       'TRANSFER', 'NZD', 'performance:transfer:' || s.transfer_number,
       TIMESTAMPTZ '2026-02-01 00:00:00+00' + s.transfer_number * INTERVAL '1 second'
FROM generate_series(1, 50000) AS s(transfer_number);

INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
SELECT md5('perf-transfer-source-posting-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       md5('perf-journal-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       md5('perf-ledger-' || lpad((((s.transfer_number - 1) % 1000) + 1)::text, 6, '0'))::uuid,
       'DEBIT', 1000 + (s.transfer_number % 9000), 'NZD'
FROM generate_series(1, 50000) AS s(transfer_number)
UNION ALL
SELECT md5('perf-transfer-target-posting-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       md5('perf-journal-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       md5('perf-ledger-' || lpad(((((s.transfer_number - 1) % 1000) + 1) % 1000 + 1)::text, 6, '0'))::uuid,
       'CREDIT', 1000 + (s.transfer_number % 9000), 'NZD'
FROM generate_series(1, 50000) AS s(transfer_number);

INSERT INTO transfers (id, owner_customer_id, source_account_id, target_account_id, amount_minor, currency,
                       journal_entry_id, created_at)
SELECT md5('perf-transfer-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       source.owner_customer_id,
       source.id,
       target.id,
       1000 + (s.transfer_number % 9000), 'NZD',
       md5('perf-journal-' || lpad(s.transfer_number::text, 6, '0'))::uuid,
       TIMESTAMPTZ '2026-02-01 00:00:00+00' + s.transfer_number * INTERVAL '1 second'
FROM generate_series(1, 50000) AS s(transfer_number)
JOIN accounts source ON source.id = md5('perf-account-' || lpad((((s.transfer_number - 1) % 1000) + 1)::text, 6, '0'))::uuid
JOIN accounts target ON target.id = md5('perf-account-' || lpad(((((s.transfer_number - 1) % 1000) + 1) % 1000 + 1)::text, 6, '0'))::uuid;

UPDATE account_balance_projection projection
SET balance_minor = balances.balance_minor,
    updated_at = clock_timestamp()
FROM (
    SELECT ledger.id,
           COALESCE(sum(CASE WHEN postings.side = ledger.normal_side
                             THEN postings.amount_minor ELSE -postings.amount_minor END), 0)::bigint AS balance_minor
    FROM ledger_accounts ledger
    LEFT JOIN postings ON postings.ledger_account_id = ledger.id
    GROUP BY ledger.id, ledger.normal_side
) balances
WHERE projection.ledger_account_id = balances.id;

COMMIT;

ANALYZE customers;
ANALYZE accounts;
ANALYZE ledger_accounts;
ANALYZE account_balance_projection;
ANALYZE journal_entries;
ANALYZE postings;
ANALYZE transfers;

SELECT 'customers' AS relation, count(*) FROM customers
UNION ALL SELECT 'accounts', count(*) FROM accounts
UNION ALL SELECT 'journal_entries', count(*) FROM journal_entries
UNION ALL SELECT 'postings', count(*) FROM postings
UNION ALL SELECT 'transfers', count(*) FROM transfers;
