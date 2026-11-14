# LedgerCore V2 — Round 2 independent engineering audit (E0–E3)

- Auditor and remediation owner: Claude Opus 5.5 (Round 2 of Plan B). Next owner: GPT-6 Luna (Round 3, E4–E11).
- Audited baseline: `b584b9570eee2d2b4c08505b19336b08dfc37a77` (Round 1 final).
- Round 2 commits on `main`: `0a885c8` (remediation), `1b6ed68` (performance pre-registration), and the evidence and
  documentation commits recorded in `ROUND-2-HANDOFF.md`.
- Evidence: `evidence/round2/` (index in `evidence/round2/README.md`); every finding in
  `evidence/round2/findings/FINDINGS-REGISTER.md`.
- Scope: E0–E3 only. No E4–E12 implementation and no V2 PDF work was started.

## 1. Executive technical assessment

E0–E3 are suitable for continued development **after** the Round 2 remediation, with explicitly recorded,
non-blocking risks.

What survived independent scrutiny:

- The double-entry ledger core: balanced entries, append-only history, sorted ledger-row locks with an authoritative
  balance check, and transactionally rebuilt projections held under every Round 2 test, reproduction, and measurement.
  No unbalanced entry, negative balance, projection mismatch, or duplicate effective payment was observed.
- Payroll's per-item transaction boundary: transfer, receipt, and payroll result commit or roll back together;
  `FOR UPDATE … SKIP LOCKED` claims are disjoint; overlapping executors pay each item once; two batches sharing one
  payer cannot overdraw it.
- Round 1's measurements are authentic: every headline number re-derives from the preserved raw files.

What did not survive:

- **Two database-level payroll integrity guarantees were incomplete (P1).** Items could be moved out of approved,
  processing, and completed batches (R2-01), and payroll results could be deleted or forged, after which a batch with
  unpaid items completed as `COMPLETED` (R2-02). Round 1 described both as enforced by PostgreSQL; they were enforced
  only for in-place edits and for UPDATE.
- **The V1 → V2 upgrade path was broken (P1).** The committed V5 cannot migrate any V4 database that holds a transfer,
  because V3's append-only trigger rejects V5's backfill (R2-08). Round 1 only applied V5 to empty databases.
- Several interpretations in E3 over-attributed latency to PostgreSQL and left H01's inconclusiveness unexplained.

All three P1 defects are fixed with forward-only, tested changes (V7, a scoped Flyway callback, and Java fail-closed
checks); V1–V6 are byte-identical. The largest remaining item is a **policy**, not a defect: under the established
Policy A, one `PAYROLL_MAKER` can move corporate funds without approval (R2-03). That is recorded as owner decision
OD-1, required before E8 acceptance and before any claim that corporate payments are dual-controlled.

## 2. Original baseline

