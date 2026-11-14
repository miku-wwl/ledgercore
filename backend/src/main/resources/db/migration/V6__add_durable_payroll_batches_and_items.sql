ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_owner_customer UNIQUE (id, owner_customer_id);

CREATE TABLE payroll_batches (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    payer_account_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by_customer_id UUID NOT NULL,
    submitted_by_customer_id UUID,
    approved_by_customer_id UUID,
    rejected_by_customer_id UUID,
    rejection_reason VARCHAR(500),
    content_digest VARCHAR(64),
    approved_content_digest VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_payroll_batches_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_payroll_batches_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED',
        'PARTIALLY_COMPLETED', 'FAILED'
    )),
    CONSTRAINT ck_payroll_batches_digest_format CHECK (
        (content_digest IS NULL OR content_digest ~ '^[0-9a-f]{64}$') AND
        (approved_content_digest IS NULL OR approved_content_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_payroll_batches_decision_state CHECK (
        (status = 'DRAFT' AND submitted_by_customer_id IS NULL AND submitted_at IS NULL
            AND content_digest IS NULL AND approved_by_customer_id IS NULL AND approved_at IS NULL
            AND approved_content_digest IS NULL AND rejected_by_customer_id IS NULL
            AND rejected_at IS NULL AND rejection_reason IS NULL AND started_at IS NULL AND completed_at IS NULL)
        OR
        (status = 'SUBMITTED' AND submitted_by_customer_id IS NOT NULL AND submitted_at IS NOT NULL
            AND content_digest IS NOT NULL AND approved_by_customer_id IS NULL AND approved_at IS NULL
            AND approved_content_digest IS NULL AND rejected_by_customer_id IS NULL
            AND rejected_at IS NULL AND rejection_reason IS NULL AND started_at IS NULL AND completed_at IS NULL)
        OR
        (status = 'REJECTED' AND submitted_by_customer_id IS NOT NULL AND submitted_at IS NOT NULL
            AND content_digest IS NOT NULL AND approved_by_customer_id IS NULL AND approved_at IS NULL
            AND approved_content_digest IS NULL AND rejected_by_customer_id IS NOT NULL
            AND rejected_at IS NOT NULL AND length(trim(rejection_reason)) > 0
            AND started_at IS NULL AND completed_at IS NULL)
        OR
        (status = 'APPROVED' AND submitted_by_customer_id IS NOT NULL AND submitted_at IS NOT NULL
            AND content_digest IS NOT NULL AND approved_by_customer_id IS NOT NULL AND approved_at IS NOT NULL
            AND approved_content_digest = content_digest AND rejected_by_customer_id IS NULL
            AND rejected_at IS NULL AND rejection_reason IS NULL AND started_at IS NULL AND completed_at IS NULL)
        OR
        (status = 'PROCESSING' AND submitted_by_customer_id IS NOT NULL AND submitted_at IS NOT NULL
            AND content_digest IS NOT NULL AND approved_by_customer_id IS NOT NULL AND approved_at IS NOT NULL
            AND approved_content_digest = content_digest AND rejected_by_customer_id IS NULL
            AND rejected_at IS NULL AND rejection_reason IS NULL AND started_at IS NOT NULL AND completed_at IS NULL)
        OR
        (status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED')
            AND submitted_by_customer_id IS NOT NULL AND submitted_at IS NOT NULL
            AND content_digest IS NOT NULL AND approved_by_customer_id IS NOT NULL AND approved_at IS NOT NULL
            AND approved_content_digest = content_digest AND rejected_by_customer_id IS NULL
            AND rejected_at IS NULL AND rejection_reason IS NULL AND started_at IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_payroll_batches_maker_checker CHECK (
        approved_by_customer_id IS NULL OR approved_by_customer_id <> submitted_by_customer_id
    ),
    CONSTRAINT fk_payroll_batches_payer_organization
        FOREIGN KEY (payer_account_id, organization_id)
        REFERENCES accounts (id, owner_organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_batches_creator_membership
        FOREIGN KEY (organization_id, created_by_customer_id)
        REFERENCES organization_memberships (organization_id, customer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_batches_submitter_membership
        FOREIGN KEY (organization_id, submitted_by_customer_id)
        REFERENCES organization_memberships (organization_id, customer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_batches_approver_membership
        FOREIGN KEY (organization_id, approved_by_customer_id)
        REFERENCES organization_memberships (organization_id, customer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_batches_rejector_membership
        FOREIGN KEY (organization_id, rejected_by_customer_id)
        REFERENCES organization_memberships (organization_id, customer_id) ON DELETE RESTRICT
);

CREATE INDEX ix_payroll_batches_org_created
    ON payroll_batches (organization_id, created_at DESC, id DESC);

CREATE TABLE payroll_items (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES payroll_batches (id) ON DELETE RESTRICT,
    sequence_no INTEGER NOT NULL,
    employee_reference VARCHAR(80) NOT NULL,
    employee_name VARCHAR(120) NOT NULL,
    payee_account_id UUID NOT NULL,
    payee_owner_customer_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'NZD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_payroll_items_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_payroll_items_reference_not_blank CHECK (length(trim(employee_reference)) > 0),
    CONSTRAINT ck_payroll_items_employee_not_blank CHECK (length(trim(employee_name)) > 0),
    CONSTRAINT ck_payroll_items_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payroll_items_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT uq_payroll_items_batch_sequence UNIQUE (batch_id, sequence_no),
    CONSTRAINT fk_payroll_items_personal_payee
        FOREIGN KEY (payee_account_id, payee_owner_customer_id)
        REFERENCES accounts (id, owner_customer_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_payroll_items_batch_employee_reference
    ON payroll_items (batch_id, lower(employee_reference));

CREATE INDEX ix_payroll_items_batch_payee
    ON payroll_items (batch_id, payee_account_id);

CREATE TABLE payroll_item_results (
    item_id UUID PRIMARY KEY REFERENCES payroll_items (id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    transfer_id UUID UNIQUE REFERENCES transfers (id) ON DELETE RESTRICT,
    error_code VARCHAR(64),
    error_message VARCHAR(300),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_payroll_item_results_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payroll_item_results_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_payroll_item_results_state CHECK (
        (status = 'PENDING' AND transfer_id IS NULL AND error_code IS NULL AND error_message IS NULL
            AND completed_at IS NULL)
        OR
        (status = 'SUCCEEDED' AND transfer_id IS NOT NULL AND error_code IS NULL AND error_message IS NULL
            AND completed_at IS NOT NULL AND attempt_count > 0)
        OR
        (status = 'FAILED' AND transfer_id IS NULL AND error_code IS NOT NULL AND error_message IS NOT NULL
            AND completed_at IS NOT NULL AND attempt_count > 0)
    )
);

CREATE INDEX ix_payroll_item_results_pending
    ON payroll_item_results (item_id)
    WHERE status = 'PENDING';

CREATE FUNCTION enforce_payroll_item_intent_mutability() RETURNS TRIGGER AS $$
DECLARE
    target_batch UUID;
    batch_status VARCHAR(24);
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_batch := OLD.batch_id;
    ELSE
        target_batch := NEW.batch_id;
    END IF;

    SELECT status INTO batch_status
    FROM payroll_batches
    WHERE id = target_batch
    FOR UPDATE;

    IF batch_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Payroll item intent is immutable after submission.'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payroll_item_intent_mutability
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_items
    FOR EACH ROW EXECUTE FUNCTION enforce_payroll_item_intent_mutability();

CREATE FUNCTION enforce_payroll_batch_transition() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
        NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
        NEW.payer_account_id IS DISTINCT FROM OLD.payer_account_id OR
        NEW.name IS DISTINCT FROM OLD.name OR
        NEW.created_by_customer_id IS DISTINCT FROM OLD.created_by_customer_id
    ) THEN
        RAISE EXCEPTION 'Payroll batch identity is immutable after draft.'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'DRAFT' AND (
        NEW.submitted_by_customer_id IS DISTINCT FROM OLD.submitted_by_customer_id OR
        NEW.submitted_at IS DISTINCT FROM OLD.submitted_at OR
        NEW.content_digest IS DISTINCT FROM OLD.content_digest
    ) THEN
        RAISE EXCEPTION 'Submitted payroll intent metadata is immutable.'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status NOT IN ('DRAFT', 'SUBMITTED') AND (
        NEW.approved_by_customer_id IS DISTINCT FROM OLD.approved_by_customer_id OR
        NEW.approved_at IS DISTINCT FROM OLD.approved_at OR
        NEW.approved_content_digest IS DISTINCT FROM OLD.approved_content_digest OR
        NEW.rejected_by_customer_id IS DISTINCT FROM OLD.rejected_by_customer_id OR
        NEW.rejected_at IS DISTINCT FROM OLD.rejected_at OR
        NEW.rejection_reason IS DISTINCT FROM OLD.rejection_reason
    ) THEN
        RAISE EXCEPTION 'Payroll decision metadata is immutable after a decision.'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED') OR
        (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'REJECTED')) OR
        (OLD.status = 'APPROVED' AND NEW.status = 'PROCESSING') OR
        (OLD.status = 'PROCESSING' AND NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Illegal payroll batch transition: % -> %.', OLD.status, NEW.status
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payroll_batch_transition
    BEFORE UPDATE ON payroll_batches
    FOR EACH ROW EXECUTE FUNCTION enforce_payroll_batch_transition();

CREATE FUNCTION enforce_payroll_item_result_transition() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'A terminal payroll item result is immutable.'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'PENDING' AND NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'Payroll item attempt count cannot decrease.'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payroll_item_result_transition
    BEFORE UPDATE ON payroll_item_results
    FOR EACH ROW EXECUTE FUNCTION enforce_payroll_item_result_transition();

CREATE FUNCTION require_payroll_item_result() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM payroll_item_results WHERE item_id = NEW.id) THEN
        RAISE EXCEPTION 'Every payroll item must have a durable result row.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_payroll_item_result_required
    AFTER INSERT ON payroll_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_payroll_item_result();

COMMENT ON COLUMN payroll_batches.created_by_customer_id IS
    'Authenticated human who created the draft and whose identity scopes payroll transfer idempotency.';
COMMENT ON COLUMN payroll_batches.content_digest IS
    'Canonical SHA-256 digest of submitted payroll intent, bound to approval.';
COMMENT ON COLUMN payroll_batches.approved_content_digest IS
    'Digest the independent approver authorized; must equal the submitted intent digest.';
COMMENT ON COLUMN payroll_items.payee_owner_customer_id IS
    'Personal account owner captured from the account service and enforced by a composite foreign key.';
COMMENT ON TABLE payroll_item_results IS
    'Mutable execution results are separate from immutable submitted payroll item intent.';
