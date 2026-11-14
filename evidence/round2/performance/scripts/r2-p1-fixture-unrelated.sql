-- R2-P1 stage S2 fixture: ten unrelated organizations, each with one DRAFT batch of 5,000 PENDING items
-- (50,000 unrelated results in total, matching Round 1 H04). Synthetic; no transfers or postings.
\set ON_ERROR_STOP on
DO $$ BEGIN
    IF current_database() NOT LIKE 'ledgercore_r2_perf_%' THEN
        RAISE EXCEPTION 'R2-P1 fixtures may only run in a ledgercore_r2_perf_* disposable database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = md5('r2p1-org-0')::uuid) THEN
        RAISE EXCEPTION 'Load r2-p1-fixture-target.sql first';
    END IF;
    IF EXISTS (SELECT 1 FROM organizations WHERE id = md5('r2p1-org-1')::uuid) THEN
        RAISE EXCEPTION 'R2-P1 unrelated fixture already exists in this database';
    END IF;
END $$;

BEGIN;
INSERT INTO organizations (id, display_name)
SELECT md5('r2p1-org-' || o)::uuid, 'R2P1 unrelated organization ' || o FROM generate_series(1, 10) o;
INSERT INTO organization_memberships (organization_id, customer_id)
SELECT md5('r2p1-org-' || o)::uuid, md5('r2p1-customer-101')::uuid FROM generate_series(1, 10) o;
INSERT INTO organization_membership_roles (organization_id, customer_id, role)
SELECT md5('r2p1-org-' || o)::uuid, md5('r2p1-customer-101')::uuid, 'PAYROLL_MAKER' FROM generate_series(1, 10) o;
INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name)
SELECT md5('r2p1-payer-' || o)::uuid, NULL, md5('r2p1-org-' || o)::uuid, 'R2P1 payer ' || o
  FROM generate_series(1, 10) o;
INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, created_by_customer_id)
SELECT md5('r2p1-batch-' || o)::uuid, md5('r2p1-org-' || o)::uuid, md5('r2p1-payer-' || o)::uuid,
       'R2P1 unrelated batch ' || o, md5('r2p1-customer-101')::uuid
  FROM generate_series(1, 10) o;
INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name, payee_account_id,
                           payee_owner_customer_id, amount_minor)
SELECT md5('r2p1-item-' || o || '-' || n)::uuid, md5('r2p1-batch-' || o)::uuid, n, 'EMP-' || n,
       'Synthetic Employee ' || n, md5('r2p1-payee-' || (1 + n % 100))::uuid,
       md5('r2p1-customer-' || (1 + n % 100))::uuid, 100 + n % 900
  FROM generate_series(1, 10) o, generate_series(1, 5000) n;
INSERT INTO payroll_item_results (item_id)
SELECT md5('r2p1-item-' || o || '-' || n)::uuid FROM generate_series(1, 10) o, generate_series(1, 5000) n;
COMMIT;

VACUUM (ANALYZE) organizations, organization_memberships, organization_membership_roles, accounts,
                 payroll_batches, payroll_items, payroll_item_results;
SELECT 'S2' AS stage, (SELECT count(*) FROM payroll_items) AS items, (SELECT count(*) FROM payroll_item_results) AS results,
       (SELECT count(*) FROM organizations) AS organizations;
