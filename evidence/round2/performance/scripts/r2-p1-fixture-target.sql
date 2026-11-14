-- R2-P1 stage S1 fixture: one target organization with one PROCESSING payroll batch of 5,000 items
-- (3,000 PENDING, 2,000 FAILED). Deterministic md5-derived UUIDs; synthetic data only; no transfers or postings.
-- Valid under both V6 and V7: items and results are created while the batch is a DRAFT, then the batch follows
-- the legal lifecycle and failures are recorded while it is PROCESSING.
\set ON_ERROR_STOP on
SELECT current_database() AS database, current_setting('port') AS port;
DO $$ BEGIN
    IF current_database() NOT LIKE 'ledgercore_r2_perf_%' THEN
        RAISE EXCEPTION 'R2-P1 fixtures may only run in a ledgercore_r2_perf_* disposable database';
    END IF;
    IF EXISTS (SELECT 1 FROM organizations WHERE id = md5('r2p1-org-0')::uuid) THEN
        RAISE EXCEPTION 'R2-P1 target fixture already exists in this database';
    END IF;
END $$;

BEGIN;
INSERT INTO customers (id, email_normalized, password_hash, display_name)
SELECT md5('r2p1-customer-' || n)::uuid, 'r2p1-customer-' || n || '@example.test', 'synthetic-hash',
       'R2P1 Customer ' || n
  FROM generate_series(1, 102) n;                     -- 1..100 employees, 101 maker, 102 approver
INSERT INTO accounts (id, owner_customer_id, display_name)
SELECT md5('r2p1-payee-' || n)::uuid, md5('r2p1-customer-' || n)::uuid, 'R2P1 payee ' || n
  FROM generate_series(1, 100) n;
INSERT INTO organizations (id, display_name) VALUES (md5('r2p1-org-0')::uuid, 'R2P1 target organization');
INSERT INTO organization_memberships (organization_id, customer_id) VALUES
    (md5('r2p1-org-0')::uuid, md5('r2p1-customer-101')::uuid),
    (md5('r2p1-org-0')::uuid, md5('r2p1-customer-102')::uuid);
INSERT INTO organization_membership_roles (organization_id, customer_id, role) VALUES
    (md5('r2p1-org-0')::uuid, md5('r2p1-customer-101')::uuid, 'PAYROLL_MAKER'),
    (md5('r2p1-org-0')::uuid, md5('r2p1-customer-102')::uuid, 'PAYROLL_APPROVER');
INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name)
VALUES (md5('r2p1-payer-0')::uuid, NULL, md5('r2p1-org-0')::uuid, 'R2P1 payer 0');
INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, created_by_customer_id)
VALUES (md5('r2p1-batch-0')::uuid, md5('r2p1-org-0')::uuid, md5('r2p1-payer-0')::uuid, 'R2P1 target batch',
        md5('r2p1-customer-101')::uuid);
INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name, payee_account_id,
                           payee_owner_customer_id, amount_minor)
SELECT md5('r2p1-item-0-' || n)::uuid, md5('r2p1-batch-0')::uuid, n, 'EMP-' || n, 'Synthetic Employee ' || n,
       md5('r2p1-payee-' || (1 + n % 100))::uuid, md5('r2p1-customer-' || (1 + n % 100))::uuid, 100 + n % 900
  FROM generate_series(1, 5000) n;
INSERT INTO payroll_item_results (item_id)
SELECT md5('r2p1-item-0-' || n)::uuid FROM generate_series(1, 5000) n;
COMMIT;

UPDATE payroll_batches SET status = 'SUBMITTED', submitted_by_customer_id = md5('r2p1-customer-101')::uuid,
       submitted_at = clock_timestamp(), content_digest = repeat('ab', 32)
 WHERE id = md5('r2p1-batch-0')::uuid;
UPDATE payroll_batches SET status = 'APPROVED', approved_by_customer_id = md5('r2p1-customer-102')::uuid,
       approved_at = clock_timestamp(), approved_content_digest = repeat('ab', 32)
 WHERE id = md5('r2p1-batch-0')::uuid;
UPDATE payroll_batches SET status = 'PROCESSING', started_at = clock_timestamp()
 WHERE id = md5('r2p1-batch-0')::uuid;
UPDATE payroll_item_results
   SET status = 'FAILED', attempt_count = 1, error_code = 'INSUFFICIENT_FUNDS',
       error_message = 'R2P1 synthetic terminal failure', completed_at = clock_timestamp()
 WHERE item_id IN (SELECT md5('r2p1-item-0-' || n)::uuid FROM generate_series(1, 2000) n);

-- Same physical state in both databases before measurement.
VACUUM (ANALYZE) customers, accounts, organizations, organization_memberships, organization_membership_roles,
                 payroll_batches, payroll_items, payroll_item_results;
SELECT 'S1' AS stage, (SELECT count(*) FROM payroll_items) AS items, (SELECT count(*) FROM payroll_item_results) AS results,
       (SELECT count(*) FROM organizations) AS organizations;
