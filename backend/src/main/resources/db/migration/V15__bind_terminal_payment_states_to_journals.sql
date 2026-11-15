-- Round 4 (R4-06): bind a corporate payment's terminal state to the journal that justifies it. Under V8-V14 a direct
-- write could mark a booked payment COMPLETED without a settlement journal or a SETTLED outcome, or FAILED with the
-- customer's funds left in the clearing payable; reconciliation flagged the first case but the database allowed both.
-- The application only ever sets COMPLETED together with the settlement journal and a booked REJECTED together with
-- the refund journal, and never uses FAILED (V8's booking constraint gives every FAILED payment a booking and no state
-- can carry its reversal). Forward-only: existing rows are checked first and the migration refuses, without repairing,
-- if any row contradicts the rule. V1-V14 remain unchanged.
DO $$
DECLARE
    contradictions BIGINT;
BEGIN
    SELECT count(*) INTO contradictions
    FROM corporate_payment_intents
    WHERE (status = 'COMPLETED' AND (settlement_journal_entry_id IS NULL OR external_status IS DISTINCT FROM 'SETTLED'
                                     OR reversal_journal_entry_id IS NOT NULL))
       OR (settlement_journal_entry_id IS NOT NULL AND status <> 'COMPLETED')
       OR (status = 'REJECTED' AND booking_journal_entry_id IS NOT NULL
           AND (reversal_journal_entry_id IS NULL OR external_status IS DISTINCT FROM 'REJECTED'))
       OR (reversal_journal_entry_id IS NOT NULL AND status <> 'REJECTED')
       OR status = 'FAILED';
    IF contradictions > 0 THEN
        RAISE EXCEPTION 'V15 preflight refused to migrate: % corporate payment(s) have a terminal state that does not match their journals.',
            contradictions USING ERRCODE = '23514';
    END IF;
END
$$;

ALTER TABLE corporate_payment_intents ADD CONSTRAINT ck_corporate_payment_terminal_journals CHECK (
    (status <> 'COMPLETED' OR (settlement_journal_entry_id IS NOT NULL AND external_status = 'SETTLED'
                               AND reversal_journal_entry_id IS NULL))
    AND (settlement_journal_entry_id IS NULL OR status = 'COMPLETED')
    AND (status <> 'REJECTED' OR booking_journal_entry_id IS NULL
         OR (reversal_journal_entry_id IS NOT NULL AND external_status = 'REJECTED'))
    AND (reversal_journal_entry_id IS NULL OR status = 'REJECTED')
    AND status <> 'FAILED'
);
