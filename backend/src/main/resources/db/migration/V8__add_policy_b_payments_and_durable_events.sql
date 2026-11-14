-- Round 3 Policy B and E4/E5 foundations. V1-V7 are published and remain unchanged.

ALTER TABLE organization_membership_roles
    DROP CONSTRAINT ck_organization_membership_role;
ALTER TABLE organization_membership_roles
    ADD CONSTRAINT ck_organization_membership_role CHECK (
        role IN ('ORG_ADMIN', 'PAYROLL_MAKER', 'PAYROLL_APPROVER', 'PAYMENT_MAKER',
                 'PAYMENT_APPROVER', 'FINANCE_VIEWER', 'OPERATIONS')
    );

ALTER TABLE ledger_accounts
    DROP CONSTRAINT ck_ledger_account_definition;
ALTER TABLE ledger_accounts
    ADD CONSTRAINT ck_ledger_account_definition CHECK (
        (kind = 'CUSTOMER_DEPOSIT' AND customer_account_id IS NOT NULL AND normal_side = 'CREDIT' AND NOT is_system)
        OR (kind = 'SIMULATION_RESERVE' AND customer_account_id IS NULL AND normal_side = 'DEBIT' AND is_system)
        OR (kind = 'CLEARING_PAYABLE' AND customer_account_id IS NULL AND normal_side = 'CREDIT' AND is_system)
    );
CREATE UNIQUE INDEX uq_ledger_accounts_clearing_payable
    ON ledger_accounts (kind) WHERE kind = 'CLEARING_PAYABLE';

INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
VALUES ('00000000-0000-5000-8000-000000000002', NULL, 'CLEARING_PAYABLE', 'CREDIT', TRUE, 'NZD');
INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
VALUES ('00000000-0000-5000-8000-000000000002', 'NZD', 0);

ALTER TABLE journal_entries
    DROP CONSTRAINT ck_journal_entries_type;
