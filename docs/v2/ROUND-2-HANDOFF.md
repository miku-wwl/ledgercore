# LedgerCore V2 Plan B — Round 2 handoff

- Outgoing owner: Claude Opus 5.5 (Round 2: independent audit and remediation of E0–E3).
- Incoming owner: GPT-6 Luna (Round 3: E4–E11).
- Repository: `https://github.com/miku-wwl/ledgercore`, branch `main` only.
- Round 2 verdict: **ACCEPTED_WITH_NONBLOCKING_RISKS** (`ROUND-2-AUDIT.md` §11).
- Round 3 may start. Owner decision OD-1 must be made before E8 is accepted.

## 1. Commits

| Role | SHA |
|---|---|
| Round 1 final (audited baseline) | `b584b9570eee2d2b4c08505b19336b08dfc37a77` |
| Round 2 remediation (V7, Java fixes, V5 callback, new tests) | `0a885c8ee6a7e2409362169614f992a076eff779` |
| Round 2 performance pre-registration (before any measurement) | `1b6ed6881343cc405c33c99475977de289f36e02` |
| Round 2 evidence, audit, handoff, status (this document) | see §6 |
| Final Round 2 head | see §6 |

No branch, worktree, pull request, detached HEAD, force-push, or history rewrite was used. The baseline for the R2-P3
comparison was exported with `git archive` into a scratch directory.

## 2. Files changed since the Round 1 baseline

Production code:

- `backend/src/main/resources/db/migration/V7__harden_payroll_intent_and_result_integrity.sql` — **new migration**.
- `backend/src/main/java/nz/ac/ledgercore/LegacyTransferOwnerBackfillCallback.java` — new (R2-08).
- `backend/src/main/java/nz/ac/ledgercore/FlywayMigrationConfiguration.java` — new (registers the callback bean).
- `backend/src/main/java/nz/ac/ledgercore/payroll/PayrollService.java` — batch-bounded `countsFor`/`viewFor`/`items`;
  fail-closed `finishIfReady`; approver authorization before state checks.
- `backend/src/main/java/nz/ac/ledgercore/payroll/PayrollItemExecutor.java` — `DEBIT_AUTHORITY_REVOKED`; key prefix
  from `TransferService`.
- `backend/src/main/java/nz/ac/ledgercore/payroll/PayrollItemTransferFailure.java` — string failure code.
- `backend/src/main/java/nz/ac/ledgercore/payroll/PayrollExecutionService.java` — uses the failure code.
- `backend/src/main/java/nz/ac/ledgercore/transfers/TransferService.java` — `PAYROLL_IDEMPOTENCY_KEY_PREFIX`, reserved
  on the customer-key path.
- `backend/src/main/resources/application-test.properties` — no default datasource URL (R2-13).

Tests and harnesses:

- `backend/src/test/java/nz/ac/ledgercore/payroll/PayrollIntegrityPostgresIntegrationTests.java` — 19 tests.
- `backend/src/test/java/nz/ac/ledgercore/MigrationCompatibilityPostgresIntegrationTests.java` — 7 tests.
- `backend/src/test/java/nz/ac/ledgercore/performance/Round2PerformanceFollowUpHarness.java` — opt-in harness.

Documentation and evidence: `docs/v2/ADR/0004-payroll-integrity-hardening.md`, `docs/v2/ROUND-2-AUDIT.md`, this file,
updates to `STATUS.md`, `ACCEPTANCE-MATRIX.md`, `EXECUTION-PLAN.md`, `DOMAIN-INVARIANTS.md`, `REPRODUCE.md`,
`EXPERIMENT-INDEX.md`, `ARCHITECTURE.md`, and everything under `evidence/round2/`. V1–V6 and `evidence/e0`–`e3` are
unchanged. The API contract (`frontend/src/api/schema.d.ts`) and the frontend are unchanged.

## 3. Design decisions to keep

