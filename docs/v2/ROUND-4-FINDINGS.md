# LedgerCore V2 — Round 4 Findings Register

Independent final audit by Claude Opus 5.5. Starting point `ba36f90957fc65f83cc83112a4ba35f6cfc1bf55` (Round 3 handoff),
`main` only. Severity: **P0** actual funds/ledger compromise; **P1** serious authorization or recovery breach; **P2**
material bounded weakness; **P3** hygiene. Dispositions: CONFIRMED_FIXED, CONFIRMED_OPEN, NOT_REPRODUCED,
FALSE_POSITIVE, DESIGN_DECISION, DEFERRED_WITH_RISK. "Prior evidence" is what Round 3 reported; "Round 4 proof" is what
was executed independently in this round. All money, people and organizations are synthetic; all databases were
disposable (Round 4 cluster `127.0.0.1:55450`, per-run E6 clusters on `127.0.0.1:55437`); the persistent service on
55432 was never contacted.

## Baseline reproduction (A)

| Claim (Round 3) | Round 4 proof | Result |
|---|---|---|
| 95 backend tests pass | `clean verify` of `ba36f90` on fresh `ledgercore_r4_baseline` (PostgreSQL 18.7, Temurin 21.0.13) | **95/95, 0 failures/errors/skips** (`evidence/round4/baseline/BASELINE.md`) |
| 6 simulator tests pass | `clean verify` on fresh `ledgercore_r4_sim_baseline` | **6/6** |
| V1–V11 applied, append-only financial history | `flyway_schema_history` of the baseline database; 13 tables with 35 triggers inventoried | V1–V11 present with the checksums now pinned in `MigrationCompatibilityPostgresIntegrationTests` |
| Every journal balances | independent SQL over the baseline database after all 95 tests (1,263 journals) | 0 unbalanced journals |
| CI for the handoff | run [43628403609](https://github.com/miku-wwl/ledgercore/actions/runs/43628403609) for `ba36f90` | success |

## Register

| ID | Title | Severity | Disposition |
|---|---|---|---|
| R4-01 | Accepted terminal clearing callback could be acknowledged while its settlement/refund was never posted | **P1** | CONFIRMED_FIXED |
| R4-02 | Insufficient-reserve branch unreachable by any legitimate path; Round 3 evidence was a mocked exception | **P1** (evidence/recovery gap) | CONFIRMED_FIXED (finite reserve + real lifecycle) |
| R4-03 | Reconciliation blind to SETTLED/REJECTED outcomes whose local effect is missing while the payment is not COMPLETED; CRITICAL exceptions closable by a note | **P1** | CONFIRMED_FIXED |
| R4-04 | Callbacks that exhausted the simulator's delivery budget during a bank outage could never be redelivered | **P1** | CONFIRMED_FIXED |
| R4-05 | With the clearing integration disabled (the application default) an approved, booked payment's instruction is acknowledged by the fact observer and never sent | P2 | CONFIRMED_FIXED (detection) + DEFERRED_WITH_RISK (re-send path) |
| R4-06 | Terminal payment states not bound to their journals in PostgreSQL (COMPLETED without settlement and FAILED with stranded funds were writable) | P2 | CONFIRMED_FIXED |
| R4-07 | A bank restored to before callbacks the simulator had already delivered could never receive them again | **P1** | CONFIRMED_FIXED |

Detailed entries follow; the independent review of other areas (§ Independent review) records what was checked and
found sound.

---

## R4-01 — Acknowledged callback, lost settlement

- **Source:** `ClearingCallbackService.receive` (records inbox row, external status, audit and the
  `clearing.callback.accepted` outbox event in one transaction, then publishes an in-process event);
  `ClearingOutcomeListener` (`@TransactionalEventListener(AFTER_COMMIT)` → `ClearingFinancialEffectProcessor.apply`,
  `REQUIRES_NEW`); `DurableOutboxConsumer` (receipt-only default consumer of the callback event).
- **Hypothesis:** the settlement is not owned by any durable mechanism. In Spring Framework 7.0.10, AFTER_COMMIT
  listeners run inside `afterCompletion`, and `TransactionSynchronizationUtils.invokeAfterCompletion` catches and logs
  every exception (verified from the `spring-tx-7.0.10` bytecode). Any failure of the effect therefore still returns
  `200 ACCEPTED`; the simulator marks the callback delivered; the durable callback event is consumed by an observer that
  posts nothing. A process crash between the two commits relies entirely on the simulator's 8-attempt redelivery budget.
- **Reproduction (unremediated build, real faults, two processes, two databases):** see
  `evidence/round4/reproductions/README.md`. A — JVM killed at `CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT`, restart after
  the simulator's callback was `DEAD`: payment stayed `PENDING_CLEARING`/`SETTLED`, 0 settlement journals, payable
  1,234 minor units outstanding, the callback event `DELIVERED` with only the `durable-fact-observer-v1` receipt.
  C — **no crash**: the settlement session waiting on the reserve row lock was terminated with `pg_terminate_backend`;
  the bank answered 200, the simulator marked the callback `DELIVERED`, and the settlement never happened, before or
  after a restart. D — JVM killed with the settlement journal written but uncommitted: same stranded state. B and E
  (prompt restart; crash after the settlement commit) recovered, B only through the simulator's redelivery.
- **Red test:** `ClearingEffectFastPathFailurePostgresIntegrationTests` run against a `git archive` export of `94a2978`
  fails (reconciliation finding list empty; variant without that assertion: settlement `expected 1 but was 0` after
  durable dispatch). The Spring log line "TransactionSynchronization.afterCompletion threw exception" with
  `JpaSystemException` is preserved next to the reports.
- **Impact:** the customer was debited at booking, the external system settled, and the bank's books kept a clearing
  payable and an overstated reserve indefinitely, with no automatic recovery and no detection (R4-03). No duplicate
  money movement was possible; the defect is a lost financial effect.
- **Remediation (`d52920840452fabfab808c8801b0f21fdd08a846`):** the callback's outbox event is the durable owner of the
  effect. `ClearingCallbackEffectOutboxHandler` applies it through `ClearingFinancialEffects` using the outbox's lease,
  `SKIP LOCKED` claim, exponential backoff and dead-letter limit; the decision is derived only from the locked payment
  row, so the fast path, the durable worker, a duplicate callback and a governed retry are mutually idempotent. The
  after-commit listener is kept as a fast path that can never lose the work. The handler is registered even when the
  simulator integration is disabled. `V12` enqueues work for terminal outcomes stranded by a pre-Round-4 node (it posts
  nothing). The processor returns an explicit outcome (`APPLIED`, `ALREADY_APPLIED`, `NOT_REQUIRED`).
- **Regression tests:** `ClearingEffectDurabilityPostgresIntegrationTests` (settle and refund windows, competing
  workers + duplicate callback + direct retry → exactly one journal), `ClearingEffectFastPathFailurePostgresIntegrationTests`
  (real terminated session), `MigrationCompatibilityPostgresIntegrationTests.populatedV11WithAStrandedSettlementUpgradesToDurableWorkWithoutPostingMoney`.
- **Post-fix lab:** `evidence/round4/remediation/` — see the post-fix section of `evidence/round4/remediation/README.md`.
- **Disposition:** CONFIRMED_FIXED.

## R4-02 — Insufficient reserve could not occur, so its recovery was never proven

- **Source:** `LedgerWriter.settleClearingPayment` (throws `InsufficientLedgerBalanceException` when the reserve cannot
  cover the payable), `ClearingExceptionRecorder`, Round 3 `ClearingReserveFailureIntegrationTests` (a
  `MockitoSpyBean` throws the exception, then the same callback is replayed by hand).
- **Analysis:** every journal type in V1–V11 keeps `SIMULATION_RESERVE = Σ customer deposits + clearing payable`
  (opening funding and simulated deposits debit the reserve and credit a deposit; transfers, bookings and refunds move
  value between credit-normal liabilities; settlement reduces the payable and the reserve together). A settlement of A
  requires a payable ≥ A, hence a reserve ≥ A. The shortage branch was unreachable; the Round 3 test proved rollback
  under an injected exception, not a reserve lifecycle. Verified empirically on the baseline database after all 95
  tests: reserve 61,400,650 = deposits 61,400,350 + payable 300 across 1,263 journals.
- **Remediation:** `V13` adds a second bank-owned simulated asset, `SIMULATION_TREASURY`, and a balanced
  `RESERVE_LIQUIDITY_TRANSFER` (reserve ↔ treasury) guarded by a deferred constraint trigger (exactly two postings,
  reserve and treasury, opposite sides, equal amounts, request-id reference; no other journal type may touch the
  treasury; one journal per request id). `LedgerWriter.transferReserveLiquidity` cannot overdraw either asset and replays
  a repeated request id. It is reachable only through `ReserveLiquidityLabRunner` — profile `reserve-lab`, explicit
  property, `LEDGERCORE_RESERVE_LAB_CONFIRM=DISPOSABLE_ONLY`, a verified loopback `ledgercore_{e6,e12,r4}_*` database —
  with no HTTP route; every movement is audited (`LAB_RESERVE_LIQUIDITY_TRANSFER`). A failed settlement now keeps the
  obligation, records `CLEARING_RESERVE_INSUFFICIENT` (CRITICAL, deduplicated), retries with backoff and dead-letters;
  recovery is an audited replenishment plus one governed dead-letter retry by an `OPERATIONS` member. If replenishment
  never happens, the payable and the CRITICAL exception remain visible; there is no automatic refund or silent success.
- **Regression tests:** `ClearingEffectDurabilityPostgresIntegrationTests.genuinelyFiniteReserveHoldsTheObligationUntilAuditedReplenishmentAndGovernedRetry`
  (real sweep to 50.00, NZ$80.00 settlement refused 5 times, CRITICAL findings, resolution refused, replenishment,
  two concurrent governed retries → exactly one accepted, one settlement, reserve 70.00, balance sheet balanced) and
  `reserveLiquidityTransfersAreBalancedReplayableAndCannotOverdrawEitherAsset` (database refuses a balanced forged
  treasury posting).
- **Disposition:** CONFIRMED_FIXED.

## R4-03 — Reconciliation blind spot and resolution by note

- **Source:** `ReconciliationQueryService.paymentFinding` requires a settlement journal only when the payment is
  `COMPLETED`; `ReconciliationService.compareClearing` sees bank and simulator agree on `SETTLED`;
  `ReconciliationExceptionService.resolve` closed any exception with a 1–500 character note.
- **Reproduction:** in A, C and D above the `OPERATIONS` reconciliation run returned **zero** findings for the stranded
  payment, before and after restart; the red test shows the same in CI form.
- **Remediation:** `CLEARING_FINANCIAL_EFFECT_PENDING` for `SETTLED` without settlement or `REJECTED` without refund
  while the payment is `PENDING_CLEARING`/`UNKNOWN`, with the terminal callback, the work items' state, attempts, last
  error and age as observed facts. Severity HIGH while an automatic retry is still scheduled and younger than 60 s,
  CRITICAL otherwise; an open exception's severity only rises on later scans (audited). Resolution re-runs the
  independent check for that category and resource: SQL recheck for state-derived categories, an authenticated
  simulator inquiry for cross-system ones, a completed settlement for `CLEARING_RESERVE_INSUFFICIENT`; otherwise HTTP
  409. The recheck method and result are written into the actor-attributed resolution audit.
- **Disposition:** CONFIRMED_FIXED.

## R4-04 — Dead callbacks could not be redelivered

- **Source:** simulator `SimulatorCallbackStore` (8 attempts, then `DEAD`; the V1 trigger forbids leaving `DEAD`; later
  sequences wait behind an undelivered earlier one); `SimulatorOperationService.inquire` (a terminal inquiry returns the
  status and enqueues nothing); bank `ClearingOperationsService.inquire` (returns the response, changes nothing).
- **Reproduction:** `ecf5a9ab-72dd-4dbf-9653-ebd3527c5d6b` — bank down longer than the budget while the operation moved
  to `SETTLED` (sequence 3): sequence 2 `DEAD`, sequence 3 blocked; reconciliation detected `CLEARING_CALLBACK_MISSING`,
  the operator's authenticated inquiry returned `SETTLED/3`, and nothing was ever delivered.
- **Remediation:** simulator `V3` allows `DEAD → PENDING` only with the attempt budget reset to zero and no lease; an
  authenticated inquiry re-arms the operation's dead callbacks with their original ids, sequences and signed payloads.
  No new financial identity is created; the bank processes them in order as usual.
- **Regression tests:** `SimulatorOperationPostgresIntegrationTests.authenticatedInquiryRearmsDeadCallbacksWithTheirOriginalIdentity`,
  `rearmingCannotRewriteCallbackFactsOrKeepTheSpentBudget`.
- **Disposition:** CONFIRMED_FIXED.

## R4-05 — Booked instruction silently acknowledged when the clearing integration is disabled

- **Source:** `ClearingInstructionOutboxHandler` exists only when `ledgercore.clearing.enabled=true` (default `false`);
  otherwise the receipt-only `DurableOutboxConsumer` marks `clearing.instruction.ready` `DELIVERED`; reconciliation
  skipped `READY` instructions.
- **Impact:** bounded — the customer debit sits in the clearing payable and the payment stays `PENDING_CLEARING`; no
  money is created or duplicated, but nothing is ever sent and nothing reported it.
- **Remediation:** `CLEARING_INSTRUCTION_NOT_DISPATCHED` (CRITICAL) for a booked payment whose instruction is still
  `READY` while its dispatch event is `DELIVERED`/`DEAD`/missing; resolution requires a clean recheck. Test:
  `bookedInstructionAcknowledgedWithoutDispatchIsReportedInsteadOfStayingSilentlyPending`.
- **Residual risk:** there is still no governed "re-send instruction" action; an operator must restore the clearing
  integration and re-enqueue the instruction under its original operation id. Making approval fail closed when the
  integration is disabled is the recommended owner decision (it changes the behaviour many existing tests rely on).
- **Disposition:** CONFIRMED_FIXED (detection) + DEFERRED_WITH_RISK (re-send path).

## R4-06 — Terminal payment states not bound to their journals

- **Found by:** the E7 negative-fixture test (`ReconciliationNegativeFixturesPostgresIntegrationTests`): with every
  constraint enabled, `UPDATE corporate_payment_intents SET status = 'COMPLETED'` on a booked `PENDING_CLEARING` payment
  succeeded — no settlement journal, no `SETTLED` outcome. `FAILED` (transition allowed by V8, never used by the
  application) would leave the customer's funds in the clearing payable with no refund.
- **Impact:** requires a direct database write; the application never does it; reconciliation flagged the `COMPLETED`
  case (`CORPORATE_PAYMENT_JOURNAL_MISMATCH`) but not `FAILED`.
- **Remediation:** `V15__bind_terminal_payment_states_to_journals.sql` — preflight that refuses to migrate a contradicting
  history, then `ck_corporate_payment_terminal_journals`: `COMPLETED` ⇔ settlement journal + `SETTLED`; settlement ⇒
  `COMPLETED`; booked `REJECTED` ⇔ refund journal + `REJECTED`; refund ⇒ `REJECTED`; `FAILED` unrepresentable.
- **Regression tests:** the negative-fixture test now asserts the refusal by constraint name for both `COMPLETED` and
  `FAILED`; `MigrationCompatibilityPostgresIntegrationTests.v15RefusesToMigrateACompletedPaymentThatHasNoSettlementJournal`.
- **Disposition:** CONFIRMED_FIXED. Design note: a future failure path must refund the booking before it may end.

## R4-07 — No redelivery after a bank restore

- **Analysis:** found while designing the E12 restore case. E10 restored the bank while the simulator's callbacks were
  still undelivered. If the bank is restored to a point before callbacks the simulator already *delivered*, the simulator
  never sends them again (`DELIVERED` was terminal) and R4-04's re-arm covers only `DEAD` callbacks: the restored bank sees
  `ACCEPTED`, reconciliation reports `CLEARING_CALLBACK_MISSING`, and no path completes the payment.
- **Remediation:** the bank's authenticated inquiry now sends the highest callback sequence it holds
  (`InquiryRequest.afterSequence`); the simulator re-arms every later callback, `DEAD` or `DELIVERED`, with its original
  id, sequence and signed payload. Simulator `V5` adds `redelivery_count`/`last_rearmed_at` and only allows a re-arm that
  records one more redelivery. A reconciliation run's own inquiry therefore heals a restored bank.
- **Regression tests:** `SimulatorOperationPostgresIntegrationTests.inquiryFromABankBehindTheDeliveredSequenceRedeliversOnlyTheLaterCallbacks`;
  E12-G02 restore experiment (`assessment/e12/run-e12-restore.ps1`).
- **Disposition:** CONFIRMED_FIXED.

## Independent review

Checked against code, constraints and executed tests; no further P0/P1 found.

| Area | What was checked | Result |
|---|---|---|
| Journal balance and immutability | V2 deferred balance triggers, append-only triggers; V8/V13 constraint triggers on corporate and liquidity journals | Sound; 0 unbalanced journals in every Round 4 database |
| Accounting signs | booking Dr deposit / Cr payable; settlement Dr payable / Cr reserve; refund Dr payable / Cr deposit; liquidity Dr receiving asset / Cr giving asset | Correct; balance-sheet identity holds in every grade |
| Terminal-state legality | V9 external-status trigger, callback service `legalTransition` | `REJECTED` after `SETTLED` is an `ILLEGAL_CLEARING_TRANSITION` exception with no refund (tested) |
| Callback authentication and replay | HMAC over `timestamp.body`, 300 s skew, constant-time compare, inbox keyed by callback id and (operation, sequence) | Sound; tampering, replay, id reuse, digest mismatch, sequence collision and gaps tested (E8 work) |
| Policy B and self-approval | `TransferService` corporate-source guard; payment/payroll maker-checker in Java and V8/V6 constraints | Sound; executed in the E8 matrix |
| Tenant concealment | `OrganizationAuthorizationService` (404 non-member, 403 missing role, ACTIVE organization required) | Sound; executed in the E8 matrix |
| Outbox concurrency | `claimNext` lease + `SKIP LOCKED`; `markDelivered` lease check | Sound; a lost lease is retried idempotently |
| Shared HMAC key for both directions | same key signs bank→simulator requests and simulator→bank callbacks, no direction label | P3 hygiene: cross-direction replay is rejected by body validation today; a direction label in the signature base is recommended before any real integration |