| Item | Value |
|---|---|
| Round 1 final SHA | `b584b9570eee2d2b4c08505b19336b08dfc37a77` |
| Round 1 final CI | run [43553101864](https://github.com/miku-wwl/ledgercore/actions/runs/43553101864): full-stack acceptance and OSV scan succeeded |
| Round 1 test count | 49 backend tests; 3 Chromium E2E tests |
| Independent reproduction of the count | fresh database `ledgercore_r2_baseline`: `mvn clean verify` exit 0, **49 tests, 0 failures, 0 errors, 0 skipped** |
| E3 verdicts | H01 INCONCLUSIVE, H02 SUPPORTED, H03 MIXED, H04 SUPPORTED |
| Starting repository state | branch `main`; clean tree; local HEAD = `origin/main` = baseline; one worktree; no other branches |
| Dependency audit | npm: 6 findings (2 low, 4 moderate, 0 high/critical); OSV scan job passing |

Round 2 used a new disposable PostgreSQL 18.7 cluster (`127.0.0.1:55436`, `work/pg-round2-audit`). The Round 1
cluster (55434) was accessed read-only for the R2-04 investigation; the persistent service (55432) was never contacted.

## 3. Independent findings

Full records (hypothesis, reproduction, expected/observed, root cause, remediation, tests, verification) are in
`evidence/round2/findings/FINDINGS-REGISTER.md`.

| ID | Finding | Severity | Reproduced? | Fix | Disposition |
|---|---|---|---|---|---|
| R2-01 | Payroll item can be moved out of a non-draft batch (V6 trigger checks only `NEW.batch_id`) | P1 | Yes — SQL and Java, on baseline | V7: item identity/batch immutable; checks the item's own batch; composite FK | CONFIRMED_FIXED |
| R2-02 | Results deletable/born terminal; missing results completed the batch | P1 | Yes — SQL with a real transfer; Java | V7 result lifecycle + terminal-batch checks; `finishIfReady` fails closed | CONFIRMED_FIXED |
| R2-03 | `PAYROLL_MAKER` debits corporate accounts with no approval | P1-level policy risk | Yes — behaves exactly per ADR 0002 | None (policy) | DESIGN_DECISION + OD-1 |
| R2-04 | V5 checksum `2096504864` in Round 1 `ledgercore_test` | none | Root cause proven: pre-commit V5 draft | Checksum pin test; never repair that database | FALSE_POSITIVE |
| R2-05 | Creator authority loss recorded as `ACCOUNT_NOT_FOUND`; policy implicit | P2 | Yes | Explicit `DEBIT_AUTHORITY_REVOKED`; ADR 0004 policy | CONFIRMED_FIXED |
| R2-06 | Public transfer API can claim payroll operation identities | P2 | Yes | `payroll-` keys reserved on the customer-key path | CONFIRMED_FIXED |
| R2-07 | Approve/reject reveal batch state before authorization | P3 | Yes | Role check first | CONFIRMED_FIXED |
| R2-08 | Committed V5 cannot upgrade a populated V4 database | P1 | Yes — found by a new upgrade test; SQL | V5-scoped Flyway callback; V5 unchanged | CONFIRMED_FIXED |
| R2-09 | Progress query scans the global result table (H04) | P2 | Yes — R2-P1 fixture | V7 `batch_id` + index; batch-bounded SQL | CONFIRMED_FIXED |
| R2-10 | H01 spread caused by stale statistics and buffer state | P2 | Yes — from preserved plans | New controlled follow-up R2-P2 | Analysed; INCONCLUSIVE by rule (§7) |
| R2-11 | H02/H03 latency and lock data over-attributed to PostgreSQL | P2 | Yes — from raw CSVs | Annotation | CONFIRMED_FIXED (annotation) |
| R2-12 | Per-item payroll latency grows within a batch | P2 | Yes — R1 raw samples and R2-P3 | None in Round 2 | DEFERRED_WITH_RISK |
| R2-13 | Test profile defaulted to the persistent service on 55432 | P3 | Yes — by inspection | No default URL | CONFIRMED_FIXED |
| R2-14 | Releaser of payroll execution not recorded | P2 | Yes — by inspection | None | DEFERRED_WITH_RISK (E4/E9) |
| R2-15 | Admin self-provisioning, membership re-activation, e-mail probing | P2/P3 | Yes — E1 tests do it | None | DESIGN_DECISION (OD-1) |
| R2-16 | Local E2E depends on Windows drive-letter casing | P3 | Yes | Procedure documented | CONFIRMED_FIXED |
| R2-17 | Unbounded batch size; unpaginated item listing | P3 | By inspection | None | DEFERRED_WITH_RISK |
| R2-18 | No history/reconciliation endpoints for corporate accounts | P3 | By inspection | None | DEFERRED_WITH_RISK |

Reproduce-before-fix evidence: the new 19-test payroll integrity suite was run against the **unmodified baseline**
first — 12 failures, every one the defect itself (`evidence/round2/regression/red-baseline-integrity-suite.txt`) —
then against the remediation: 0 failures.

### Actual impact of the P1 defects

- R2-01 + R2-02 required a database client able to write payroll tables directly (a defect, a privileged operator, or
  a compromised credential). With that access, approved items could be removed from execution, completed history
  could be rewritten or erased, and a batch could be marked `COMPLETED` with unpaid employees. Chained with a second
  approval, an already-paid item moved to a new batch would have been paid again under a new operation key. A
  read-only run of V7's preflight against the three Round 1 V6 databases (2,517, 503, and 121,500 payroll items,
  including the 66,000 executed E3 results with real transfers) found zero violations
  (`evidence/round2/migrations/v7-preflight-round1-databases-output.txt`): no such chain occurred in Round 1 data,
  and V7's linkage rule matches every application-produced result.
