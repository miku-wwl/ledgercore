ALTER TABLE simulator_operations ADD COLUMN correlation_id VARCHAR(64);
UPDATE simulator_operations SET correlation_id = operation_id::text;
ALTER TABLE simulator_operations ALTER COLUMN correlation_id SET NOT NULL;
ALTER TABLE simulator_operations ADD CONSTRAINT ck_simulator_operation_correlation_id
    CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$');

CREATE FUNCTION protect_simulator_correlation_id() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'Simulator operation correlation identity is immutable.' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_simulator_operation_correlation_id
    BEFORE UPDATE OF correlation_id ON simulator_operations
    FOR EACH ROW EXECUTE FUNCTION protect_simulator_correlation_id();
