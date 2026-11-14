CREATE FUNCTION protect_corporate_payment_clearing_state() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.external_status IS DISTINCT FROM OLD.external_status AND NOT (
        (OLD.external_status IS NULL AND NEW.external_status IN ('ACCEPTED', 'PENDING', 'UNKNOWN', 'SETTLED', 'REJECTED')) OR
        (OLD.external_status = 'ACCEPTED' AND NEW.external_status IN ('PENDING', 'UNKNOWN', 'SETTLED', 'REJECTED')) OR
        (OLD.external_status = 'PENDING' AND NEW.external_status IN ('UNKNOWN', 'SETTLED', 'REJECTED')) OR
        (OLD.external_status = 'UNKNOWN' AND NEW.external_status IN ('SETTLED', 'REJECTED'))
    ) THEN
        RAISE EXCEPTION 'Illegal external clearing transition: % -> %.', OLD.external_status, NEW.external_status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_corporate_payment_clearing_state
    BEFORE UPDATE OF external_status ON corporate_payment_intents
    FOR EACH ROW EXECUTE FUNCTION protect_corporate_payment_clearing_state();

CREATE FUNCTION protect_clearing_instruction_lifecycle() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.payment_intent_id IS DISTINCT FROM OLD.payment_intent_id
        OR NEW.intent_digest IS DISTINCT FROM OLD.intent_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Clearing instruction identity and intent are immutable.' USING ERRCODE = '55000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'Clearing instruction attempt count cannot decrease.' USING ERRCODE = '55000';
    END IF;
    IF OLD.simulator_reference IS NOT NULL AND NEW.simulator_reference IS DISTINCT FROM OLD.simulator_reference THEN
        RAISE EXCEPTION 'Clearing simulator reference is immutable once assigned.' USING ERRCODE = '55000';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'READY' AND NEW.status IN ('SENT', 'ACCEPTED', 'PENDING', 'UNKNOWN', 'SETTLED', 'REJECTED', 'DEAD')) OR
        (OLD.status = 'SENT' AND NEW.status IN ('ACCEPTED', 'PENDING', 'UNKNOWN', 'SETTLED', 'REJECTED', 'DEAD')) OR
        (OLD.status = 'ACCEPTED' AND NEW.status IN ('PENDING', 'UNKNOWN', 'SETTLED', 'REJECTED', 'DEAD')) OR
        (OLD.status = 'PENDING' AND NEW.status IN ('UNKNOWN', 'SETTLED', 'REJECTED', 'DEAD')) OR
        (OLD.status = 'UNKNOWN' AND NEW.status IN ('SETTLED', 'REJECTED', 'DEAD')) OR
        (OLD.status = 'DEAD' AND NEW.status = 'READY')
    ) THEN
        RAISE EXCEPTION 'Illegal clearing instruction transition: % -> %.', OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_clearing_instruction_lifecycle
    BEFORE UPDATE ON clearing_instructions
    FOR EACH ROW EXECUTE FUNCTION protect_clearing_instruction_lifecycle();
