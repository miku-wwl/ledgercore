# ADR 0003: Durable payroll intent and item execution

- Status: Accepted for Round 1 implementation. Round 2 audit: decisions 3 and 5 were enforced only partly in PostgreSQL (R2-01, R2-02); completed by V7 and refined by ADR 0004.
- Date: 2026-11-14.
- Scope: E2 corporate payroll.

## Context

Payroll is a multi-step business operation over the existing double-entry transfer service. A batch may contain hundreds of payments, and a process can fail after some item transactions commit. The design must preserve Maker/Checker separation, protect the approved contents, and allow a retry to find only unfinished work.

## Decision

1. Store batch lifecycle and approved content digest in `payroll_batches`. Store immutable submitted intent in `payroll_items` and mutable execution state in `payroll_item_results`.
2. Canonically serialize batch identity and ordered item identity/ownership/amount fields, hash with SHA-256 at submission, and store the same digest as the independent approval authorization.
3. Serialize mutation, submission, and approval on the batch row. PostgreSQL triggers acquire the same parent-row lock for item-intent writes and reject intent changes after submission. Approval recomputes the canonical digest before recording the approver.
4. Run each payroll item in a separate transaction. Claim a pending result with `FOR UPDATE ... SKIP LOCKED`; call the existing `TransferService` and update the durable payroll result through a callback inside that same transaction. Transfer postings, transfer receipt, idempotency record, and `SUCCEEDED` result therefore commit or roll back together.
5. Use the stable key `payroll-{batchId}-{itemId}` under the batch creator's identity. Terminal item results are immutable. Permanent business failures such as insufficient funds are recorded as terminal results after the failed transfer transaction rolls back; unclassified persistence failures leave the item pending.
6. Keep batch execution orchestration non-transactional. A worker interruption leaves earlier committed results intact and an unfinished item pending. A subsequent authorized execution reclaims pending rows and reconciles the final batch state.
7. Require `PAYROLL_APPROVER` for approval and execution, with `OPERATIONS` also allowed to execute. The submitting Maker cannot approve their own batch.

## Consequences

- A payroll batch is a durable orchestration boundary rather than one long database transaction.
- The result table is the durable checkpoint. No process-local cursor or in-memory progress is authoritative.
- PostgreSQL locks and triggers are part of the financial correctness mechanism and must be included in migration review and backup/restore testing.
- An item failure after earlier successes yields partial completion; the system does not silently retry a terminal business failure or reverse committed employee payments.
- Item intent and result rows add schema and query complexity, and transfer service callback coupling deserves independent review.
- Execution is synchronous and bounded by request limits in this round. A durable external queue, settlement reconciliation, and network delivery are deferred to later authorized stages.

## Evidence

- `evidence/e2/VALIDATION.md` records the 500-item PostgreSQL scenario, lock/mutation tests, injected rollback, insufficient funds, browser workflow, and actual Java process termination/restart experiment.
