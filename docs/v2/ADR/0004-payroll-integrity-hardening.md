# ADR 0004: Payroll integrity hardening and legacy V5 upgrade path

- Status: Accepted in Round 2 (independent audit and remediation of E0–E3).
- Date: 2026-11-14.
- Supersedes: nothing. Refines ADR 0003 (payroll intent and item execution) and ADR 0002 (transfer source owner).
- Evidence: `evidence/round2/` and `docs/v2/ROUND-2-AUDIT.md`.

## Context

Round 2 reproduced four database-level or execution-level defects in the Round 1 baseline
(`b584b9570eee2d2b4c08505b19336b08dfc37a77`):

| ID | Defect | Reproduction |
|---|---|---|
| R2-01 | `enforce_payroll_item_intent_mutability()` checked only `NEW.batch_id`. An item could be moved out of a SUBMITTED, APPROVED, PROCESSING, or terminal batch into a DRAFT batch, rewritten there, and deleted (cascading its result). | `evidence/round2/reproductions/R2-01-*` |
| R2-02 | Result rows were protected from UPDATE only. They could be deleted in any state or inserted already terminal, and `PayrollService.countsFor()` used an INNER JOIN, so a batch with missing results was completed as `COMPLETED`. | `evidence/round2/reproductions/R2-02-*` |
| R2-08 | V5's `UPDATE transfers SET source_owner_customer_id = owner_customer_id` is rejected by V3's `tr_transfers_append_only`. V5 therefore cannot upgrade any V4 database that holds a transfer, i.e. any used V1 deployment. Round 1 only applied V5 to empty databases. | `evidence/round2/reproductions/R2-08-*` |
| R2-09 (E3 H04) | Batch progress scanned the global result table; unrelated organizations' rows increased its work. | `evidence/e3/verdicts/H04-*`, `evidence/round2/performance/` |

Historical migrations V1–V6 are immutable: every deployed database records their Flyway checksums, and Round 2 pins
them in `MigrationCompatibilityPostgresIntegrationTests`.

## Decision

### 1. Forward-only V7 (`V7__harden_payroll_intent_and_result_integrity.sql`)

1. **Preflight, never repair.** Before changing anything, V7 counts items without results, non-draft batches without
   items, terminal batches whose status contradicts their results, and SUCCEEDED results not linked to their payroll
   transfer. Any non-zero count aborts the migration transaction with SQLSTATE 23514. V7 does not delete, insert,
   or rewrite payroll or ledger history.
2. **Results carry their batch.** `payroll_item_results.batch_id` is backfilled from the item, made NOT NULL, and bound
   by `fk_payroll_item_results_item_batch (item_id, batch_id) → payroll_items (id, batch_id)`; `ix_payroll_item_results_batch_status`
   supports batch-bounded progress. The V6 transition trigger is disabled only for the backfill statement, inside the
   migration transaction; the backfill writes no column other than `batch_id`. A BEFORE INSERT trigger derives
   `batch_id` when omitted, so the application's existing insert statement is unchanged.
3. **R2-01.** Item `id` and `batch_id` are immutable. INSERT locks the new batch; UPDATE and DELETE lock and check the
   item's own batch (`OLD.batch_id`), so in-place edits still serialize behind approval.
4. **R2-02.** Result rows are created PENDING with zero attempts while their batch is a DRAFT; they cannot be deleted
   while their item exists (deleting a DRAFT item still cascades); they change only while the batch is PROCESSING;
   identity is immutable; and a SUCCEEDED row must reference a transfer whose payer, payee, amount, actor, and source
   organization match the item and whose receipt is the item's stable key `payroll-{batchId}-{itemId}` under the
   batch creator.
5. **Batch transitions.** All V6 rules are kept verbatim. Additionally, DRAFT → SUBMITTED requires at least one item,
   and PROCESSING → COMPLETED / PARTIALLY_COMPLETED / FAILED requires one result per item, none PENDING, and a status
   consistent with the succeeded/failed counts (the same rule as `PayrollService.finishIfReady`).

### 2. Application changes

- `PayrollService.countsFor()` and `viewFor()` read results through `batch_id`; `finishIfReady()` compares result and
  item counts and fails closed with `PAYROLL_RESULTS_INCOMPLETE` (HTTP 409) instead of completing.
