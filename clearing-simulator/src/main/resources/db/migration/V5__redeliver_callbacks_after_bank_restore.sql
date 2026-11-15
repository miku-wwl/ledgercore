-- Round 4 (R4-07): a bank restored to a point before it received callbacks that this simulator already delivered can
-- never learn the outcome, because DELIVERED was terminal and V3 only re-armed DEAD callbacks. An authenticated inquiry
-- now carries the bank's highest received sequence; callbacks after it are re-armed for redelivery whether DEAD or
-- DELIVERED. Identity, sequence, status and signed payload never change; every re-arm increments redelivery_count and
-- stamps last_rearmed_at, so a redelivery is attributable. delivered_at is cleared because the callback is undelivered
-- again for the current bank state. V1-V4 remain unchanged.
ALTER TABLE simulator_callback_outbox ADD COLUMN redelivery_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE simulator_callback_outbox ADD COLUMN last_rearmed_at TIMESTAMPTZ;
ALTER TABLE simulator_callback_outbox ADD CONSTRAINT ck_simulator_callback_redelivery_count CHECK (redelivery_count >= 0);

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
    IF NEW.redelivery_count < OLD.redelivery_count THEN
        RAISE EXCEPTION 'Callback redelivery history cannot decrease.' USING ERRCODE = '55000';
    END IF;
    IF OLD.delivery_status IN ('DEAD', 'DELIVERED') AND NEW.delivery_status = 'PENDING' THEN
        IF NEW.attempt_count <> 0 OR NEW.lease_until IS NOT NULL THEN
            RAISE EXCEPTION 'A re-armed callback restarts its delivery budget at zero without a lease.' USING ERRCODE = '55000';
        END IF;
        IF NEW.redelivery_count <> OLD.redelivery_count + 1 OR NEW.last_rearmed_at IS NULL THEN
            RAISE EXCEPTION 'A re-armed callback must record one more redelivery.' USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.redelivery_count <> OLD.redelivery_count THEN
        RAISE EXCEPTION 'Redelivery is recorded only when a callback is re-armed.' USING ERRCODE = '55000';
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
