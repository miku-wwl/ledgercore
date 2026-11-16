# LedgerCore V2 — Round 4 Audit: Architecture and Remediations

Independent final engineering audit by Claude Opus 5.5 (Round 4). Starting point: Round 3 handoff
`ba36f90957fc65f83cc83112a4ba35f6cfc1bf55`. All work is on `main`; no branches, pull requests, worktrees or force
pushes. V1–V11 migrations are byte-for-byte unchanged (checksums pinned in
`MigrationCompatibilityPostgresIntegrationTests`). Findings, reproductions and dispositions are registered in
`ROUND-4-FINDINGS.md`; acceptance is recorded in `E12-ACCEPTANCE.md`; the frozen source in `E12-SOURCE-FREEZE.md`.

All money, people and organizations are synthetic. LedgerCore is an engineering laboratory, not a certified production
bank, and nothing here is a claim of compliance with New Zealand (or any) financial regulation or of completed
penetration testing.

## 1. Architecture as audited

| Layer | Implementation | Round 4 notes |
|---|---|---|
| Bank process | Spring Boot 4.0.9 modular monolith (Spring Modulith 2.0.8), Java 21 | Module boundaries verified by `ModuleArchitectureTests`; `ledger` and `events` depend on no business module |
| Bank database | PostgreSQL 18.7, Flyway V1–V15 | Round 4 adds V12–V15 only (forward-only) |
| Money writes | `LedgerWriter` only; sorted-UUID row locks; projections rebuilt from immutable postings | Unchanged except the new liquidity transfer |
| Durable events | Transactional outbox, `SKIP LOCKED` claims, 30 s leases, 5 attempts with 1/2/4/8 s backoff, DEAD, governed retry | Gains the clearing financial-effect work item and W3C trace context |
| Clearing simulator | Separate Spring Boot process and database; HMAC-SHA256 over `timestamp.body`, ±300 s; 8-attempt callback budget | V3/V5 re-arm callbacks under their original identity |
| Reconciliation | Read-only organization-scoped checks, deduplicated exceptions, recheck-before-resolve | New categories and verified resolution (R4-03, R4-05) |
| Front end | React 19, TanStack Query, generated OpenAPI types | Honest owed-effect labels; Operations console shows owed effects, unresolved payable, reserve block, retries |
| Observability | Micrometer with low-cardinality tags; database gauges on a separate connection; structured span log | Five new gauges, two alert rules; W3C Trace Context end to end |
| Fault lab | Guarded `e6-lab` profile checkpoints; exact-PID kill; independent SQL grading | Seven new clearing/payroll cases plus three recovery scenarios |

## 2. Remediations

| ID | Severity | Root cause | Remediation | Commit(s) | Regression evidence |
|---|---|---|---|---|---|
| R4-01 | P1 | Settlement/refund ran in a Spring `@TransactionalEventListener(AFTER_COMMIT)`; failures in `afterCompletion` are logged and swallowed, the callback was still acknowledged 200 and the simulator stopped redelivering | Durable work item `clearing.callback.accepted:<callbackId>` committed with the callback; idempotent `ClearingFinancialEffectProcessor` (REQUIRES_NEW, payment row lock, settlement precedes refund); after-commit fast path kept for latency, never throws; V12 enqueues work for stranded payments without posting | `94a2978` (instrumentation), `d529208` (fix) | Red tests on `94a2978`; `ClearingEffectDurabilityPostgresIntegrationTests`, `ClearingEffectFastPathFailurePostgresIntegrationTests`; lab A/A*/B/C/D/E before and after; E12-E04/E05 |
| R4-02 | P1 | Reserve shortage was mathematically unreachable (reserve = deposits + payable for every V1–V11 journal type); Round 3 evidence was a mocked exception | V13 `SIMULATION_TREASURY` and a constrained `RESERVE_LIQUIDITY_TRANSFER` journal; `LedgerWriter.transferReserveLiquidity`; `ReserveLiquidityLabRunner` (profile, property, confirmation token, disposable loopback database, audit; no HTTP route); shortage keeps the obligation, records a deduplicated CRITICAL exception, retries then dead-letters | `d529208`, `d185416` | `genuinelyFiniteReserveHoldsTheObligationUntilAuditedReplenishmentAndGovernedRetry`; reserve-shortage lab; E12-E06; E9 alert lab |
| R4-03 | P1 | Reconciliation required a settlement journal only for COMPLETED payments; any exception closed with a note | `CLEARING_FINANCIAL_EFFECT_PENDING` (HIGH while an automatic retry is pending and younger than 60 s, otherwise CRITICAL); severity only rises (audited); resolution re-runs the category's independent check (SQL, authenticated inquiry, completed settlement) or returns 409 | `d529208` | Durability tests (409 while owed, 200 after recheck); E12-F02/F04 |
| R4-04 | P1 | DEAD simulator callbacks could never leave DEAD; inquiry enqueued nothing | Simulator V3: authenticated inquiry re-arms DEAD callbacks with original id, sequence and signed payload, budget reset | `d529208` | Simulator tests; lost-callback lab; E12-E07 |
| R4-05 | P2 | With clearing disabled (default), the receipt-only consumer acknowledged `clearing.instruction.ready` | Detection: `CLEARING_INSTRUCTION_NOT_DISPATCHED` (CRITICAL, recheck-gated). Re-send path DEFERRED_WITH_RISK (owner decision: fail approval closed when clearing is disabled) | `d529208` | `bookedInstructionAcknowledgedWithoutDispatchIsReportedInsteadOfStayingSilentlyPending` |
| R4-06 | P2 | No constraint bound COMPLETED/REJECTED to their journals; FAILED representable | V15 `ck_corporate_payment_terminal_journals` with fail-closed preflight | `292976b` | Negative fixtures (constraint name asserted); `v15RefusesToMigrateACompletedPaymentThatHasNoSettlementJournal` |
| R4-07 | P1 | A bank restored before callbacks the simulator had already delivered could never receive them | Inquiry carries the bank's highest held sequence; simulator V5 re-arms later DELIVERED callbacks, recording `redelivery_count`/`last_rearmed_at` under a guarded trigger | `292976b` | `inquiryFromABankBehindTheDeliveredSequenceRedeliversOnlyTheLaterCallbacks`; E12-G02 restore |