- `approve()` and `reject()` check `PAYROLL_APPROVER` membership before revealing batch state (R2-07): non-members
  receive 404 for every batch state.
- **Creator debit authority (R2-05) — explicit policy.** Payroll transfers keep the E2 identity: the batch creator is
  the transfer actor and idempotency owner, and their `PAYROLL_MAKER` debit authority is evaluated when each item
  executes. If that authority is no longer active (role revoked, membership suspended, organization suspended), the
  item fails closed and terminal with `DEBIT_AUTHORITY_REVOKED`; no transfer is created. Recovery is a new batch with a
  new independent approval, consistent with the existing rule for terminal failures. Previously the same outcome was
  recorded as the misleading `ACCOUNT_NOT_FOUND`. Approval itself is a point-in-time decision: an approver who later
  loses their role does not invalidate an approved batch; execution needs a current `PAYROLL_APPROVER` or `OPERATIONS`
  member. An alternative policy (approval-time authority for already-approved batches) would change the authorization
  principal of payroll transfers and is left as an owner decision.
- **Reserved operation identities (R2-06).** `TransferService.transfer()` (the customer-key path behind
  `POST /api/transfers`) rejects Idempotency-Key values starting with `payroll-` with `INVALID_REQUEST`. Payroll keys
  share the creator's idempotency scope, so a creator could otherwise pre-claim an item's key before execution, either
  linking an unapproved direct transfer to an approved item or blocking the item permanently. The payroll key format,
  scope, and `operation = 'TRANSFER'` are unchanged.

### 3. Legacy V5 upgrade (R2-08)

`LegacyTransferOwnerBackfillCallback` (a Flyway Java callback registered as a Spring bean) acts only for version 5 and
only when `transfers` has rows. In V5's own migration transaction it disables `tr_transfers_append_only`, lets the
unmodified V5 run, and re-enables and verifies the trigger before commit. It refuses to act in autocommit mode. The
`ALTER TABLE` lock blocks concurrent transfer writers for the duration, and a V5 failure rolls the trigger change back
with V5. Fresh installs (no transfers) never invoke it. V5's single UPDATE writes only the new nullable
`source_owner_customer_id`; Round 2 verifies the ledger fingerprint is identical before and after the upgrade.

## Owner-controlled recovery when the V7 preflight refuses

A refusal means payroll history in that database was already altered outside the application (for example through the
R2-01/R2-02 defects). It is a data-integrity incident, not a migration problem.

1. Stop. Do not run `flyway repair`, edit V1–V7, disable V7, or delete rows to make the preflight pass.
2. Take a backup and work on a restored copy. Diagnose read-only, e.g.:
   `SELECT i.batch_id, i.id FROM payroll_items i WHERE NOT EXISTS (SELECT 1 FROM payroll_item_results r WHERE r.item_id = i.id);`
   and compare `transfers`/`idempotency_records` rows whose key starts with `payroll-` against batch items.
3. Reconstruct each affected item's true outcome from the authoritative ledger: a transfer with the item's stable key
   proves payment; no such transfer proves non-payment. Never infer payment from a payroll status alone.
4. Correct the payroll rows under dual control (a second reviewer approves the SQL and its evidence), in one audited
   transaction, preserving the original rows in an incident table. Ledger history is append-only and is never changed;
   financial corrections use new reversing entries.
5. Re-run the preflight queries; migrate only when every count is zero. Record the incident in the audit trail.

Automatic schema compatibility covers every database built from committed migrations through the application. It does
not cover databases altered by hand; those require the procedure above.

## Consequences

- PostgreSQL now enforces INV-08 (approved intent cannot leave its batch) and the result side of INV-06/INV-10
  (one durable, correctly linked result per item) independently of Java.
- Each item execution adds a few primary-key lookups inside its transaction (result trigger); measured overhead is
  reported in `docs/v2/ROUND-2-AUDIT.md`.
- Any future migration that must update terminal result rows has to disable the result triggers explicitly inside its
  own transaction and justify it in an ADR, as V7 does.
- The approval digest is still verified in Java at approval and at the APPROVED → PROCESSING transition. A PostgreSQL
  digest recomputation was considered and not added: with item intent immutable after submission, the database already
  prevents the content from changing, and a second digest implementation would need to stay byte-identical to Java.
