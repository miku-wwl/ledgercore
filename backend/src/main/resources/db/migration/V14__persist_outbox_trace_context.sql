-- Round 4 / E9: the W3C traceparent of the transaction that produced an outbox event. A dispatcher starts a new trace
-- for each delivery attempt and links it to this producer context, so asynchronous work is correlated across restarts
-- without pretending to be one continuous span. Observability data only: no authorization reads it. V1-V13 unchanged.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(55);
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_trace_parent
    CHECK (trace_parent IS NULL OR trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');

-- Same rules as V8, with the producer trace context added to the immutable event facts.
CREATE OR REPLACE FUNCTION protect_outbox_event_fact() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.event_key IS DISTINCT FROM OLD.event_key
        OR NEW.event_type IS DISTINCT FROM OLD.event_type OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.actor_customer_id IS DISTINCT FROM OLD.actor_customer_id
        OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.trace_parent IS DISTINCT FROM OLD.trace_parent THEN
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