ALTER TABLE journal_entries
    ADD CONSTRAINT ck_journal_entries_type CHECK (entry_type IN (
        'OPENING_FUNDING', 'SIMULATED_DEPOSIT', 'TRANSFER', 'REVERSAL',
        'CORPORATE_PAYMENT_BOOKING', 'CLEARING_SETTLEMENT', 'CORPORATE_PAYMENT_REFUND'
    ));

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    organization_id UUID REFERENCES organizations (id) ON DELETE RESTRICT,
    correlation_id VARCHAR(80),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_audit_action_not_blank CHECK (length(trim(action)) > 0),
    CONSTRAINT ck_audit_resource_not_blank CHECK (length(trim(resource_type)) > 0)
);
CREATE INDEX ix_audit_events_resource_time ON audit_events (resource_type, resource_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_events_org_time ON audit_events (organization_id, occurred_at DESC, id DESC);

CREATE FUNCTION reject_audit_event_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit history is append-only.' USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TABLE corporate_payment_intents (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    source_account_id UUID NOT NULL,
    created_by_customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    submitted_by_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    approved_by_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    rejected_by_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    beneficiary_name VARCHAR(120) NOT NULL,
    beneficiary_account_reference VARCHAR(80) NOT NULL,
    beneficiary_routing_code VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    version INTEGER NOT NULL DEFAULT 1,
    intent_digest CHAR(64),
    approved_version INTEGER,
    approved_digest CHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    external_status VARCHAR(16),
    rejection_reason VARCHAR(300),
    booking_journal_entry_id UUID REFERENCES journal_entries (id) ON DELETE RESTRICT,
    settlement_journal_entry_id UUID REFERENCES journal_entries (id) ON DELETE RESTRICT,
    reversal_journal_entry_id UUID REFERENCES journal_entries (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_corporate_payment_source_owner FOREIGN KEY (source_account_id, organization_id)
        REFERENCES accounts (id, owner_organization_id) ON DELETE RESTRICT,
    CONSTRAINT ck_corporate_payment_positive_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_corporate_payment_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT ck_corporate_payment_version CHECK (version > 0),
    CONSTRAINT ck_corporate_payment_state CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'APPROVED', 'PENDING_CLEARING', 'UNKNOWN', 'COMPLETED', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT ck_corporate_payment_external_state CHECK (
        external_status IS NULL OR external_status IN ('ACCEPTED', 'PENDING', 'SETTLED', 'REJECTED', 'UNKNOWN')
    ),
    CONSTRAINT ck_corporate_payment_beneficiary CHECK (
        length(trim(beneficiary_name)) > 0 AND length(trim(beneficiary_account_reference)) > 0
        AND length(trim(beneficiary_routing_code)) > 0
    ),
    CONSTRAINT ck_corporate_payment_digest_shape CHECK (
        (intent_digest IS NULL OR intent_digest ~ '^[0-9a-f]{64}$')
        AND (approved_digest IS NULL OR approved_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_corporate_payment_submission_metadata CHECK (
        (status = 'DRAFT' AND submitted_by_customer_id IS NULL AND submitted_at IS NULL AND intent_digest IS NULL)
        OR (status <> 'DRAFT' AND submitted_by_customer_id IS NOT NULL AND submitted_at IS NOT NULL
            AND intent_digest IS NOT NULL)
    ),
    CONSTRAINT ck_corporate_payment_approval_metadata CHECK (
        (status IN ('DRAFT', 'SUBMITTED') AND approved_by_customer_id IS NULL AND approved_at IS NULL
            AND approved_version IS NULL AND approved_digest IS NULL)
        OR (status = 'REJECTED' AND booking_journal_entry_id IS NULL AND approved_by_customer_id IS NULL
            AND approved_at IS NULL AND approved_version IS NULL AND approved_digest IS NULL
            AND rejected_by_customer_id IS NOT NULL)
        OR (status = 'REJECTED' AND booking_journal_entry_id IS NOT NULL AND approved_by_customer_id IS NOT NULL
            AND approved_at IS NOT NULL AND approved_version = version AND approved_digest = intent_digest
            AND approved_by_customer_id <> created_by_customer_id
            AND approved_by_customer_id <> submitted_by_customer_id)
        OR (status NOT IN ('DRAFT', 'SUBMITTED', 'REJECTED') AND approved_by_customer_id IS NOT NULL AND approved_at IS NOT NULL
            AND approved_version = version AND approved_digest = intent_digest
            AND approved_by_customer_id <> created_by_customer_id
            AND approved_by_customer_id <> submitted_by_customer_id)
    ),
    CONSTRAINT ck_corporate_payment_booking_metadata CHECK (
        (status IN ('DRAFT', 'SUBMITTED', 'APPROVED') AND booking_journal_entry_id IS NULL)
        OR (status = 'REJECTED' AND booking_journal_entry_id IS NULL AND external_status IS NULL)
        OR (status IN ('PENDING_CLEARING', 'UNKNOWN', 'COMPLETED', 'REJECTED', 'FAILED')
            AND booking_journal_entry_id IS NOT NULL)
    ),
    CONSTRAINT ck_corporate_payment_terminal_metadata CHECK (
        (status NOT IN ('COMPLETED', 'REJECTED', 'FAILED') OR completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_corporate_payment_operation_identity CHECK (operation_id = id)
);

CREATE INDEX ix_corporate_payment_org_created
    ON corporate_payment_intents (organization_id, created_at DESC, id DESC);
CREATE INDEX ix_corporate_payment_review
    ON corporate_payment_intents (organization_id, status, created_at DESC, id DESC);
CREATE INDEX ix_corporate_payment_outcomes
    ON corporate_payment_intents (status, external_status, updated_at DESC, id DESC);

CREATE FUNCTION enforce_corporate_payment_intent() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Corporate payment intent history is durable and cannot be deleted.'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'DRAFT' AND (
        NEW.id IS DISTINCT FROM OLD.id OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.source_account_id IS DISTINCT FROM OLD.source_account_id
        OR NEW.created_by_customer_id IS DISTINCT FROM OLD.created_by_customer_id
        OR NEW.beneficiary_name IS DISTINCT FROM OLD.beneficiary_name
        OR NEW.beneficiary_account_reference IS DISTINCT FROM OLD.beneficiary_account_reference
        OR NEW.beneficiary_routing_code IS DISTINCT FROM OLD.beneficiary_routing_code
        OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.version IS DISTINCT FROM OLD.version OR NEW.intent_digest IS DISTINCT FROM OLD.intent_digest
        OR NEW.submitted_by_customer_id IS DISTINCT FROM OLD.submitted_by_customer_id
        OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
    ) THEN
        RAISE EXCEPTION 'Submitted corporate payment content is immutable.'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status NOT IN ('DRAFT', 'SUBMITTED') AND (
        NEW.approved_by_customer_id IS DISTINCT FROM OLD.approved_by_customer_id
        OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
        OR NEW.approved_version IS DISTINCT FROM OLD.approved_version
        OR NEW.approved_digest IS DISTINCT FROM OLD.approved_digest
    ) THEN
        RAISE EXCEPTION 'Corporate payment approval is immutable after it is recorded.'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED') OR
        (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'REJECTED')) OR
        (OLD.status = 'APPROVED' AND NEW.status IN ('PENDING_CLEARING', 'FAILED')) OR
        (OLD.status = 'PENDING_CLEARING' AND NEW.status IN ('UNKNOWN', 'COMPLETED', 'REJECTED', 'FAILED')) OR
        (OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED', 'REJECTED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Illegal corporate payment transition: % -> %.', OLD.status, NEW.status
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'APPROVED' AND (
        NEW.approved_by_customer_id IS NULL OR NEW.approved_by_customer_id = NEW.created_by_customer_id
        OR NEW.approved_by_customer_id = NEW.submitted_by_customer_id
        OR NEW.approved_version <> NEW.version OR NEW.approved_digest IS DISTINCT FROM NEW.intent_digest
    ) THEN
        RAISE EXCEPTION 'Corporate payment approval must be independent and bind the submitted intent.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_corporate_payment_intent_integrity
    BEFORE UPDATE OR DELETE ON corporate_payment_intents
    FOR EACH ROW EXECUTE FUNCTION enforce_corporate_payment_intent();

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_key VARCHAR(180) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    actor_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    organization_id UUID REFERENCES organizations (id) ON DELETE RESTRICT,
    correlation_id VARCHAR(80),
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'DEAD')),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_event_names CHECK (
        length(trim(event_key)) > 0 AND length(trim(event_type)) > 0 AND length(trim(aggregate_type)) > 0
    ),
    CONSTRAINT ck_outbox_delivery_metadata CHECK (
        (status = 'DELIVERED' AND delivered_at IS NOT NULL AND lease_until IS NULL)
        OR (status <> 'DELIVERED' AND delivered_at IS NULL)
    )
);
CREATE INDEX ix_outbox_pending_dispatch
    ON outbox_events (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX ix_outbox_deadletter ON outbox_events (created_at DESC, id DESC) WHERE status = 'DEAD';

CREATE FUNCTION protect_outbox_event_fact() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.event_key IS DISTINCT FROM OLD.event_key
        OR NEW.event_type IS DISTINCT FROM OLD.event_type OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.actor_customer_id IS DISTINCT FROM OLD.actor_customer_id
        OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Outbox event facts are immutable.' USING ERRCODE = '55000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count AND NOT (
        OLD.status = 'DEAD' AND NEW.status = 'PENDING' AND NEW.attempt_count = 0
    ) THEN
        RAISE EXCEPTION 'Outbox attempt count cannot decrease.' USING ERRCODE = '55000';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'PENDING' AND NEW.status = 'PROCESSING') OR
        (OLD.status = 'PROCESSING' AND NEW.status IN ('PENDING', 'DELIVERED', 'DEAD')) OR
        (OLD.status = 'DEAD' AND NEW.status = 'PENDING')
    ) THEN
        RAISE EXCEPTION 'Illegal outbox state transition: % -> %.', OLD.status, NEW.status
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status = 'DELIVERED' AND NEW.status <> 'DELIVERED' THEN
        RAISE EXCEPTION 'Delivered outbox events are terminal.' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_outbox_event_fact_immutable
    BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION protect_outbox_event_fact();
