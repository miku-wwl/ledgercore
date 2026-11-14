-- Round 2 independent-audit remediation. Forward-only: V1-V6 are unchanged.
--
-- R2-01  V6 checked only NEW.batch_id when a payroll item changed, so an item could be moved out of a SUBMITTED,
--        APPROVED, PROCESSING, or terminal batch into a DRAFT batch (and then rewritten or deleted there).
--        Item identity and batch membership are now immutable, and every intent change locks and checks the item's
--        own batch.
-- R2-02  V6 protected terminal result rows from UPDATE only. Result rows could be deleted (or inserted already
--        terminal), and an INNER JOIN progress query then treated missing results as finished work. Result rows are
--        now created PENDING while the batch is a DRAFT, retained for as long as their item exists, changed only while
--        the batch is PROCESSING, and a SUCCEEDED row must reference the payroll transfer bound to the item's stable
--        operation identity. A batch can enter a terminal status only with a complete, status-consistent result set.
-- H04    Results now carry their batch (composite foreign key to the item) so progress queries are batch-bounded.
--
-- The migration refuses to run over payroll history that already violates these invariants. It never repairs,
-- deletes, or rewrites payroll or ledger history; see docs/v2/ADR/0004-payroll-integrity-hardening.md.

DO $$
DECLARE
    items_without_results BIGINT;
    non_draft_batches_without_items BIGINT;
    inconsistent_terminal_batches BIGINT;
    unlinked_successes BIGINT;
BEGIN
    SELECT count(*) INTO items_without_results
      FROM payroll_items i
     WHERE NOT EXISTS (SELECT 1 FROM payroll_item_results r WHERE r.item_id = i.id);

    SELECT count(*) INTO non_draft_batches_without_items
      FROM payroll_batches b
     WHERE b.status <> 'DRAFT' AND NOT EXISTS (SELECT 1 FROM payroll_items i WHERE i.batch_id = b.id);

    SELECT count(*) INTO inconsistent_terminal_batches
      FROM (
          SELECT b.status,
                 count(r.item_id) FILTER (WHERE r.status = 'PENDING') AS pending_count,
                 count(r.item_id) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
                 count(r.item_id) FILTER (WHERE r.status = 'FAILED') AS failed_count
            FROM payroll_batches b
            JOIN payroll_items i ON i.batch_id = b.id
            JOIN payroll_item_results r ON r.item_id = i.id
           WHERE b.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED')
           GROUP BY b.id, b.status
      ) terminal
     WHERE terminal.pending_count > 0
        OR (terminal.status = 'COMPLETED' AND terminal.failed_count > 0)
        OR (terminal.status = 'FAILED' AND terminal.succeeded_count > 0)
        OR (terminal.status = 'PARTIALLY_COMPLETED'
            AND (terminal.succeeded_count = 0 OR terminal.failed_count = 0));

    SELECT count(*) INTO unlinked_successes
      FROM payroll_item_results r
      JOIN payroll_items i ON i.id = r.item_id
      JOIN payroll_batches b ON b.id = i.batch_id
     WHERE r.status = 'SUCCEEDED'
       AND NOT EXISTS (
           SELECT 1
             FROM transfers t
             JOIN idempotency_records k ON k.transfer_id = t.id
            WHERE t.id = r.transfer_id
              AND t.source_account_id = b.payer_account_id
              AND t.target_account_id = i.payee_account_id
              AND t.amount_minor = i.amount_minor
              AND t.owner_customer_id = b.created_by_customer_id
              AND t.source_organization_id = b.organization_id
              AND k.owner_customer_id = b.created_by_customer_id
              AND k.operation = 'TRANSFER'
              AND k.idempotency_key = 'payroll-' || b.id || '-' || i.id);

    IF items_without_results > 0 OR non_draft_batches_without_items > 0 OR inconsistent_terminal_batches > 0
       OR unlinked_successes > 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = format('V7 preflight refused to migrate inconsistent payroll history: %s item(s) without a '
                || 'result, %s non-draft batch(es) without items, %s terminal batch(es) whose status does not match '
                || 'their results, %s succeeded result(s) not linked to their payroll transfer.',
                items_without_results, non_draft_batches_without_items, inconsistent_terminal_batches,
                unlinked_successes),
            HINT = 'Do not run flyway repair or edit V1-V7. Follow the owner-controlled procedure in '
                || 'docs/v2/ADR/0004-payroll-integrity-hardening.md.';
    END IF;
END;
$$;

-- 1. Results carry their batch, bound to the item by a composite foreign key.
ALTER TABLE payroll_items
    ADD CONSTRAINT uq_payroll_items_id_batch UNIQUE (id, batch_id);

ALTER TABLE payroll_item_results
    ADD COLUMN batch_id UUID;

-- The V6 transition trigger rejects every UPDATE of a terminal row. It is disabled only for this backfill statement,
-- inside the migration transaction, and the backfill changes no column other than the new batch_id.
ALTER TABLE payroll_item_results DISABLE TRIGGER trg_payroll_item_result_transition;
UPDATE payroll_item_results r
   SET batch_id = i.batch_id
  FROM payroll_items i
 WHERE i.id = r.item_id;
