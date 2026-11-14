CREATE TABLE simulator_operations (
    operation_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE,
    intent_digest CHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    beneficiary_reference VARCHAR(80) NOT NULL,
    routing_code VARCHAR(32) NOT NULL,
    outcome_policy VARCHAR(16) NOT NULL,
    external_status VARCHAR(16) NOT NULL,
    inquiry_count INTEGER NOT NULL DEFAULT 0,
    simulator_reference VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_simulator_operation_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_simulator_operation_currency CHECK (currency = 'NZD'),
    CONSTRAINT ck_simulator_operation_digest CHECK (intent_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_simulator_operation_policy CHECK (outcome_policy IN ('SETTLE', 'REJECT', 'ACCEPT', 'PENDING', 'UNKNOWN')),
    CONSTRAINT ck_simulator_operation_status CHECK (external_status IN ('ACCEPTED', 'PENDING', 'SETTLED', 'REJECTED', 'UNKNOWN')),
    CONSTRAINT ck_simulator_operation_inquiries CHECK (inquiry_count >= 0)
);
CREATE INDEX ix_simulator_operations_status ON simulator_operations (external_status, updated_at DESC, operation_id);

CREATE FUNCTION protect_simulator_operation_facts() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
        OR NEW.intent_digest IS DISTINCT FROM OLD.intent_digest OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.beneficiary_reference IS DISTINCT FROM OLD.beneficiary_reference
        OR NEW.routing_code IS DISTINCT FROM OLD.routing_code OR NEW.outcome_policy IS DISTINCT FROM OLD.outcome_policy
        OR NEW.simulator_reference IS DISTINCT FROM OLD.simulator_reference
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Simulator operation facts are immutable.' USING ERRCODE = '55000';
    END IF;
    IF NEW.external_status IS DISTINCT FROM OLD.external_status AND NOT (
        (OLD.external_status = 'ACCEPTED' AND NEW.external_status = 'PENDING') OR
        (OLD.external_status = 'UNKNOWN' AND NEW.external_status IN ('SETTLED', 'REJECTED')) OR
        (OLD.external_status = 'PENDING' AND NEW.external_status IN ('SETTLED', 'REJECTED'))
    ) THEN
        RAISE EXCEPTION 'Illegal simulator operation transition: % -> %.', OLD.external_status, NEW.external_status
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_simulator_operation_facts
    BEFORE UPDATE ON simulator_operations FOR EACH ROW EXECUTE FUNCTION protect_simulator_operation_facts();

CREATE TABLE simulator_callback_outbox (
    callback_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES simulator_operations (operation_id) ON DELETE RESTRICT,
    sequence_no BIGINT NOT NULL,
    external_status VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uq_simulator_callback_sequence UNIQUE (operation_id, sequence_no),
    CONSTRAINT ck_simulator_callback_status CHECK (external_status IN ('ACCEPTED', 'PENDING', 'SETTLED', 'REJECTED', 'UNKNOWN')),
    CONSTRAINT ck_simulator_callback_delivery CHECK (delivery_status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'DEAD')),
    CONSTRAINT ck_simulator_callback_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_simulator_callback_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_simulator_callback_metadata CHECK (
        (delivery_status = 'DELIVERED' AND delivered_at IS NOT NULL AND lease_until IS NULL)
        OR (delivery_status <> 'DELIVERED' AND delivered_at IS NULL)
    )
);
CREATE INDEX ix_simulator_callback_pending
    ON simulator_callback_outbox (next_attempt_at, created_at, callback_id)
    WHERE delivery_status IN ('PENDING', 'PROCESSING');

CREATE FUNCTION protect_simulator_callback_payload() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.callback_id IS DISTINCT FROM OLD.callback_id OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.sequence_no IS DISTINCT FROM OLD.sequence_no OR NEW.external_status IS DISTINCT FROM OLD.external_status
        OR NEW.payload IS DISTINCT FROM OLD.payload OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Simulator callback facts are immutable.' USING ERRCODE = '55000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count OR NEW.delivery_status IS DISTINCT FROM OLD.delivery_status AND NOT (
        (OLD.delivery_status = 'PENDING' AND NEW.delivery_status = 'PROCESSING') OR
        (OLD.delivery_status = 'PROCESSING' AND NEW.delivery_status IN ('PENDING', 'DELIVERED', 'DEAD'))
    ) THEN
        RAISE EXCEPTION 'Illegal simulator callback delivery transition.' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_simulator_callback_immutable
    BEFORE UPDATE ON simulator_callback_outbox FOR EACH ROW EXECUTE FUNCTION protect_simulator_callback_payload();
CREATE TRIGGER trg_simulator_callback_no_delete
    BEFORE DELETE ON simulator_callback_outbox FOR EACH ROW EXECUTE FUNCTION protect_simulator_callback_payload();