CREATE FUNCTION reject_outbox_event_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Outbox event history is durable and cannot be deleted.' USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_outbox_event_append_only
    BEFORE DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION reject_outbox_event_delete();

CREATE TABLE outbox_consumer_receipts (
    event_id UUID NOT NULL REFERENCES outbox_events (id) ON DELETE RESTRICT,
    consumer_key VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    result_digest CHAR(64),
    PRIMARY KEY (event_id, consumer_key),
    CONSTRAINT ck_outbox_consumer_key CHECK (length(trim(consumer_key)) > 0)
);

CREATE TABLE clearing_instructions (
    operation_id UUID PRIMARY KEY REFERENCES corporate_payment_intents (operation_id) ON DELETE RESTRICT,
    payment_intent_id UUID NOT NULL UNIQUE REFERENCES corporate_payment_intents (id) ON DELETE RESTRICT,
    intent_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    simulator_reference VARCHAR(120),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_clearing_instruction_status CHECK (status IN (
        'READY', 'SENT', 'ACCEPTED', 'PENDING', 'UNKNOWN', 'SETTLED', 'REJECTED', 'DEAD'
    )),
    CONSTRAINT ck_clearing_instruction_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_clearing_instruction_digest CHECK (intent_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_clearing_instruction_queue ON clearing_instructions (status, updated_at, operation_id);

CREATE TABLE clearing_callback_inbox (
    callback_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES clearing_instructions (operation_id) ON DELETE RESTRICT,
    sequence_no BIGINT NOT NULL,
    external_status VARCHAR(16) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_clearing_callback_operation_sequence UNIQUE (operation_id, sequence_no),
    CONSTRAINT ck_clearing_callback_status CHECK (external_status IN ('ACCEPTED', 'PENDING', 'SETTLED', 'REJECTED', 'UNKNOWN')),
    CONSTRAINT ck_clearing_callback_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_clearing_callback_digest CHECK (payload_digest ~ '^[0-9a-f]{64}$')
);
CREATE TRIGGER trg_clearing_callback_append_only
    BEFORE UPDATE OR DELETE ON clearing_callback_inbox
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TRIGGER trg_outbox_consumer_receipt_append_only
    BEFORE UPDATE OR DELETE ON outbox_consumer_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TABLE reconciliation_exceptions (
    id UUID PRIMARY KEY,
    category VARCHAR(64) NOT NULL,
    severity VARCHAR(12) NOT NULL,
    operation_id UUID,
    organization_id UUID REFERENCES organizations (id) ON DELETE RESTRICT,
    expected_facts JSONB NOT NULL,
    observed_facts JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_to_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    resolved_by_customer_id UUID REFERENCES customers (id) ON DELETE RESTRICT,
    resolution VARCHAR(500),
    correction_journal_entry_id UUID REFERENCES journal_entries (id) ON DELETE RESTRICT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_reconciliation_exception_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_reconciliation_exception_status CHECK (status IN ('OPEN', 'ASSIGNED', 'RESOLVED')),
    CONSTRAINT ck_reconciliation_resolution CHECK (
        (status = 'RESOLVED' AND resolved_by_customer_id IS NOT NULL AND resolved_at IS NOT NULL
            AND resolution IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_by_customer_id IS NULL AND resolved_at IS NULL)
    )
);
CREATE INDEX ix_reconciliation_exception_open ON reconciliation_exceptions (status, severity, detected_at DESC, id DESC);

CREATE FUNCTION assert_corporate_payment_journal() RETURNS TRIGGER AS $$
DECLARE
    match_parts TEXT[];
    payment_id UUID;
    action_name TEXT;
    source_account UUID;
    payment_amount BIGINT;
    payment_status VARCHAR(24);
    clearing_status VARCHAR(16);
    booking_entry UUID;
    settlement_entry UUID;
    refund_entry UUID;
    source_ledger UUID;
    posting_count BIGINT;
    expected_debits BIGINT;
    expected_credits BIGINT;
    debit_account UUID;
    credit_account UUID;
BEGIN
    IF NEW.entry_type NOT IN ('CORPORATE_PAYMENT_BOOKING', 'CLEARING_SETTLEMENT', 'CORPORATE_PAYMENT_REFUND') THEN
        RETURN NULL;
    END IF;

    match_parts := regexp_match(NEW.reference,
        '^corporate-payment:([0-9a-fA-F-]{36}):(booking|settlement|refund)$');
    IF match_parts IS NULL THEN
        RAISE EXCEPTION 'Corporate payment journal entries require a stable payment reference.'
            USING ERRCODE = '23514';
    END IF;
    payment_id := match_parts[1]::UUID;
    action_name := match_parts[2];

    SELECT source_account_id, amount_minor, status, external_status,
           booking_journal_entry_id, settlement_journal_entry_id, reversal_journal_entry_id
      INTO source_account, payment_amount, payment_status, clearing_status,
           booking_entry, settlement_entry, refund_entry
      FROM corporate_payment_intents
     WHERE id = payment_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Corporate payment journal entry has no persisted payment intent.'
            USING ERRCODE = '23503';
    END IF;

    SELECT id INTO source_ledger FROM ledger_accounts
     WHERE customer_account_id = source_account AND kind = 'CUSTOMER_DEPOSIT';
    IF source_ledger IS NULL THEN
        RAISE EXCEPTION 'Corporate payment source has no customer deposit ledger.'
            USING ERRCODE = '23514';
    END IF;

    CASE action_name
        WHEN 'booking' THEN
            IF NEW.entry_type <> 'CORPORATE_PAYMENT_BOOKING' OR payment_status NOT IN
                    ('PENDING_CLEARING', 'UNKNOWN', 'COMPLETED', 'REJECTED', 'FAILED')
                OR booking_entry IS DISTINCT FROM NEW.id THEN
                RAISE EXCEPTION 'Corporate payment booking is not linked to an independently approved operation.'
                    USING ERRCODE = '23514';
            END IF;
            debit_account := source_ledger;
            credit_account := '00000000-0000-5000-8000-000000000002'::UUID;
        WHEN 'settlement' THEN
            IF NEW.entry_type <> 'CLEARING_SETTLEMENT' OR payment_status <> 'COMPLETED'
                OR clearing_status <> 'SETTLED' OR settlement_entry IS DISTINCT FROM NEW.id THEN
                RAISE EXCEPTION 'Clearing settlement is not linked to a verified settled payment.'
                    USING ERRCODE = '23514';
            END IF;
            debit_account := '00000000-0000-5000-8000-000000000002'::UUID;
            credit_account := '00000000-0000-5000-8000-000000000001'::UUID;
        WHEN 'refund' THEN
            IF NEW.entry_type <> 'CORPORATE_PAYMENT_REFUND' OR payment_status <> 'REJECTED'
                OR clearing_status <> 'REJECTED' OR refund_entry IS DISTINCT FROM NEW.id
                OR settlement_entry IS NOT NULL THEN
                RAISE EXCEPTION 'Refund is not linked to a definitive pre-settlement rejection.'
                    USING ERRCODE = '23514';
            END IF;
            debit_account := '00000000-0000-5000-8000-000000000002'::UUID;
            credit_account := source_ledger;
        ELSE
            RAISE EXCEPTION 'Unsupported corporate payment journal action.' USING ERRCODE = '23514';
    END CASE;

    SELECT count(*), count(*) FILTER (WHERE ledger_account_id = debit_account AND side = 'DEBIT'
                                      AND amount_minor = payment_amount),
           count(*) FILTER (WHERE ledger_account_id = credit_account AND side = 'CREDIT'
                            AND amount_minor = payment_amount)
      INTO posting_count, expected_debits, expected_credits
      FROM postings WHERE journal_entry_id = NEW.id;
    IF posting_count <> 2 OR expected_debits <> 1 OR expected_credits <> 1 THEN
        RAISE EXCEPTION 'Corporate payment journal postings do not match the approved operation amount and accounts.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER ct_corporate_payment_journal_integrity
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_corporate_payment_journal();

COMMENT ON TABLE outbox_events IS 'Transactional at-least-once event queue; event facts commit with their business transaction.';
COMMENT ON TABLE clearing_instructions IS 'Bank-side durable instruction state. The external simulator owns separate persistent state.';
COMMENT ON TABLE clearing_callback_inbox IS 'Authenticated callback inbox; duplicates are keyed by callback ID and operation sequence.';
COMMENT ON TABLE reconciliation_exceptions IS 'Auditable workflow for independently detected financial or cross-system differences.';