- R2-08 blocked every real V1 deployment (any database with at least one transfer) from upgrading to V2. The failure
  was safe — Flyway rolls the transaction back — but total.

## 4. Financial invariants

The maintained matrix is `docs/v2/DOMAIN-INVARIANTS.md` (INV-01–INV-14). Summary of how each is enforced and verified:

| Invariant | Java | PostgreSQL | Tests (real PostgreSQL) | Round 2 independent check |
|---|---|---|---|---|
| INV-01 balanced entries | `JournalEntry` validation | deferred `ct_journal_entries_balanced`, `ct_postings_keep_entry_balanced` | `LedgerPostgresIntegrationTests` | 0 unbalanced entries in every run; R2-08 debits = credits after upgrade |
| INV-02 append-only history | — | `tr_journal_entries_append_only`, `tr_postings_append_only`, `tr_transfers_append_only` | ledger and transfer suites | Verified; relaxed only inside V5's transaction and re-verified (R2-08) |
| INV-03 exact minor units | `Money`, `addExact` | BIGINT, positive CHECKs | `MoneyTests`, `payrollRejectsZeroNonCanonicalAndOverflowingAmounts` | Overflow and non-canonical amounts rejected |
| INV-04 no unauthorized overdraft | sorted `FOR UPDATE`, ledger balance under lock | non-negative projection CHECK | concurrent transfer tests, `concurrentBatchesSharingOnePayerNeverOverdrawIt` | 60 × NZ$20 against NZ$1,000: exactly 50 paid, balance 0 |
| INV-05 idempotency | actor-scoped keys; reserved `payroll-` namespace | PK, success-only receipts, deferred success check | transfer suite, R2-06 test | Verified |
| INV-06 atomic payroll item | `transferAndRecord` callback in one transaction | V7: SUCCEEDED must reference its own payroll transfer and receipt | rollback test, linkage test | Verified |
| INV-07 independent approval | role + identity check | `ck_payroll_batches_maker_checker` | E2E self-approval 403; Java | Verified for payroll; corporate transfers are single-authorization by policy (R2-03) |
| INV-08 immutable approved intent | digest at approve and start | V7 item triggers + composite FK | R2-01 tests | **Was incomplete; fixed** |
| INV-09 tenant isolation | membership checks | composite owner FKs | E1 HTTP test, cross-org progress test | Non-members always 404 (R2-07 fixed) |
| INV-10 recoverable partial execution | non-transactional orchestrator; durable results | V7 result lifecycle | rollback, retry, overlap, unclassified-failure tests | Verified for tested interleavings |
| INV-11 projection reconciliation | projection rebuilt from postings in the posting transaction | — | projection checks in suites and harnesses | 0 mismatches |
| INV-12 V1 compatibility | — | — | full suite, E2E | Verified, including populated upgrades |
| INV-13 complete, linked results (new) | `finishIfReady` fail-closed | V7 creation/retention/transition/terminal checks | R2-02 tests | New |
| INV-14 immutable, upgradeable migrations (new) | V5 callback | V7 preflight | `MigrationCompatibilityPostgresIntegrationTests` | New |

## 5. Transaction and concurrency review

