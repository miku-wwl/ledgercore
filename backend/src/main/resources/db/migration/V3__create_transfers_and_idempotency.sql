ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_owner UNIQUE (id, owner_customer_id);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    owner_customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    source_account_id UUID NOT NULL,
    target_account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    journal_entry_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_transfers_id_owner UNIQUE (id, owner_customer_id),
    CONSTRAINT ck_transfers_accounts_distinct CHECK (source_account_id <> target_account_id),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_transfers_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT fk_transfers_owned_source FOREIGN KEY (source_account_id, owner_customer_id)
        REFERENCES accounts (id, owner_customer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_journal_currency FOREIGN KEY (journal_entry_id, currency)
        REFERENCES journal_entries (id, currency) ON DELETE RESTRICT
);

CREATE INDEX ix_transfers_source_created ON transfers (source_account_id, created_at DESC, id DESC);
CREATE INDEX ix_transfers_target_created ON transfers (target_account_id, created_at DESC, id DESC);

CREATE TABLE idempotency_records (
    owner_customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    transfer_id UUID,
    response_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (owner_customer_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_operation_transfer CHECK (operation = 'TRANSFER'),
    CONSTRAINT ck_idempotency_key_non_blank CHECK (length(trim(idempotency_key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_idempotency_fingerprint_sha256 CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_success_has_receipt CHECK (
        (status = 'IN_PROGRESS' AND transfer_id IS NULL AND response_payload IS NULL)
        OR (status = 'SUCCEEDED' AND transfer_id IS NOT NULL AND response_payload IS NOT NULL)
    ),
    CONSTRAINT fk_idempotency_transfer_owner FOREIGN KEY (transfer_id, owner_customer_id)
        REFERENCES transfers (id, owner_customer_id) ON DELETE RESTRICT
);

CREATE INDEX ix_idempotency_transfer ON idempotency_records (transfer_id) WHERE transfer_id IS NOT NULL;

CREATE FUNCTION ledgercore_assert_transfer_matches_journal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_ledger_account_id UUID;
    target_ledger_account_id UUID;
    actual_entry_type VARCHAR(32);
    posting_count BIGINT;
    source_debit NUMERIC;
    target_credit NUMERIC;
BEGIN
    SELECT entry_type INTO actual_entry_type FROM journal_entries WHERE id = NEW.journal_entry_id;
    IF actual_entry_type <> 'TRANSFER' THEN
        RAISE EXCEPTION 'Transfer % must reference a TRANSFER journal entry', NEW.id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_transfers_journal_type';
    END IF;

    SELECT id INTO source_ledger_account_id
      FROM ledger_accounts
     WHERE customer_account_id = NEW.source_account_id AND kind = 'CUSTOMER_DEPOSIT';
    SELECT id INTO target_ledger_account_id
      FROM ledger_accounts
     WHERE customer_account_id = NEW.target_account_id AND kind = 'CUSTOMER_DEPOSIT';
    IF source_ledger_account_id IS NULL OR target_ledger_account_id IS NULL THEN
        RAISE EXCEPTION 'Transfer % must reference customer deposit ledger accounts', NEW.id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_transfers_customer_ledgers';
    END IF;

    SELECT count(*),
           COALESCE(sum(amount_minor) FILTER (
               WHERE ledger_account_id = source_ledger_account_id AND side = 'DEBIT'), 0),
           COALESCE(sum(amount_minor) FILTER (
               WHERE ledger_account_id = target_ledger_account_id AND side = 'CREDIT'), 0)
      INTO posting_count, source_debit, target_credit
      FROM postings
     WHERE journal_entry_id = NEW.journal_entry_id;

    IF posting_count <> 2 OR source_debit <> NEW.amount_minor OR target_credit <> NEW.amount_minor THEN
        RAISE EXCEPTION 'Transfer % must have one matching debit and credit posting', NEW.id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_transfers_match_postings';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_transfers_match_journal
    AFTER INSERT ON transfers
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_transfer_matches_journal();

CREATE FUNCTION ledgercore_assert_transfer_has_record()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.entry_type = 'TRANSFER' AND NOT EXISTS (
        SELECT 1 FROM transfers WHERE journal_entry_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'TRANSFER journal entry % requires a transfer record', NEW.id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_transfer_journal_has_transfer';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_transfer_journal_has_record
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_transfer_has_record();

CREATE FUNCTION ledgercore_validate_idempotency_transition()
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
       OR NEW.transfer_id IS NULL OR NEW.response_payload IS NULL THEN
        RAISE EXCEPTION 'Idempotency record permits only one immutable success transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_idempotency_success_once
    BEFORE UPDATE OR DELETE ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION ledgercore_validate_idempotency_transition();

CREATE TRIGGER tr_transfers_append_only
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION ledgercore_reject_ledger_history_change();