ALTER TABLE payroll_item_results ENABLE TRIGGER trg_payroll_item_result_transition;

ALTER TABLE payroll_item_results
    ALTER COLUMN batch_id SET NOT NULL;
ALTER TABLE payroll_item_results
    DROP CONSTRAINT payroll_item_results_item_id_fkey;
ALTER TABLE payroll_item_results
    ADD CONSTRAINT fk_payroll_item_results_item_batch
    FOREIGN KEY (item_id, batch_id) REFERENCES payroll_items (id, batch_id) ON DELETE CASCADE;

CREATE INDEX ix_payroll_item_results_batch_status
    ON payroll_item_results (batch_id, status);

-- 2. R2-01: item identity and batch membership are immutable; changes are checked against the item's own batch.
CREATE OR REPLACE FUNCTION enforce_payroll_item_intent_mutability() RETURNS TRIGGER AS $$
DECLARE
    owning_batch UUID;
    batch_status VARCHAR(24);
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.id IS DISTINCT FROM OLD.id OR NEW.batch_id IS DISTINCT FROM OLD.batch_id) THEN
        RAISE EXCEPTION 'Payroll item identity and batch membership are immutable.'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        owning_batch := NEW.batch_id;
    ELSE
        owning_batch := OLD.batch_id;
    END IF;

    SELECT status INTO batch_status
      FROM payroll_batches
     WHERE id = owning_batch
       FOR UPDATE;

    IF batch_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Payroll item intent is immutable after submission.'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. R2-02: result rows are born PENDING with their draft item.
CREATE FUNCTION enforce_payroll_item_result_creation() RETURNS TRIGGER AS $$
DECLARE
    item_batch UUID;
    batch_status VARCHAR(24);
BEGIN
    SELECT batch_id INTO item_batch FROM payroll_items WHERE id = NEW.item_id;
    IF item_batch IS NULL THEN
        RAISE EXCEPTION 'A payroll item result requires an existing payroll item.'
            USING ERRCODE = '23503';
    END IF;
    IF NEW.batch_id IS NULL THEN
        NEW.batch_id := item_batch;
    ELSIF NEW.batch_id <> item_batch THEN
        RAISE EXCEPTION 'A payroll item result must belong to the batch of its item.'
            USING ERRCODE = '23503';
    END IF;

    IF NEW.status <> 'PENDING' OR NEW.attempt_count <> 0 OR NEW.transfer_id IS NOT NULL
       OR NEW.error_code IS NOT NULL OR NEW.error_message IS NOT NULL OR NEW.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'A payroll item result must be created PENDING with no attempts.'
            USING ERRCODE = '55000';
    END IF;

    SELECT status INTO batch_status
      FROM payroll_batches
     WHERE id = item_batch
       FOR UPDATE;
    IF batch_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Payroll item results can only be created while their batch is a draft.'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payroll_item_result_creation
    BEFORE INSERT ON payroll_item_results
    FOR EACH ROW EXECUTE FUNCTION enforce_payroll_item_result_creation();

-- 4. R2-02: results change only while the batch executes; success must reference the item's own payroll transfer.
CREATE OR REPLACE FUNCTION enforce_payroll_item_result_transition() RETURNS TRIGGER AS $$
DECLARE
    batch_status VARCHAR(24);
    batch_payer UUID;
    batch_organization UUID;
    batch_creator UUID;
    item_payee UUID;
    item_amount BIGINT;
BEGIN
    IF NEW.item_id IS DISTINCT FROM OLD.item_id OR NEW.batch_id IS DISTINCT FROM OLD.batch_id THEN
        RAISE EXCEPTION 'Payroll item result identity is immutable.'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'A terminal payroll item result is immutable.'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'Payroll item attempt count cannot decrease.'
            USING ERRCODE = '55000';
    END IF;

    SELECT b.status, b.payer_account_id, b.organization_id, b.created_by_customer_id,
           i.payee_account_id, i.amount_minor
      INTO batch_status, batch_payer, batch_organization, batch_creator, item_payee, item_amount
      FROM payroll_items i
      JOIN payroll_batches b ON b.id = i.batch_id
     WHERE i.id = OLD.item_id;
    IF batch_status IS DISTINCT FROM 'PROCESSING' THEN
        RAISE EXCEPTION 'Payroll item results can change only while their batch is processing.'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'SUCCEEDED' AND NOT EXISTS (
        SELECT 1
          FROM transfers t
          JOIN idempotency_records k ON k.transfer_id = t.id
         WHERE t.id = NEW.transfer_id
           AND t.source_account_id = batch_payer
           AND t.target_account_id = item_payee
           AND t.amount_minor = item_amount
           AND t.owner_customer_id = batch_creator
           AND t.source_organization_id = batch_organization
           AND k.owner_customer_id = batch_creator
           AND k.operation = 'TRANSFER'
           AND k.idempotency_key = 'payroll-' || OLD.batch_id || '-' || OLD.item_id) THEN
        RAISE EXCEPTION 'A succeeded payroll result must reference the payroll transfer that pays its item.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 5. R2-02: a result row is retained for as long as its item exists. Deleting a DRAFT item (itself guarded by the
