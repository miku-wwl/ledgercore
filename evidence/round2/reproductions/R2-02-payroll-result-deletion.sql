-- Round 2 reproduction R2-02: can payroll result rows be deleted, and how do progress counts react?
-- Target: a fresh disposable database migrated with the committed V1-V6 files only. Synthetic data only.
\set VERBOSITY default
\pset footer off
SELECT current_database() AS database, current_setting('port') AS port;

BEGIN;
INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES
 ('c0000000-0000-4000-8000-000000000011', 'r2-02-maker@example.test', 'synthetic-hash', 'R2-02 Maker'),
 ('c0000000-0000-4000-8000-000000000012', 'r2-02-approver@example.test', 'synthetic-hash', 'R2-02 Approver'),
 ('c0000000-0000-4000-8000-000000000013', 'r2-02-employee@example.test', 'synthetic-hash', 'R2-02 Employee');
INSERT INTO organizations (id, display_name) VALUES ('0a000000-0000-4000-8000-000000000011', 'R2-02 Synthetic Payroll Ltd');
INSERT INTO organization_memberships (organization_id, customer_id) VALUES
 ('0a000000-0000-4000-8000-000000000011', 'c0000000-0000-4000-8000-000000000011'),
 ('0a000000-0000-4000-8000-000000000011', 'c0000000-0000-4000-8000-000000000012');
INSERT INTO organization_membership_roles (organization_id, customer_id, role) VALUES
 ('0a000000-0000-4000-8000-000000000011', 'c0000000-0000-4000-8000-000000000011', 'PAYROLL_MAKER'),
 ('0a000000-0000-4000-8000-000000000011', 'c0000000-0000-4000-8000-000000000012', 'PAYROLL_APPROVER');
INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name) VALUES
 ('ac000000-0000-4000-8000-000000000011', NULL, '0a000000-0000-4000-8000-000000000011', 'R2-02 corporate payer'),
 ('ac000000-0000-4000-8000-000000000013', 'c0000000-0000-4000-8000-000000000013', NULL, 'R2-02 employee');
INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system) VALUES
 ('1a000000-0000-4000-8000-000000000011', 'ac000000-0000-4000-8000-000000000011', 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE),
 ('1a000000-0000-4000-8000-000000000013', 'ac000000-0000-4000-8000-000000000013', 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE);
-- Opening funding for the payer (balanced against the simulation reserve).
INSERT INTO journal_entries (id, entry_type, reference) VALUES
 ('3e000000-0000-4000-8000-000000000001', 'OPENING_FUNDING', 'account:ac000000-0000-4000-8000-000000000011');
INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor) VALUES
 ('50000000-0000-4000-8000-000000000001', '3e000000-0000-4000-8000-000000000001', '00000000-0000-5000-8000-000000000001', 'DEBIT', 100000),
 ('50000000-0000-4000-8000-000000000002', '3e000000-0000-4000-8000-000000000001', '1a000000-0000-4000-8000-000000000011', 'CREDIT', 100000);
INSERT INTO account_balance_projection (ledger_account_id, balance_minor) VALUES
 ('1a000000-0000-4000-8000-000000000011', 100000), ('1a000000-0000-4000-8000-000000000013', 0);
INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, created_by_customer_id) VALUES
 ('ba000000-0000-4000-8000-000000000011', '0a000000-0000-4000-8000-000000000011', 'ac000000-0000-4000-8000-000000000011', 'R2-02 batch', 'c0000000-0000-4000-8000-000000000011');
INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name, payee_account_id, payee_owner_customer_id, amount_minor) VALUES
 ('1e000000-0000-4000-8000-000000000011', 'ba000000-0000-4000-8000-000000000011', 1, 'EMP-1', 'Employee One', 'ac000000-0000-4000-8000-000000000013', 'c0000000-0000-4000-8000-000000000013', 1000),
 ('1e000000-0000-4000-8000-000000000012', 'ba000000-0000-4000-8000-000000000011', 2, 'EMP-2', 'Employee Two', 'ac000000-0000-4000-8000-000000000013', 'c0000000-0000-4000-8000-000000000013', 2000),
 ('1e000000-0000-4000-8000-000000000013', 'ba000000-0000-4000-8000-000000000011', 3, 'EMP-3', 'Employee Three', 'ac000000-0000-4000-8000-000000000013', 'c0000000-0000-4000-8000-000000000013', 3000);
INSERT INTO payroll_item_results (item_id) VALUES
 ('1e000000-0000-4000-8000-000000000011'), ('1e000000-0000-4000-8000-000000000012'), ('1e000000-0000-4000-8000-000000000013');
COMMIT;

UPDATE payroll_batches SET status = 'SUBMITTED', submitted_by_customer_id = 'c0000000-0000-4000-8000-000000000011',
       submitted_at = clock_timestamp(), content_digest = repeat('ef', 32) WHERE id = 'ba000000-0000-4000-8000-000000000011';
UPDATE payroll_batches SET status = 'APPROVED', approved_by_customer_id = 'c0000000-0000-4000-8000-000000000012',
       approved_at = clock_timestamp(), approved_content_digest = repeat('ef', 32) WHERE id = 'ba000000-0000-4000-8000-000000000011';
UPDATE payroll_batches SET status = 'PROCESSING', started_at = clock_timestamp() WHERE id = 'ba000000-0000-4000-8000-000000000011';

-- Item 1 is paid exactly as the application does it: balanced TRANSFER, transfer row, receipt, SUCCEEDED result.
BEGIN;
INSERT INTO journal_entries (id, entry_type, reference) VALUES
 ('3e000000-0000-4000-8000-000000000002', 'TRANSFER', 'transfer:7f000000-0000-4000-8000-000000000001');
INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor) VALUES
 ('50000000-0000-4000-8000-000000000003', '3e000000-0000-4000-8000-000000000002', '1a000000-0000-4000-8000-000000000011', 'DEBIT', 1000),
 ('50000000-0000-4000-8000-000000000004', '3e000000-0000-4000-8000-000000000002', '1a000000-0000-4000-8000-000000000013', 'CREDIT', 1000);
UPDATE account_balance_projection SET balance_minor = 99000 WHERE ledger_account_id = '1a000000-0000-4000-8000-000000000011';
UPDATE account_balance_projection SET balance_minor = 1000 WHERE ledger_account_id = '1a000000-0000-4000-8000-000000000013';
INSERT INTO transfers (id, owner_customer_id, source_owner_customer_id, source_organization_id, source_account_id,
                       target_account_id, amount_minor, journal_entry_id) VALUES
 ('7f000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000011', NULL, '0a000000-0000-4000-8000-000000000011',
  'ac000000-0000-4000-8000-000000000011', 'ac000000-0000-4000-8000-000000000013', 1000, '3e000000-0000-4000-8000-000000000002');
INSERT INTO idempotency_records (owner_customer_id, operation, idempotency_key, request_fingerprint, status, transfer_id, response_payload) VALUES
 ('c0000000-0000-4000-8000-000000000011', 'TRANSFER',
  'payroll-ba000000-0000-4000-8000-000000000011-1e000000-0000-4000-8000-000000000011', repeat('0', 64), 'SUCCEEDED',
  '7f000000-0000-4000-8000-000000000001', '{"transferId":"7f000000-0000-4000-8000-000000000001"}');
UPDATE payroll_item_results SET status = 'SUCCEEDED', attempt_count = 1, transfer_id = '7f000000-0000-4000-8000-000000000001',
       completed_at = clock_timestamp() WHERE item_id = '1e000000-0000-4000-8000-000000000011';
COMMIT;
-- Item 2 fails permanently (insufficient funds is recorded without any transfer).
UPDATE payroll_item_results SET status = 'FAILED', attempt_count = 1, error_code = 'INSUFFICIENT_FUNDS',
       error_message = 'synthetic terminal failure', completed_at = clock_timestamp()
 WHERE item_id = '1e000000-0000-4000-8000-000000000012';

\echo '== Before: PayrollService.countsFor() SQL and the item/result correspondence'
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
  FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
 WHERE i.batch_id = 'ba000000-0000-4000-8000-000000000011';

\echo '== Control: UPDATE of a terminal result (expected: rejected by enforce_payroll_item_result_transition)'
UPDATE payroll_item_results SET status = 'PENDING' WHERE item_id = '1e000000-0000-4000-8000-000000000012';

\echo '== R2-02 attempt 1: DELETE the terminal SUCCEEDED result that links item 1 to its ledger transfer'
DELETE FROM payroll_item_results WHERE item_id = '1e000000-0000-4000-8000-000000000011';
\echo '== R2-02 attempt 2: DELETE the terminal FAILED result of item 2'
DELETE FROM payroll_item_results WHERE item_id = '1e000000-0000-4000-8000-000000000012';
\echo '== R2-02 attempt 3: DELETE the PENDING result of the still-unpaid item 3'
DELETE FROM payroll_item_results WHERE item_id = '1e000000-0000-4000-8000-000000000013';

\echo '== After: items still exist, results are gone'
SELECT (SELECT count(*) FROM payroll_items WHERE batch_id = 'ba000000-0000-4000-8000-000000000011') AS items,
       (SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
         WHERE i.batch_id = 'ba000000-0000-4000-8000-000000000011') AS results;
\echo '== After: countsFor() now reports zero pending, which finishIfReady() treats as complete'
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count,
       CASE WHEN count(*) FILTER (WHERE r.status = 'PENDING') > 0 THEN 'stay PROCESSING'
            WHEN count(*) FILTER (WHERE r.status = 'FAILED') = 0 THEN 'COMPLETED'
            WHEN count(*) FILTER (WHERE r.status = 'SUCCEEDED') = 0 THEN 'FAILED'
            ELSE 'PARTIALLY_COMPLETED' END AS finish_if_ready_decision
  FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
 WHERE i.batch_id = 'ba000000-0000-4000-8000-000000000011';
\echo '== The decision is accepted by the database (no constraint checks result existence after item insert)'
UPDATE payroll_batches SET status = 'COMPLETED', completed_at = clock_timestamp()
 WHERE id = 'ba000000-0000-4000-8000-000000000011' AND status = 'PROCESSING';
\echo '== Ledger truth versus payroll record: the transfer exists, no payroll result references it'
SELECT t.id AS transfer_id, t.amount_minor,
       EXISTS (SELECT 1 FROM payroll_item_results r WHERE r.transfer_id = t.id) AS referenced_by_payroll_result
  FROM transfers t WHERE t.source_account_id = 'ac000000-0000-4000-8000-000000000011';
\echo '== A replacement result can be INSERTed directly in a terminal state (no insert-time lifecycle check)'
INSERT INTO payroll_item_results (item_id, status, attempt_count, error_code, error_message, completed_at)
VALUES ('1e000000-0000-4000-8000-000000000013', 'FAILED', 1, 'FORGED', 'inserted directly as terminal', clock_timestamp());
SELECT b.status, (SELECT count(*) FROM payroll_items i WHERE i.batch_id = b.id) AS items,
       (SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id WHERE i.batch_id = b.id) AS results
  FROM payroll_batches b WHERE b.id = 'ba000000-0000-4000-8000-000000000011';