E5–E9 closure work (Round 4): E7 executed negative fixtures (18 refused corruptions with exact constraint names, payroll
history, DEAD detection, concurrent graders with no cross-tenant leak) and a signed callback lifecycle test; E8 executed
role × route × object matrix (12 principals × 44 routes = 528 cells, 243 denied mutations side-effect free) plus CSRF,
maker-checker and revocation tests; E9 W3C Trace Context (bank and simulator, V14/simulator V4), gauges and two
Prometheus rules proven to fire and clear on a genuine shortage; Operations console metrics required by master prompt
§18; E12 accessibility subset. Harness defects found during Round 4 (all fixed, none masked): frozen
`pg_stat_activity` snapshot, payroll grade column, unquoted Playwright reporter list, array-splatted lab parameters
(official attempt 1 superseded, kept in `evidence/round4/e12/attempt-1-6119274/`).

## 3. Invariant mapping

| Invariant | Enforcement | Executed evidence |
|---|---|---|
| INV-01 balanced journals | V2 deferred constraint triggers | Grader `unbalancedJournals` = 0 in every E12 run |
| INV-02 immutable history | V2 append-only triggers; reversals | Negative fixtures |
| INV-03 exact minor units | `Money`, BIGINT, CHECKs | Payroll amount tests; grader `nonPositiveOrForeignCurrencyPostings` |
| INV-04 no unauthorized overdraft | Sorted locks + authoritative balance + non-negative projections | E3 H02; concurrent batch test; grader `negativeNormalBalances` |
| INV-05 idempotency | Scoped keys, fingerprints, V4 lifecycle | Transfer E6 cases (E12-A04); browser lost-response journey |
| INV-06 payroll item atomicity | One transaction per item | E2; payroll crash lab (E12-C03) |
| INV-07 independent approval | Java checks + V6/V8 constraints | E8 matrix; grader `bookedWithoutIndependentApproval` |
| INV-08 immutable approved intent | Digests + V7/V8 triggers | Negative fixtures; approval races |
| INV-09 organization isolation | Membership filtering; 404 concealment | E8 matrix; concurrent reconciliation tenant test |
| INV-10 recoverable partial execution | Durable results, resumable batches | E2; E12-C03 |
| INV-11 projections reconcile | Rebuilt from postings; reconciliation | Grader `projectionMismatches` = 0 |
| INV-12 personal behavior preserved | Regression and upgrade tests | Migration compatibility tests (E12-G01) |
| INV-13 one linked payroll result | V7 triggers and FKs | Payroll integrity tests |
| INV-14 published migrations immutable | Pinned checksums; refusing preflights | `publishedMigrationsKeepTheirRecordedFlywayChecksums` |
| INV-15 Policy B | V8 intents; transfer corporate guard | E8; E12-B03 |
| INV-16 durable owner of every terminal effect | Callback + work item in one transaction | Grader `deliveredWorkMaskingOwedEffect` = 0; E12-E04/E05 |
| INV-17 terminal state bound to journals | V15 | Negative fixtures; grader `completedWithoutSettlement` |
| INV-18 assets equal liabilities; reserve ≥ 0 | Balance triggers; V13; non-negative projection | Grader `balanceSheetDifferenceMinor` = 0; reserve lab |
| INV-19 recheck before resolution | `ReconciliationService.resolveException` | Durability tests; reserve lab; E12-F04 |

## 4. Independent review (no further P0/P1)

Journal balance and immutability, accounting signs, terminal-state legality, callback authentication and replay,
Policy B and self-approval, tenant concealment and outbox concurrency were re-checked against code, constraints and
executed tests (details in `ROUND-4-FINDINGS.md` § Independent review). One P3 hygiene item remains open: the same
HMAC key signs both directions with no direction label in the signature base.

## 5. Residual risks and unexecuted classes

- R4-05 re-send path (DEFERRED_WITH_RISK); recommended owner decision: make approval fail closed while clearing is disabled.
- Message-derived reconciliation categories close by a single audited acknowledgement (no four-eyes rule).
- Hot-account serialization and cross-organization payroll progress scans are measured but not optimized (E3 H02/H04).
- Not executed: lock-timeout faults, disk/storage failure, network partition with both sides alive, clock skew beyond
  the signature window, PostgreSQL server crash, offsite/repeated restore variance, axe-core/WCAG audit and real
  screen-reader review, penetration testing. E10 (Round 3) remains the only PITR evidence (one local run).
- The clearing simulator is a toy: nothing here establishes the behavior or guarantees of any real interbank system.