- **Payroll approval.** `approve()` locks the batch row (`FOR UPDATE`), checks `PAYROLL_APPROVER` membership (Round 2
  moved this first), requires a submitted batch and a different maker, recomputes the canonical SHA-256 digest from the
  items, and updates guarded by `status = 'SUBMITTED' AND content_digest = ?`. Item inserts/updates/deletes take the
  same batch-row lock in their trigger, so in-place edits serialize behind approval (test
  `itemChangesRacingApprovalCannotAlterTheApprovedContent`); after V7 an item can no longer leave its batch at all.
  The digest is re-verified at APPROVED → PROCESSING.
- **Item claiming.** `executeNext` is one transaction: it selects the first PENDING result of the batch in
  `sequence_no` order with `FOR UPDATE OF r SKIP LOCKED`, so concurrent workers claim disjoint items; `executeOne`
  locks a specific item without skipping (duplicate-worker test: one executes, the other returns the same transfer).
  The claim's cost grows with completed items until vacuum (R2-12).
- **Ledger posting.** `TransferService.transferAndRecord` (READ COMMITTED): authorization (personal owner or active
  `PAYROLL_MAKER`), idempotency reservation (`INSERT … ON CONFLICT DO NOTHING`; a concurrent same-key request waits on
  the unique index), ledger rows locked in UUID order, balance computed from postings under the lock, journal entry and
  postings, projections rebuilt from postings, transfer row, receipt finalized to SUCCEEDED, then the payroll callback.
  Deferred constraint triggers check balance, transfer-to-journal agreement, and receipt success at commit.