--    item trigger) cascades to its result after the item row is gone, which is the only permitted removal.
CREATE FUNCTION enforce_payroll_item_result_retention() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll_items WHERE id = OLD.item_id) THEN
        RAISE EXCEPTION 'A payroll item result is retained for as long as its item exists.'
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payroll_item_result_retention
    BEFORE DELETE ON payroll_item_results
    FOR EACH ROW EXECUTE FUNCTION enforce_payroll_item_result_retention();

-- 6. Batch transitions: V6 rules unchanged, plus submission requires items and terminal states require a complete,
--    status-consistent result set.
CREATE OR REPLACE FUNCTION enforce_payroll_batch_transition() RETURNS TRIGGER AS $$
DECLARE
    item_count BIGINT;
    result_count BIGINT;
    pending_count BIGINT;
    succeeded_count BIGINT;
    failed_count BIGINT;
BEGIN
    IF OLD.status <> 'DRAFT' AND (
        NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
        NEW.payer_account_id IS DISTINCT FROM OLD.payer_account_id OR
        NEW.name IS DISTINCT FROM OLD.name OR
        NEW.created_by_customer_id IS DISTINCT FROM OLD.created_by_customer_id
    ) THEN
        RAISE EXCEPTION 'Payroll batch identity is immutable after draft.'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'DRAFT' AND (
        NEW.submitted_by_customer_id IS DISTINCT FROM OLD.submitted_by_customer_id OR
        NEW.submitted_at IS DISTINCT FROM OLD.submitted_at OR
        NEW.content_digest IS DISTINCT FROM OLD.content_digest
    ) THEN
        RAISE EXCEPTION 'Submitted payroll intent metadata is immutable.'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status NOT IN ('DRAFT', 'SUBMITTED') AND (
        NEW.approved_by_customer_id IS DISTINCT FROM OLD.approved_by_customer_id OR
        NEW.approved_at IS DISTINCT FROM OLD.approved_at OR
        NEW.approved_content_digest IS DISTINCT FROM OLD.approved_content_digest OR
        NEW.rejected_by_customer_id IS DISTINCT FROM OLD.rejected_by_customer_id OR
        NEW.rejected_at IS DISTINCT FROM OLD.rejected_at OR
        NEW.rejection_reason IS DISTINCT FROM OLD.rejection_reason
    ) THEN
        RAISE EXCEPTION 'Payroll decision metadata is immutable after a decision.'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED') OR
        (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'REJECTED')) OR
        (OLD.status = 'APPROVED' AND NEW.status = 'PROCESSING') OR
        (OLD.status = 'PROCESSING' AND NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Illegal payroll batch transition: % -> %.', OLD.status, NEW.status
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'SUBMITTED' AND NOT EXISTS (SELECT 1 FROM payroll_items WHERE batch_id = NEW.id) THEN
        RAISE EXCEPTION 'A payroll batch must contain at least one item before submission.'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED') THEN
        SELECT count(*) INTO item_count FROM payroll_items WHERE batch_id = NEW.id;
        SELECT count(*),
               count(*) FILTER (WHERE status = 'PENDING'),
               count(*) FILTER (WHERE status = 'SUCCEEDED'),
               count(*) FILTER (WHERE status = 'FAILED')
          INTO result_count, pending_count, succeeded_count, failed_count
          FROM payroll_item_results
         WHERE batch_id = NEW.id;
        IF result_count <> item_count OR pending_count > 0 THEN
            RAISE EXCEPTION 'Payroll batch % cannot finish with % item(s), % result(s), and % pending.',
                NEW.id, item_count, result_count, pending_count
                USING ERRCODE = '23514';
        END IF;
        IF (NEW.status = 'COMPLETED' AND failed_count > 0)
           OR (NEW.status = 'FAILED' AND succeeded_count > 0)
           OR (NEW.status = 'PARTIALLY_COMPLETED' AND (succeeded_count = 0 OR failed_count = 0)) THEN
            RAISE EXCEPTION 'Payroll batch % status % does not match its results (% succeeded, % failed).',
                NEW.id, NEW.status, succeeded_count, failed_count
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON COLUMN payroll_item_results.batch_id IS
    'Batch of the result''s item, enforced by fk_payroll_item_results_item_batch; supports batch-bounded progress.';
COMMENT ON TRIGGER trg_payroll_item_result_creation ON payroll_item_results IS
    'R2-02: results are born PENDING with no attempts while their batch is a DRAFT.';
COMMENT ON TRIGGER trg_payroll_item_result_retention ON payroll_item_results IS
    'R2-02: a result row cannot be removed while its payroll item exists.';
