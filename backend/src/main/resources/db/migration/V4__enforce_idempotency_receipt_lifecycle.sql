CREATE UNIQUE INDEX uq_idempotency_transfer
    ON idempotency_records (transfer_id)
    WHERE transfer_id IS NOT NULL;

CREATE OR REPLACE FUNCTION ledgercore_validate_idempotency_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Successful idempotency records are retained as immutable receipts'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'IN_PROGRESS' OR NEW.status <> 'SUCCEEDED'
       OR NEW.owner_customer_id <> OLD.owner_customer_id
       OR NEW.operation <> OLD.operation
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.request_fingerprint <> OLD.request_fingerprint
       OR NEW.created_at <> OLD.created_at
       OR NEW.transfer_id IS NULL OR NEW.response_payload IS NULL THEN
        RAISE EXCEPTION 'Idempotency record permits only one immutable success transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ledgercore_assert_idempotency_succeeded()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    final_status VARCHAR(16);
BEGIN
    SELECT status INTO final_status
      FROM idempotency_records
     WHERE owner_customer_id = NEW.owner_customer_id
       AND operation = NEW.operation
       AND idempotency_key = NEW.idempotency_key;
    IF final_status IS DISTINCT FROM 'SUCCEEDED' THEN
        RAISE EXCEPTION 'An idempotency reservation cannot commit without its success receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_idempotency_must_succeed';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_idempotency_must_succeed
    AFTER INSERT OR UPDATE ON idempotency_records
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_idempotency_succeeded();