All are recorded in `docs/v2/ADR/0004-payroll-integrity-hardening.md`:

1. V7 is forward-only and **refuses, never repairs**, inconsistent payroll history; recovery is an owner procedure.
2. `payroll_item_results.batch_id` exists, is NOT NULL, and is bound to the item by a composite FK; a BEFORE INSERT
   trigger derives it, so result inserts may omit it.
3. Payroll item identity and batch membership are immutable; result rows are born PENDING in a DRAFT batch, retained
   while their item exists, changed only while the batch is PROCESSING, and a SUCCEEDED row must reference the transfer
   and `payroll-{batch}-{item}` receipt that pay its own item.
4. The batch creator's `PAYROLL_MAKER` authority is evaluated at item execution; its loss is a terminal
   `DEBIT_AUTHORITY_REVOKED` result (fail closed, no transfer). Approval is a point-in-time decision.
5. Customer-supplied idempotency keys cannot start with `payroll-`.
6. The V5 legacy-upgrade callback disables only `tr_transfers_append_only`, only within V5's transaction, only when
   transfers exist, and re-verifies it.

## 4. Confirmed defects fixed

R2-01, R2-02, R2-08 (P1); R2-05, R2-06, R2-09 (P2); R2-07, R2-13, R2-16 (P3). R2-11 (misleading E3 interpretation)
is corrected by `evidence/round2/performance/ROUND-1-ANNOTATIONS.md`. Details and reproductions:
`evidence/round2/findings/FINDINGS-REGISTER.md`.

## 5. Tests and performance findings

- Backend: 75 tests (49 Round 1 + 19 payroll integrity + 7 migration compatibility), 0 failures, locally and in CI.
  The integrity suite failed 12 of 19 on the unmodified baseline before remediation.
- Browser: the three Round 1 Chromium tests pass unchanged; no browser-visible behaviour changed.
- R2-P1 (progress query): SUPPORTED — Round 2 SQL reads exactly the target batch's 5,000 results with or without
  50,000 unrelated rows.
- R2-P2 (H01 controlled follow-up): INCONCLUSIVE by its pre-registered rule; history-dependent cost established
  (100 → 100,000 postings: 47.0× SQL, 6.1× service; repeat spread ≤ 1.29×).
- R2-P3 (payroll within-batch growth): growth reproduced on the baseline and attributed (~89%) to the claim walking
  completed items plus payer-history aggregation; V7 trigger cost ~0.04 ms per item (exploratory).
- H02/H03 latency above ten clients is pool queueing (Hikari 10), not PostgreSQL lock contention.

## 6. CI verification

