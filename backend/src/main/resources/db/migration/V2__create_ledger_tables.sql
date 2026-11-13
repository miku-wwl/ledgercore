CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    customer_account_id UUID REFERENCES accounts (id) ON DELETE RESTRICT,
    kind VARCHAR(32) NOT NULL,
    normal_side VARCHAR(6) NOT NULL,
    is_system BOOLEAN NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_ledger_accounts_id_currency UNIQUE (id, currency),
    CONSTRAINT ck_ledger_accounts_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT ck_ledger_account_definition CHECK (
        (kind = 'CUSTOMER_DEPOSIT' AND customer_account_id IS NOT NULL AND normal_side = 'CREDIT' AND NOT is_system)
        OR (kind = 'SIMULATION_RESERVE' AND customer_account_id IS NULL AND normal_side = 'DEBIT' AND is_system)
    )
);

CREATE UNIQUE INDEX uq_ledger_accounts_customer_account
    ON ledger_accounts (customer_account_id)
    WHERE kind = 'CUSTOMER_DEPOSIT';

CREATE UNIQUE INDEX uq_ledger_accounts_simulation_reserve
    ON ledger_accounts (kind)
    WHERE kind = 'SIMULATION_RESERVE';

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    entry_type VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    reference VARCHAR(128) NOT NULL,
    reverses_entry_id UUID UNIQUE REFERENCES journal_entries (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_journal_entries_id_currency UNIQUE (id, currency),
    CONSTRAINT ck_journal_entries_type CHECK (entry_type IN ('OPENING_FUNDING', 'SIMULATED_DEPOSIT', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT ck_journal_entries_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT ck_journal_entries_not_self_reversal CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> id),
    CONSTRAINT ck_journal_entries_reference_not_blank CHECK (length(trim(reference)) > 0)
);

CREATE TABLE postings (
    id UUID PRIMARY KEY,
    journal_entry_id UUID NOT NULL,
    ledger_account_id UUID NOT NULL,
    side VARCHAR(6) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    CONSTRAINT ck_postings_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_postings_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_postings_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT uq_postings_entry_account UNIQUE (journal_entry_id, ledger_account_id),
    CONSTRAINT fk_postings_journal_currency FOREIGN KEY (journal_entry_id, currency)
        REFERENCES journal_entries (id, currency) ON DELETE RESTRICT,
    CONSTRAINT fk_postings_ledger_currency FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger_accounts (id, currency) ON DELETE RESTRICT
);

CREATE INDEX ix_postings_ledger_account ON postings (ledger_account_id, journal_entry_id);
CREATE INDEX ix_postings_journal_entry ON postings (journal_entry_id, id);

CREATE TABLE account_balance_projection (
    ledger_account_id UUID PRIMARY KEY,
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    balance_minor BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_account_balance_projection_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT ck_account_balance_projection_non_negative CHECK (balance_minor >= 0),
    CONSTRAINT fk_account_balance_projection_ledger_currency FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger_accounts (id, currency) ON DELETE RESTRICT
);

INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
VALUES ('00000000-0000-5000-8000-000000000001', NULL, 'SIMULATION_RESERVE', 'DEBIT', TRUE, 'NZD');

INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
VALUES ('00000000-0000-5000-8000-000000000001', 'NZD', 0);

CREATE FUNCTION ledgercore_assert_journal_entry_balanced()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_entry_id UUID;
    posting_count BIGINT;
    debit_total NUMERIC;
    credit_total NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target_entry_id := NEW.id;
    ELSE
        target_entry_id := NEW.journal_entry_id;
    END IF;

    SELECT count(*),
           COALESCE(sum(amount_minor) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(sum(amount_minor) FILTER (WHERE side = 'CREDIT'), 0)
      INTO posting_count, debit_total, credit_total
      FROM postings
     WHERE journal_entry_id = target_entry_id;

    IF posting_count < 2 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'Journal entry % must contain at least two balanced postings', target_entry_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_journal_entries_balanced';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_journal_entry_balanced();

CREATE CONSTRAINT TRIGGER ct_postings_keep_entry_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_journal_entry_balanced();

CREATE FUNCTION ledgercore_reject_ledger_history_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Posted ledger history is append-only; record a new reversal entry'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER tr_journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledgercore_reject_ledger_history_change();

CREATE TRIGGER tr_postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledgercore_reject_ledger_history_change();