- **Idempotency receipts.** Only successes persist; any failure rolls back the reservation. A replay re-checks
  authorization and the request fingerprint and returns the stored receipt; in payroll the callback then links the
  replayed transfer (V7 verifies it is the item's own).
- **Payroll result persistence.** The SUCCEEDED update runs inside the transfer transaction through the callback, so
  postings, transfer, receipt, and result are atomic (injected-failure test). Since V7 PostgreSQL rejects a SUCCEEDED
  result whose transfer or receipt does not match the item.
- **Failure classification.** Permanent: `INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND`, `ACCOUNT_NOT_OPEN`,
  `INVALID_REQUEST`, and (Round 2) `DEBIT_AUTHORITY_REVOKED`. The item transaction rolls back, then
  `markItemFailed` records the terminal result in a new transaction guarded by `status = 'PENDING'`, so a concurrent
  success wins and a double failure is harmless. Unclassified (`IDEMPOTENCY_KEY_REUSED`, `UNAUTHENTICATED`, any
  `DataAccessException`): the item stays PENDING and `execute()` stops with 409/500 (test
  `unclassifiedTransferFailureLeavesTheItemPendingAndStopsExecution`). Between a failed transfer and
  `markItemFailed`, a crash leaves the item PENDING and a later run retries the transfer, which can then succeed if
  funds arrived; that is the documented recovery semantics.
- **Batch completion.** `finishIfReady` locks the batch, counts items and results by batch, refuses to complete when
  they differ (`PAYROLL_RESULTS_INCOMPLETE`), stays PROCESSING while any result is PENDING, and otherwise chooses
  COMPLETED / FAILED / PARTIALLY_COMPLETED from the counts. V7 enforces the same rule in PostgreSQL, so a direct UPDATE
  cannot complete an incomplete batch.
- **Crash coverage.** Rollback before commit, committed items with later items pending, retries over succeeded items,
  overlapping executors, and unclassified failures are tested. Round 1's process-kill experiment covers one crash point;
  other kill points (for example between the failed item transaction and `markItemFailed`) are reasoned, not observed.

## 6. Database migration review

- **R2-04 (stale V5 checksum).** Read-only forensics on the Round 1 cluster: V5 was applied to `ledgercore_test` at
  13:21:16 with checksum `2096504864`; a fresh `ledgercore_e1_test` received the final V5 (`-1572619169`) at 13:28:40;
  the E1 commit followed at 13:49:58, and its V5 blob is unchanged through the baseline. An independent implementation
  of Flyway 11.14.1's checksum (confirmed from the bytecode) reproduces all six recorded checksums; line endings cannot
  alter them. A schema-only dump shows the stale database holds an earlier V5 draft without the transfer source-owner
  columns and constraints. Conclusion: a pre-commit development database, not a compatibility defect. It must be
  discarded; `flyway repair` would stamp the final checksum over a schema that lacks the source-owner integrity rules.
- **R2-08 (V5 on populated V4).** Fixed by `LegacyTransferOwnerBackfillCallback` (ADR 0004): V5 only, transfers
  present only, inside V5's transaction only, trigger re-enabled and verified before commit. Verified by SQL
  reproduction and by an upgrade test whose ledger fingerprint is identical before and after.
- **V7 (new).** Forward-only: preflight that refuses inconsistent history; `payroll_item_results.batch_id`
  (backfilled, NOT NULL, composite FK, index); item identity immutability; result creation/retention/transition
  triggers; terminal-batch consistency. The backfill disables the V6 result trigger only for its own statement inside
  the migration transaction.
- **Compatibility evidence** (`MigrationCompatibilityPostgresIntegrationTests`, isolated schemas, run locally and in
  CI on Linux PostgreSQL 18): V1–V6 checksums pinned; fresh → V7; populated V4 → V7 (personal ledger history
  unchanged); populated V5 → V7 (corporate transfer unchanged); populated V6 → V7 (every result row identical except the
  new `batch_id`, which matches its item; R2-01/R2-02 enforced on migrated rows; the application's unchanged insert
  still works); corrupted V6 → V7 refused with the schema left at V6. No constraint is left `NOT VALID`.
- **Rollback/recovery.** PostgreSQL migrations are transactional; a failed V5 or V7 leaves the previous version intact.
  There is no down-migration; recovery from a refused preflight is the owner procedure in ADR 0004. Owners with V4
  databases holding transfers must upgrade with a build that includes the callback.

## 7. Performance review

Round 1 results and Round 2 measurements are kept separate. Round 2 experiments were pre-registered in
`evidence/round2/performance/PRE-REGISTRATION.md` (commit `1b6ed68`) before measurement; Round 1 corrections are
annotations (`evidence/round2/performance/ROUND-1-ANNOTATIONS.md`). All values are local synthetic observations.

| Topic | Round 1 result (unchanged) | Round 2 finding | Status |
|---|---|---|---|
| H01 history growth | INCONCLUSIVE; 126.4× SQL, 20.1× service; 2.3× repeat spread | Spread caused by a plan flip from stale statistics (estimates of 1 and 44 rows for 100,000 → 100,751 vs 3,251 buffers) and by buffer state. **R2-P2** (fresh statistics + checkpoint): repeat spread 1.29× SQL / 1.17× service; 100 → 100,000 ratio **47.0× SQL, 6.1× service**; buffers within 1.1% across repeats. INCONCLUSIVE by its own rule because repeat 1 legitimately chose a sequential scan (same buffers and time). | History-dependent cost established (~0.47 µs per posting per aggregate, two aggregates per transfer); write path unchanged |
| H02 transfer concurrency | SUPPORTED | Database concurrency was capped at 10 by Hikari (`pg_max_lock_wait_sessions` = 9 everywhere ≥ 10 clients); the hot-payer throughput decline happened at constant database concurrency; lock-wait sample counts scale with run duration; the sampler opened a new connection per sample; percentiles above pool size describe the pool. | Interpretation corrected; no re-run needed (decision unchanged) |
| H03 payroll | MIXED; 66,000 items | 66,000 confirmed. It is a hot-row, executor-level measurement that bypasses the orchestrator and measured no failures. Per-item latency grows within 5,000-item batches (7.8 → 21.4 ms by decile). **R2-P3**: reproduced on the baseline build (7.9 → 23.0 ms); ~89% attributed to the claim walking completed items (results are never HOT-updated, so dead index entries persist until vacuum) plus the payer's full-history aggregates. P3.1 REJECTED (build B 1.71×; 500-item batches grew in both builds — an unexplained early-phase effect after bulk loading), P3.2 SUPPORTED, P3.3 SUPPORTED but uninformative. Exploratory: V7's added trigger lookups cost **0.038 ms per item**. | DEFERRED_WITH_RISK; remedies listed for Round 3 |
| H04 progress scan | SUPPORTED (71,500 → 121,500 rows scanned) | **Fixed (R2-09).** **R2-P1 SUPPORTED**: Round 2 SQL reads exactly 5,000 result rows with and without 50,000 unrelated rows (counts: index-only scan, 14 → 15 buffers; view: 289 → 287 buffers); Round 1 SQL scans 55,000; all counts agree across 11 batches. Server-side p50 at S2: counts 1.16 ms vs 7.36–7.75 ms. | CONFIRMED_FIXED |

Decision: no ledger write-path optimization in Round 2. A transactionally maintained incremental balance would remove
the history dependence but changes the overdraft check; it needs its own pre-registered experiment and the gates in
`ROUND-2-HANDOFF.md` §8.

## 8. Security and authorization review

**Actual corporate policy (Policy A, ADR 0002 §5).** Roles are additive grants on an active membership in an active
organization:

| Action | ORG_ADMIN | PAYROLL_MAKER | PAYROLL_APPROVER | OPERATIONS | FINANCE_VIEWER | Non-member |
|---|---|---|---|---|---|---|
| View organization, corporate accounts, payroll batches/items | yes | yes | yes | yes | yes | 404 |
| Open corporate account; list/add members; grant roles (including to self) | yes | 403 | 403 | 403 | 403 | 404 |
| Direct corporate transfer (`POST /api/transfers`) | 404 | **yes, no approval** | 404 | 404 | 404 | 404 |
| Create, fill, submit own payroll draft | 403 | yes | 403 | 403 | 403 | 404 |
| Approve or reject a submitted batch | 403 | 403 | yes, never own batch | 403 | 403 | 404 |
| Execute an approved batch | 403 | 403 | yes | yes | 403 | 404 |

Corporate debit entry points are exactly the two `TransferService` paths; both apply the same check. Suspended
memberships and organizations have no rights. Round 2 found no bypass of Policy A.

**Owner decision OD-1 (required before E8 acceptance and before any dual-control claim).** Under Policy A, payroll
Maker/Checker protects payroll-batch integrity but does not stop a single `PAYROLL_MAKER` (the E1 acceptance test
transfers corporate funds to the maker's own account), and a sole `ORG_ADMIN` can grant themselves `PAYROLL_MAKER` and
`PAYROLL_APPROVER` (R2-15). Options:

1. Keep Policy A, but separate direct-debit authority from `PAYROLL_MAKER` (a distinct role) and document that
   corporate transfers are single-authorization.
2. Adopt Policy B: every corporate outgoing payment requires independent approval (a payment-approval workflow
   reusing the payroll intent/digest pattern), and remove direct corporate debit from `POST /api/transfers`.
3. In either case, govern entitlements: forbid self-grants of payment roles, or require a second administrator, and
   make `addMember` refuse to silently re-activate a suspended membership.

Round 2 recommends option 2 with item 3 for any production-like positioning. It is not implemented because it would
remove an accepted E1 capability and materially expand scope.

**Other Round 2 security changes.** `DEBIT_AUTHORITY_REVOKED` (R2-05, explicit execution-time creator authority);
reserved `payroll-` idempotency namespace (R2-06); authorization before state disclosure (R2-07). Unchanged and
re-verified: server-derived principal, CSRF on state-changing requests, HttpOnly/SameSite session cookie, 404 for
cross-organization access. Remaining uncertainty: customer e-mail probing via `addMember` (V1 registration already
reveals registration), and no recorded releaser of payroll execution (R2-14). Dependency gates are unchanged: npm audit
at `high` (2 low, 4 moderate, 0 high/critical) and the Maven OSV scan both pass in CI; nothing was suppressed.

## 9. Test and CI results

| Check | Command / location | Result |
|---|---|---|
| Baseline reproduction | `mvnw --batch-mode --no-transfer-progress -f backend/pom.xml clean verify`, fresh `ledgercore_r2_baseline`, at `b584b95` | exit 0; 49 tests, 0 failures/errors/skips |
| Red run (new suite on baseline) | `mvnw -Dtest=PayrollIntegrityPostgresIntegrationTests test`, fresh `ledgercore_r2_red` | exit 1; 19 tests, 12 failures (expected) |
| Remediation | `clean verify`, fresh `ledgercore_r2_green2` | exit 0; **75 tests**, 0 failures/errors/skips |
| Final local regression | `clean verify`, fresh `ledgercore_r2_final` | recorded in `evidence/round2/acceptance/` |
| Frontend | `npm run lint`, `npm run build` | exit 0 / exit 0 |
| OpenAPI contract | `npm run generate:api` against the remediated JAR; `git diff --exit-code -- src/api/schema.d.ts` | no diff |
| Browser | `npx playwright test` (Chromium) against the remediated JAR | 3 passed |
| CI for remediation `0a885c8` | run [43560923022](https://github.com/miku-wwl/ledgercore/actions/runs/43560923022) | both jobs success; 75 tests; 3 Playwright passed; OSV success |
| CI for later commits | see `ROUND-2-HANDOFF.md` §6 | recorded there |

New tests: 19 payroll integrity tests (`PayrollIntegrityPostgresIntegrationTests`), 7 migration compatibility tests
(`MigrationCompatibilityPostgresIntegrationTests`). The opt-in `Round2PerformanceFollowUpHarness` is compiled by every
build but excluded from normal discovery, like Round 1's harnesses.

## 10. Residual risks

1. **OD-1** — corporate payments are single-authorization under Policy A; admin self-provisioning (R2-03, R2-15).
2. **History-dependent ledger cost** — every transfer aggregates the source's full posting history twice; long-lived
   payer accounts slow down linearly (R2-10, R2-P2).
3. **Within-batch payroll slowdown** — claim walk over dead result versions plus payer history; an early-phase slowdown
   after bulk loading is unexplained (R2-12, R2-P3).
4. **Audit trail** — the member who released or resumed payroll execution is not recorded (R2-14).
5. **Operational gaps** — no cancel/skip workflow for a batch stuck PROCESSING after an unclassified failure; unbounded
   batch size and unpaginated item listing (R2-17); no corporate history/reconciliation endpoints (R2-18).
6. **Evidence limits** — local laptop, synthetic data, Hikari 10; the crash experiment covers one kill point; H02/H03
   latency above ten clients reflects pool queueing.
7. **Upgrade constraint** — populated V4 databases must upgrade with a build containing the V5 callback; databases
   altered by hand may be refused by V7 and then need the ADR 0004 owner procedure.
8. **Stale Round 1 database** — `ledgercore_test` on 55434 holds a V5 draft; it must never be repaired or reused.

## 11. Final Round 2 verdict

**ACCEPTED_WITH_NONBLOCKING_RISKS** — provided the GitHub Actions run for the final Round 2 head succeeds, as recorded
in `ROUND-2-HANDOFF.md` §6.

- Every confirmed P0/P1 defect within E0–E3 is fixed and verified: R2-01, R2-02, R2-08. No P0 was found.
- Every Round 1 claim was checked against source and raw evidence; where a claim was overstated (INV-08, result
  durability, migration compatibility, H02 attribution) the correction is recorded without rewriting Round 1 files.
- R2-03 is resolved as a documented design decision with owner decision OD-1. It does not block E4–E7; it **blocks
  E8 acceptance** and any claim that corporate payments are dual-controlled.
- The residual risks above are explicit, owned, and assigned to Round 3 stages.

Round 3 (GPT-6 Luna, E4–E11) may begin under the conditions in `ROUND-2-HANDOFF.md`. Round 4 has not started.
