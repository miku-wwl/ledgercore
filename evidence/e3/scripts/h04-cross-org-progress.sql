\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() <> 'ledgercore_e3_perf' THEN
        RAISE EXCEPTION 'H04 is restricted to ledgercore_e3_perf; connected database is %', current_database();
    END IF;
    IF EXISTS (SELECT 1 FROM organizations WHERE display_name LIKE 'E3 H04 Org %') THEN
        RAISE EXCEPTION 'H04 fixture already exists; refusing to duplicate synthetic rows';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payroll_batches WHERE name = 'E3 plan-5000') THEN
        RAISE EXCEPTION 'H03 target batch E3 plan-5000 is missing';
    END IF;
END
$$;

\echo H04_BASELINE_GLOBAL_RESULT_COUNT
SELECT count(*) AS payroll_item_results_before FROM payroll_item_results;
\echo H04_BASELINE_TARGET_COUNTS_AND_PLAN
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
WHERE i.batch_id = :'target_batch_id'::uuid;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
WHERE i.batch_id = :'target_batch_id'::uuid;

BEGIN;

INSERT INTO customers (id, email_normalized, password_hash, display_name)
SELECT md5('e3-h04-maker-' || n)::uuid,
       'e3-h04-maker-' || n || '@example.test',
       'synthetic-e3-h04-hash-not-for-authentication',
       'E3 H04 Maker ' || n
FROM generate_series(1, 10) AS series(n);

INSERT INTO organizations (id, display_name)
SELECT md5('e3-h04-org-' || n)::uuid, 'E3 H04 Org ' || n
FROM generate_series(1, 10) AS series(n);

INSERT INTO organization_memberships (organization_id, customer_id)
SELECT md5('e3-h04-org-' || n)::uuid, md5('e3-h04-maker-' || n)::uuid
FROM generate_series(1, 10) AS series(n);

INSERT INTO organization_membership_roles (organization_id, customer_id, role)
SELECT md5('e3-h04-org-' || n)::uuid, md5('e3-h04-maker-' || n)::uuid, 'ORG_ADMIN'
FROM generate_series(1, 10) AS series(n);

INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name)
SELECT md5('e3-h04-payer-' || n)::uuid, NULL, md5('e3-h04-org-' || n)::uuid,
       'E3 H04 query fixture payer ' || n
FROM generate_series(1, 10) AS series(n);

INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
SELECT md5('e3-h04-payer-ledger-' || n)::uuid, md5('e3-h04-payer-' || n)::uuid,
       'CUSTOMER_DEPOSIT', 'CREDIT', FALSE, 'NZD'
FROM generate_series(1, 10) AS series(n);

INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
SELECT md5('e3-h04-payer-ledger-' || n)::uuid, 'NZD', 0
FROM generate_series(1, 10) AS series(n);

INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, status, created_by_customer_id)
SELECT md5('e3-h04-batch-' || n)::uuid, md5('e3-h04-org-' || n)::uuid,
       md5('e3-h04-payer-' || n)::uuid, 'E3 H04 Batch ' || n, 'DRAFT',
       md5('e3-h04-maker-' || n)::uuid
FROM generate_series(1, 10) AS series(n);

WITH target_owner AS (
    SELECT i.payee_owner_customer_id
    FROM payroll_items i
    JOIN payroll_batches b ON b.id = i.batch_id
    WHERE b.name = 'E3 plan-5000'
    ORDER BY i.sequence_no
    LIMIT 1
), payees AS (
    SELECT a.id AS account_id, a.owner_customer_id,
           row_number() OVER (ORDER BY a.id)::INTEGER AS employee_no
    FROM accounts a
    JOIN target_owner o ON o.payee_owner_customer_id = a.owner_customer_id
    WHERE a.owner_organization_id IS NULL
)
INSERT INTO payroll_items
    (id, batch_id, sequence_no, employee_reference, employee_name, payee_account_id,
     payee_owner_customer_id, amount_minor, currency)
SELECT md5('e3-h04-item-' || org_no || '-' || seq_no)::uuid,
       md5('e3-h04-batch-' || org_no)::uuid,
       seq_no,
       'H04-' || lpad(org_no::text, 2, '0') || '-' || lpad(seq_no::text, 5, '0'),
       'E3 H04 Synthetic Employee ' || lpad(seq_no::text, 5, '0'),
       payees.account_id, payees.owner_customer_id, 100, 'NZD'
FROM generate_series(1, 10) AS orgs(org_no)
CROSS JOIN generate_series(1, 5000) AS employees(seq_no)
JOIN payees ON payees.employee_no = seq_no;

INSERT INTO payroll_item_results (item_id)
SELECT i.id
FROM payroll_items i
JOIN payroll_batches b ON b.id = i.batch_id
WHERE b.name LIKE 'E3 H04 Batch %';

COMMIT;

ANALYZE payroll_batches;
ANALYZE payroll_items;
ANALYZE payroll_item_results;

\echo H04_ADDED_UNRELATED_ROWS
SELECT count(*) AS h04_organizations FROM organizations WHERE display_name LIKE 'E3 H04 Org %';
SELECT count(*) AS h04_batches FROM payroll_batches WHERE name LIKE 'E3 H04 Batch %';
SELECT count(*) AS h04_items
FROM payroll_items i JOIN payroll_batches b ON b.id = i.batch_id
WHERE b.name LIKE 'E3 H04 Batch %';
SELECT count(*) AS h04_pending_results
FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
JOIN payroll_batches b ON b.id = i.batch_id
WHERE b.name LIKE 'E3 H04 Batch %' AND r.status = 'PENDING';
SELECT count(*) AS payroll_item_results_after FROM payroll_item_results;

\echo H04_TARGET_COUNTS_AND_PLAN_AFTER_OTHER_ORG_ROWS
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
WHERE i.batch_id = :'target_batch_id'::uuid;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
WHERE i.batch_id = :'target_batch_id'::uuid;
