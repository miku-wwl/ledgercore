-- Round 4 / E9: the W3C traceparent active when an operation was accepted and when each callback was enqueued. The
-- callback dispatcher starts a new trace per delivery attempt and links it to this context. Correlation data only;
-- callbacks stay authenticated solely by the HMAC over their body bytes. V1-V3 remain unchanged.
ALTER TABLE simulator_operations ADD COLUMN trace_parent VARCHAR(55);
ALTER TABLE simulator_callback_outbox ADD COLUMN trace_parent VARCHAR(55);
ALTER TABLE simulator_operations ADD CONSTRAINT ck_simulator_operation_trace_parent
    CHECK (trace_parent IS NULL OR trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
ALTER TABLE simulator_callback_outbox ADD CONSTRAINT ck_simulator_callback_trace_parent
    CHECK (trace_parent IS NULL OR trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');

CREATE FUNCTION protect_simulator_trace_parent() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.trace_parent IS NOT NULL AND NEW.trace_parent IS DISTINCT FROM OLD.trace_parent THEN
        RAISE EXCEPTION 'Recorded trace context is immutable.' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_simulator_operation_trace_parent
    BEFORE UPDATE OF trace_parent ON simulator_operations
    FOR EACH ROW EXECUTE FUNCTION protect_simulator_trace_parent();
CREATE TRIGGER trg_simulator_callback_trace_parent
    BEFORE UPDATE OF trace_parent ON simulator_callback_outbox
    FOR EACH ROW EXECUTE FUNCTION protect_simulator_trace_parent();
