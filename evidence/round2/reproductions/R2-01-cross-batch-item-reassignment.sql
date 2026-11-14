-- Round 2 reproduction R2-01: can a payroll item be moved out of a non-DRAFT batch?
-- Target: a fresh disposable database migrated with the committed V1-V6 files only.
-- All identities, accounts, and amounts are synthetic. Run with psql -X -v ON_ERROR_STOP=0.
\set VERBOSITY default
\pset footer off
SELECT current_database() AS database, current_setting('port') AS port,
       (SELECT max(version) FROM (VALUES ('V6')) v(version)) AS schema_under_test;

BEGIN;
INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES
 ('c0000000-0000-4000-8000-000000000001', 'r2-01-maker@example.test', 'synthetic-hash', 'R2 Maker'),
 ('c0000000-0000-4000-8000-000000000002', 'r2-01-approver@example.test', 'synthetic-hash', 'R2 Approver'),
 ('c0000000-0000-4000-8000-000000000003', 'r2-01-employee-a@example.test', 'synthetic-hash', 'R2 Employee A'),
 ('c0000000-0000-4000-8000-000000000004', 'r2-01-employee-b@example.test', 'synthetic-hash', 'R2 Employee B');
INSERT INTO organizations (id, display_name) VALUES ('0a000000-0000-4000-8000-000000000001', 'R2-01 Synthetic Payroll Ltd');
INSERT INTO organization_memberships (organization_id, customer_id) VALUES
 ('0a000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000001'),
 ('0a000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000002');
INSERT INTO organization_membership_roles (organization_id, customer_id, role) VALUES
 ('0a000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000001', 'PAYROLL_MAKER'),
 ('0a000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000002', 'PAYROLL_APPROVER');
INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name) VALUES
 ('ac000000-0000-4000-8000-000000000001', NULL, '0a000000-0000-4000-8000-000000000001', 'R2-01 corporate payer'),
 ('ac000000-0000-4000-8000-000000000003', 'c0000000-0000-4000-8000-000000000003', NULL, 'R2-01 employee A'),
 ('ac000000-0000-4000-8000-000000000004', 'c0000000-0000-4000-8000-000000000004', NULL, 'R2-01 employee B');
-- Batch A will be submitted and approved; batch B stays DRAFT; batch C reaches a terminal state.
INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, created_by_customer_id) VALUES
 ('ba000000-0000-4000-8000-00000000000a', '0a000000-0000-4000-8000-000000000001', 'ac000000-0000-4000-8000-000000000001', 'R2-01 batch A (approved)', 'c0000000-0000-4000-8000-000000000001'),
 ('ba000000-0000-4000-8000-00000000000b', '0a000000-0000-4000-8000-000000000001', 'ac000000-0000-4000-8000-000000000001', 'R2-01 batch B (draft)', 'c0000000-0000-4000-8000-000000000001'),
 ('ba000000-0000-4000-8000-00000000000c', '0a000000-0000-4000-8000-000000000001', 'ac000000-0000-4000-8000-000000000001', 'R2-01 batch C (terminal)', 'c0000000-0000-4000-8000-000000000001');
INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name, payee_account_id, payee_owner_customer_id, amount_minor) VALUES
 ('1e000000-0000-4000-8000-0000000000a1', 'ba000000-0000-4000-8000-00000000000a', 1, 'EMP-A1', 'Employee A', 'ac000000-0000-4000-8000-000000000003', 'c0000000-0000-4000-8000-000000000003', 150000),
 ('1e000000-0000-4000-8000-0000000000a2', 'ba000000-0000-4000-8000-00000000000a', 2, 'EMP-A2', 'Employee B', 'ac000000-0000-4000-8000-000000000004', 'c0000000-0000-4000-8000-000000000004', 120000),
 ('1e000000-0000-4000-8000-0000000000b1', 'ba000000-0000-4000-8000-00000000000b', 1, 'EMP-B1', 'Employee A', 'ac000000-0000-4000-8000-000000000003', 'c0000000-0000-4000-8000-000000000003', 1000),
 ('1e000000-0000-4000-8000-0000000000c1', 'ba000000-0000-4000-8000-00000000000c', 1, 'EMP-C1', 'Employee B', 'ac000000-0000-4000-8000-000000000004', 'c0000000-0000-4000-8000-000000000004', 5000);
INSERT INTO payroll_item_results (item_id) VALUES
 ('1e000000-0000-4000-8000-0000000000a1'), ('1e000000-0000-4000-8000-0000000000a2'),
 ('1e000000-0000-4000-8000-0000000000b1'), ('1e000000-0000-4000-8000-0000000000c1');
COMMIT;

