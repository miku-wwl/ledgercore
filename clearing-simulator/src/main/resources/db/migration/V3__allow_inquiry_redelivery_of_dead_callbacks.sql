-- Round 4 (R4-04): an authenticated inquiry may re-arm callbacks whose delivery budget was exhausted while the bank was
-- unavailable. Before this, a DEAD callback could never leave DEAD, so a bank outage longer than the retry budget left a
-- settled operation permanently undelivered. Re-arming keeps the callback id, sequence, status and signed payload; it only
-- restarts the delivery attempt budget at zero. Deletes stay forbidden. V1-V2 remain unchanged.
CREATE OR REPLACE FUNCTION protect_simulator_callback_payload() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Simulator callback facts are immutable.' USING ERRCODE = '55000';
    END IF;
    IF NEW.callback_id IS DISTINCT FROM OLD.callback_id OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.sequence_no IS DISTINCT FROM OLD.sequence_no OR NEW.external_status IS DISTINCT FROM OLD.external_status
        OR NEW.payload IS DISTINCT FROM OLD.payload OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Simulator callback facts are immutable.' USING ERRCODE = '55000';
    END IF;
    IF OLD.delivery_status = 'DEAD' AND NEW.delivery_status = 'PENDING' THEN
        IF NEW.attempt_count <> 0 OR NEW.lease_until IS NOT NULL THEN
            RAISE EXCEPTION 'A re-armed callback restarts its delivery budget at zero without a lease.' USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
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
