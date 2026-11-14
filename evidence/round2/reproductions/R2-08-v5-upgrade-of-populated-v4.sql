-- Round 2 reproduction R2-08: can the committed V5 migration upgrade a V4 database that already holds transfers?
-- Run with psql -X -v ON_ERROR_STOP=0 -f <this file> against a fresh, empty,
-- disposable database. The script applies V1-V4, seeds one valid synthetic personal transfer, then applies V5.
\set VERBOSITY default
\pset footer off
SELECT current_database() AS database, current_setting('port') AS port;
\set QUIET on
BEGIN; \ir ../../../backend/src/main/resources/db/migration/V1__create_customer_and_account_tables.sql
COMMIT; BEGIN; \ir ../../../backend/src/main/resources/db/migration/V2__create_ledger_tables.sql
COMMIT; BEGIN; \ir ../../../backend/src/main/resources/db/migration/V3__create_transfers_and_idempotency.sql
COMMIT; BEGIN; \ir ../../../backend/src/main/resources/db/migration/V4__enforce_idempotency_receipt_lifecycle.sql
COMMIT;
\set QUIET off

BEGIN;
INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES
 ('c0000000-0000-4000-8000-000000000081', 'r2-08-alice@example.test', 'synthetic-hash', 'R2-08 Alice'),
 ('c0000000-0000-4000-8000-000000000082', 'r2-08-bob@example.test', 'synthetic-hash', 'R2-08 Bob');
INSERT INTO accounts (id, owner_customer_id, display_name) VALUES
 ('ac000000-0000-4000-8000-000000000081', 'c0000000-0000-4000-8000-000000000081', 'Alice everyday'),
 ('ac000000-0000-4000-8000-000000000082', 'c0000000-0000-4000-8000-000000000082', 'Bob everyday');
INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system) VALUES
 ('1a000000-0000-4000-8000-000000000081', 'ac000000-0000-4000-8000-000000000081', 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE),
 ('1a000000-0000-4000-8000-000000000082', 'ac000000-0000-4000-8000-000000000082', 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE);
INSERT INTO journal_entries (id, entry_type, reference) VALUES
 ('3e000000-0000-4000-8000-000000000081', 'OPENING_FUNDING', 'account:ac000000-0000-4000-8000-000000000081'),
 ('3e000000-0000-4000-8000-000000000082', 'TRANSFER', 'transfer:7f000000-0000-4000-8000-000000000081');
INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor) VALUES
 ('50000000-0000-4000-8000-000000000081', '3e000000-0000-4000-8000-000000000081', '00000000-0000-5000-8000-000000000001', 'DEBIT', 100000),
 ('50000000-0000-4000-8000-000000000082', '3e000000-0000-4000-8000-000000000081', '1a000000-0000-4000-8000-000000000081', 'CREDIT', 100000),
 ('50000000-0000-4000-8000-000000000083', '3e000000-0000-4000-8000-000000000082', '1a000000-0000-4000-8000-000000000081', 'DEBIT', 2500),
 ('50000000-0000-4000-8000-000000000084', '3e000000-0000-4000-8000-000000000082', '1a000000-0000-4000-8000-000000000082', 'CREDIT', 2500);
INSERT INTO account_balance_projection (ledger_account_id, balance_minor) VALUES
 ('1a000000-0000-4000-8000-000000000081', 97500), ('1a000000-0000-4000-8000-000000000082', 2500);
UPDATE account_balance_projection SET balance_minor = 100000 WHERE ledger_account_id = '00000000-0000-5000-8000-000000000001';
INSERT INTO transfers (id, owner_customer_id, source_account_id, target_account_id, amount_minor, journal_entry_id) VALUES
 ('7f000000-0000-4000-8000-000000000081', 'c0000000-0000-4000-8000-000000000081', 'ac000000-0000-4000-8000-000000000081',
  'ac000000-0000-4000-8000-000000000082', 2500, '3e000000-0000-4000-8000-000000000082');
INSERT INTO idempotency_records (owner_customer_id, operation, idempotency_key, request_fingerprint, status, transfer_id, response_payload) VALUES
 ('c0000000-0000-4000-8000-000000000081', 'TRANSFER', 'r2-08-alice-key', repeat('0', 64), 'SUCCEEDED',
  '7f000000-0000-4000-8000-000000000081', '{"transferId":"7f000000-0000-4000-8000-000000000081"}');
COMMIT;
\echo '== Populated V4 state: one committed personal transfer'
SELECT count(*) AS transfers, (SELECT count(*) FROM postings) AS postings FROM transfers;

\echo '== Attempt 1: apply the committed, unmodified V5 in its own transaction (as Flyway does)'
\set QUIET on
BEGIN;
\ir ../../../backend/src/main/resources/db/migration/V5__add_organizations_and_corporate_account_ownership.sql
\set QUIET off
COMMIT;
\echo '== After attempt 1 (the failed transaction rolled back): V5 objects exist?'
SELECT to_regclass('organizations') IS NOT NULL AS organizations_table_exists,
       EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'transfers'
               AND column_name = 'source_owner_customer_id') AS transfer_owner_column_exists;

\echo '== Attempt 2: the Round 2 remedy, inside ONE transaction: disable only the append-only trigger, run V5, re-enable'
\set QUIET on
BEGIN;
ALTER TABLE transfers DISABLE TRIGGER tr_transfers_append_only;
\ir ../../../backend/src/main/resources/db/migration/V5__add_organizations_and_corporate_account_ownership.sql
ALTER TABLE transfers ENABLE TRIGGER tr_transfers_append_only;
\set QUIET off
COMMIT;
\echo '== After attempt 2: backfill result, trigger state, and append-only protection'
SELECT id, owner_customer_id = source_owner_customer_id AS owner_backfilled, source_organization_id IS NULL AS personal_source
  FROM transfers;
SELECT tgname, tgenabled FROM pg_trigger WHERE tgrelid = 'transfers'::regclass AND tgname = 'tr_transfers_append_only';
UPDATE transfers SET amount_minor = amount_minor WHERE id = '7f000000-0000-4000-8000-000000000081';
SELECT sum(amount_minor) FILTER (WHERE side = 'DEBIT') AS debits, sum(amount_minor) FILTER (WHERE side = 'CREDIT') AS credits
  FROM postings;