-- Legitimate lifecycle for batch A: DRAFT -> SUBMITTED -> APPROVED (digest value is a fixed synthetic SHA-256 hex).
UPDATE payroll_batches SET status = 'SUBMITTED', submitted_by_customer_id = 'c0000000-0000-4000-8000-000000000001',
       submitted_at = clock_timestamp(), content_digest = repeat('ab', 32)
 WHERE id = 'ba000000-0000-4000-8000-00000000000a';
UPDATE payroll_batches SET status = 'APPROVED', approved_by_customer_id = 'c0000000-0000-4000-8000-000000000002',
       approved_at = clock_timestamp(), approved_content_digest = repeat('ab', 32)
 WHERE id = 'ba000000-0000-4000-8000-00000000000a';
-- Batch C: full legal lifecycle to a terminal state with a terminal FAILED result (no transfer needed).
UPDATE payroll_batches SET status = 'SUBMITTED', submitted_by_customer_id = 'c0000000-0000-4000-8000-000000000001',
       submitted_at = clock_timestamp(), content_digest = repeat('cd', 32) WHERE id = 'ba000000-0000-4000-8000-00000000000c';
UPDATE payroll_batches SET status = 'APPROVED', approved_by_customer_id = 'c0000000-0000-4000-8000-000000000002',
       approved_at = clock_timestamp(), approved_content_digest = repeat('cd', 32) WHERE id = 'ba000000-0000-4000-8000-00000000000c';
UPDATE payroll_batches SET status = 'PROCESSING', started_at = clock_timestamp() WHERE id = 'ba000000-0000-4000-8000-00000000000c';
UPDATE payroll_item_results SET status = 'FAILED', attempt_count = 1, error_code = 'INSUFFICIENT_FUNDS',
       error_message = 'synthetic terminal failure', completed_at = clock_timestamp()
 WHERE item_id = '1e000000-0000-4000-8000-0000000000c1';
UPDATE payroll_batches SET status = 'FAILED', completed_at = clock_timestamp() WHERE id = 'ba000000-0000-4000-8000-00000000000c';

\echo '== Initial state'
SELECT b.name, b.status, count(i.id) AS items, COALESCE(sum(i.amount_minor), 0) AS total_minor
  FROM payroll_batches b LEFT JOIN payroll_items i ON i.batch_id = b.id
 GROUP BY b.id ORDER BY b.name;

\echo '== Control 1: in-place amount change on an APPROVED batch item (expected: rejected with 55000)'
UPDATE payroll_items SET amount_minor = amount_minor + 1 WHERE id = '1e000000-0000-4000-8000-0000000000a1';

\echo '== Control 2: move a DRAFT item INTO the APPROVED batch (expected: rejected, NEW batch is checked)'
UPDATE payroll_items SET batch_id = 'ba000000-0000-4000-8000-00000000000a', sequence_no = 9
 WHERE id = '1e000000-0000-4000-8000-0000000000b1';

\echo '== R2-01 attempt 1: move an APPROVED item OUT to the DRAFT batch'
UPDATE payroll_items SET batch_id = 'ba000000-0000-4000-8000-00000000000b', sequence_no = 2
 WHERE id = '1e000000-0000-4000-8000-0000000000a1';

\echo '== R2-01 attempt 2: move the other APPROVED item out AND change its amount and payee in the same statement'
UPDATE payroll_items SET batch_id = 'ba000000-0000-4000-8000-00000000000b', sequence_no = 3, amount_minor = 1,
       employee_reference = 'EMP-B3', payee_account_id = 'ac000000-0000-4000-8000-000000000003',
       payee_owner_customer_id = 'c0000000-0000-4000-8000-000000000003'
 WHERE id = '1e000000-0000-4000-8000-0000000000a2';

\echo '== R2-01 attempt 3: move an item OUT of a terminal (FAILED) batch, then delete it from the draft'
UPDATE payroll_items SET batch_id = 'ba000000-0000-4000-8000-00000000000b', sequence_no = 4
 WHERE id = '1e000000-0000-4000-8000-0000000000c1';
DELETE FROM payroll_items WHERE id = '1e000000-0000-4000-8000-0000000000c1';

\echo '== Final state (an APPROVED batch whose item set no longer matches its approved digest is a defect)'
SELECT b.name, b.status, b.approved_content_digest IS NOT NULL AS has_approved_digest,
       count(i.id) AS items, COALESCE(sum(i.amount_minor), 0) AS total_minor
  FROM payroll_batches b LEFT JOIN payroll_items i ON i.batch_id = b.id
 GROUP BY b.id ORDER BY b.name;
SELECT i.employee_reference, b.name AS now_in_batch, i.amount_minor, r.status AS result_status
  FROM payroll_items i JOIN payroll_batches b ON b.id = i.batch_id
  LEFT JOIN payroll_item_results r ON r.item_id = i.id ORDER BY b.name, i.sequence_no;
SELECT count(*) AS surviving_results_for_terminal_batch_item
  FROM payroll_item_results WHERE item_id = '1e000000-0000-4000-8000-0000000000c1';