| Commit | Run | Jobs | Notes |
|---|---|---|---|
| `b584b95` (baseline) | [43553101864](https://github.com/miku-wwl/ledgercore/actions/runs/43553101864) | full-stack acceptance success; OSV success | Round 1 final |
| `0a885c8` (remediation) | [43560923022](https://github.com/miku-wwl/ledgercore/actions/runs/43560923022) | full-stack acceptance success; OSV success | 75 tests, 0 failures; V4/V5/V6 → V7 upgrades on Linux PostgreSQL 18; 3 Playwright passed; npm audit 2 low / 4 moderate |
| `1b6ed68` (pre-registration) | [43561592007](https://github.com/miku-wwl/ledgercore/actions/runs/43561592007) | full-stack acceptance success; OSV success | harness compiled; no production change |
| Evidence/audit commit | recorded in the CI follow-up commit | | |

A pending or failed run is not a pass. The final head's own run is reported in the Round 2 final report.

## 7. Residual risks and unexecuted checks

Residual risks (owned, not hidden): OD-1 corporate single-authorization and admin self-provisioning (R2-03, R2-15);
history-dependent ledger cost (R2-10); within-batch payroll slowdown (R2-12); unrecorded execution releaser (R2-14);
no cancel/skip for stuck items, unbounded batches, unpaginated items (R2-17); no corporate history/reconciliation
(R2-18); V4 databases with transfers must upgrade with a build containing the V5 callback; the stale Round 1
`ledgercore_test` must be discarded, never repaired.

Not executed in Round 2:

- Crash/kill at points other than Round 1's single in-flight kill (for example between a failed item transaction and
  `markItemFailed`, or during V7 itself).
- Any H02/H03 re-run with a pool at least as large as concurrency; network/HTTP-level or remote-database benchmarks;
  production-scale or Linux-host performance.
- Isolation of the R2-P3 early-phase slowdown after bulk loading.
- Backup/restore of a V7 database with trigger and constraint verification.
- A local `npm audit` (the CI step is the record).
- An OD-1 decision (owner).

## 8. Recommended E4–E11 boundaries

- **E4 durable events.** Write events with a transactional outbox in the same transaction as the ledger posting and
  payroll result; events describe facts that already committed and never cause postings. Consumers must be idempotent
  on the event ID. Emit payroll lifecycle events including who started and resumed execution (closes R2-14).
- **E5 clearing simulator.** Model external outcomes explicitly as ACCEPTED, SETTLED, REJECTED, and UNKNOWN. An UNKNOWN
  outcome is never retried under a new operation identity; it is resolved by reconciliation. Keep external settlement
  separate from internal postings (for example through a clearing/suspense ledger account with balanced entries), and
  do not overload `payroll_item_results.status` with clearing states.
- **E6 fault injection.** Add kill points between every step of the item transaction, between rollback and
  `markItemFailed`, and inside migrations; after each, check INV-01–INV-14 directly in PostgreSQL.
- **E7 reconciliation.** Recompute balances from postings independently of projections; verify item ↔ transfer ↔
  receipt linkage (V7's rule) and extend reconciliation to corporate accounts (R2-18). Gate any incremental balance
  projection on: ledger balance verification, concurrent and hot-account transfers, cross-transfer deadlock tests,
  insufficient funds, rollback, idempotency replay, independent reconciliation, and crash recovery — with its own
  pre-registered experiment.
- **E8 security.** Decide and implement OD-1 (`ROUND-2-AUDIT.md` §8); govern entitlements (no self-grant of payment
  roles, or dual administration; no silent re-activation of suspended members).
- **E9 observability.** Measure connection-pool wait, row-lock wait, and execution separately; never infer database
  contention from end-to-end latency above the pool size (H02 lesson). Sample with a pooled monitoring connection.
- **E10 recovery.** Backup/restore drills must verify that V7 triggers, constraint triggers, and composite FKs survive
  and validate; run V7's read-only preflight (`evidence/round2/migrations/v7-preflight-readonly.sql`) after restore.
- **E11 operations console.** Dual-controlled cancel/skip of stuck items, batch size limits, paginated item views, and
  the payroll claim improvements from R2-P3 (each pre-registered).

## 9. What Round 3 must preserve

1. Do not weaken the financial invariants INV-01–INV-14 or remove any PostgreSQL trigger, constraint, or composite FK,
   including V7's; add forward-only migrations only, never edit V1–V7.
2. Keep the core ledger inside its posting transaction: postings, projection rebuild, transfer row, receipt, and
   payroll result commit together. No asynchronous or event-driven posting.
3. Preserve payroll operation identities exactly: key `payroll-{batchId}-{itemId}`, owner = batch creator,
   `operation = 'TRANSFER'`, and the reserved prefix on the customer-key path.
4. Design E4 events so that no consumer, replay, or retry can create a second financial posting.
5. Make E5 clearing distinguish accepted, settled, rejected, and unknown outcomes.
6. Preserve all Round 1 and Round 2 evidence unchanged; put new experiments in new directories with pre-registration
   committed before measurement, and record corrections as annotations.
7. Do not start the V2 PDF; it belongs to Round 4.
8. Keep tests on explicitly configured disposable databases; the 75-test suite must stay green.
