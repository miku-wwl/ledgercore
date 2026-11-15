-- Round 4 (R4-02): a second bank-owned simulated asset so that the settlement reserve can be genuinely finite.
-- Under V1-V12 every balanced journal kept SIMULATION_RESERVE = customer deposits + clearing payable, because a single
-- debit-normal asset funded every credit-normal liability. A settlement could therefore never find the reserve short,
-- and the insufficient-reserve branch was unreachable by any legitimate path. RESERVE_LIQUIDITY_TRANSFER moves simulated
-- liquidity between the reserve and SIMULATION_TREASURY: asset to asset, balanced, never creating money. Only the guarded
-- lab runner calls it; no HTTP route exists. V1-V12 remain unchanged.

ALTER TABLE ledger_accounts
    DROP CONSTRAINT ck_ledger_account_definition;
ALTER TABLE ledger_accounts
    ADD CONSTRAINT ck_ledger_account_definition CHECK (
        (kind = 'CUSTOMER_DEPOSIT' AND customer_account_id IS NOT NULL AND normal_side = 'CREDIT' AND NOT is_system)
        OR (kind = 'SIMULATION_RESERVE' AND customer_account_id IS NULL AND normal_side = 'DEBIT' AND is_system)
        OR (kind = 'CLEARING_PAYABLE' AND customer_account_id IS NULL AND normal_side = 'CREDIT' AND is_system)
        OR (kind = 'SIMULATION_TREASURY' AND customer_account_id IS NULL AND normal_side = 'DEBIT' AND is_system)
    );
CREATE UNIQUE INDEX uq_ledger_accounts_simulation_treasury
    ON ledger_accounts (kind) WHERE kind = 'SIMULATION_TREASURY';

INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
VALUES ('00000000-0000-5000-8000-000000000003', NULL, 'SIMULATION_TREASURY', 'DEBIT', TRUE, 'NZD');
INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
VALUES ('00000000-0000-5000-8000-000000000003', 'NZD', 0);

ALTER TABLE journal_entries
    DROP CONSTRAINT ck_journal_entries_type;
ALTER TABLE journal_entries
    ADD CONSTRAINT ck_journal_entries_type CHECK (entry_type IN (
        'OPENING_FUNDING', 'SIMULATED_DEPOSIT', 'TRANSFER', 'REVERSAL',
        'CORPORATE_PAYMENT_BOOKING', 'CLEARING_SETTLEMENT', 'CORPORATE_PAYMENT_REFUND', 'RESERVE_LIQUIDITY_TRANSFER'
    ));

-- One journal per lab request id and direction, so a repeated runner invocation replays instead of moving funds twice.
CREATE UNIQUE INDEX uq_journal_entries_reserve_liquidity_reference
    ON journal_entries (reference) WHERE entry_type = 'RESERVE_LIQUIDITY_TRANSFER';

CREATE FUNCTION assert_reserve_liquidity_journal() RETURNS TRIGGER AS $$
DECLARE
    treasury CONSTANT UUID := '00000000-0000-5000-8000-000000000003';
    reserve CONSTANT UUID := '00000000-0000-5000-8000-000000000001';
    match_parts TEXT[];
    posting_count BIGINT;
    treasury_postings BIGINT;
    reserve_debit BIGINT;
    reserve_credit BIGINT;
    treasury_debit BIGINT;
    treasury_credit BIGINT;
BEGIN
    SELECT count(*), count(*) FILTER (WHERE ledger_account_id = treasury),
           COALESCE(sum(amount_minor) FILTER (WHERE ledger_account_id = reserve AND side = 'DEBIT'), 0),
           COALESCE(sum(amount_minor) FILTER (WHERE ledger_account_id = reserve AND side = 'CREDIT'), 0),
           COALESCE(sum(amount_minor) FILTER (WHERE ledger_account_id = treasury AND side = 'DEBIT'), 0),
           COALESCE(sum(amount_minor) FILTER (WHERE ledger_account_id = treasury AND side = 'CREDIT'), 0)
      INTO posting_count, treasury_postings, reserve_debit, reserve_credit, treasury_debit, treasury_credit
      FROM postings WHERE journal_entry_id = NEW.id;

    IF NEW.entry_type <> 'RESERVE_LIQUIDITY_TRANSFER' THEN
        IF treasury_postings > 0 THEN
            RAISE EXCEPTION 'Only a reserve liquidity transfer may post to the simulation treasury.' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    match_parts := regexp_match(NEW.reference, '^reserve-liquidity:([0-9a-fA-F-]{36}):(OUT|IN)$');
    IF match_parts IS NULL OR NEW.reverses_entry_id IS NOT NULL THEN
        RAISE EXCEPTION 'A reserve liquidity transfer requires a request reference and cannot be a reversal.'
            USING ERRCODE = '23514';
    END IF;
    IF posting_count <> 2 OR treasury_postings <> 1
        OR (match_parts[2] = 'OUT' AND NOT (treasury_debit > 0 AND treasury_debit = reserve_credit
                                           AND reserve_debit = 0 AND treasury_credit = 0))
        OR (match_parts[2] = 'IN' AND NOT (reserve_debit > 0 AND reserve_debit = treasury_credit
                                          AND treasury_debit = 0 AND reserve_credit = 0)) THEN
        RAISE EXCEPTION 'A reserve liquidity transfer must move one amount between the reserve and the treasury.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER ct_reserve_liquidity_journal_integrity
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_reserve_liquidity_journal();

COMMENT ON TABLE ledger_accounts IS 'Customer deposits plus three system accounts: reserve and treasury (simulated assets) and clearing payable.';
