# LedgerCore V2 Plan B — Round 3 Acceptance

**Round:** 3 — GPT-6 Luna
**Authorized scope:** E4–E11
**Accepted Round 2 baseline:** `0db728171f7a28119ed4c4f19437896878f03fc6`
**Current repository branch:** `main` only
**Next owner:** Claude Opus 5.5 — Round 4
**Publication:** NOT_STARTED; no final V2 PDF was generated.

## Overall result

Round 3 is ready for the final E11 direct-main implementation commit and exact CI gate before handoff. E4 and E10 are VALIDATED. E5–E9 remain PARTIAL with their unexecuted cases documented. E10 evidence commit `8f2dbee863d85978ed31aa0cc9b21e0ef0c5db05` passed exact GitHub Actions run [43624499785](https://github.com/miku-wwl/ledgercore/actions/runs/43624499785), including Build/security/full-stack acceptance and Maven OSV scan. E11's full-stack acceptance passed locally; its implementation commit has not yet been made.

No feature branch, worktree, pull request, force push, or history rewrite was used. All Round 3 commits were made or will be made directly on `main`. V1–V7 migration files and the preserved Round 1/Round 2 evidence remain intact. No real bank, money, or identity was used.

## Stage results

| Stage | Status | Implemented / observed | Verification and exact CI | Remaining issues |
|---|---|---|---|---|
| Stage 0 | PASS | Accepted baseline and Policy B registered on `main` | Fresh PostgreSQL 18.7; 75 backend tests; CI run `43567898184` | None for the baseline gate |
| E4 | VALIDATED | Corporate approved-operation boundary; transactional durable outbox and at-least-once dispatch | 78 backend tests, restart/SQL verification, API and browser checks; commit `0dfcb0c`; CI `43574289360` | Stage-specific limits are in `evidence/round3/e4/LOCAL-VALIDATION.md` |
| E5 | PARTIAL | Independent simulator/database, stable operation identity, signed callback lifecycle, settlement/rejection/UNKNOWN inquiry | Bank 81 tests, simulator 4 tests, two-process restart and balanced-ledger checks; CI `43580338058` | Naturally underfunded reserve state is not exercised; no reserve-drain business operation exists |
| E6 | PARTIAL | `inject/grade/restore` style process-kill harness for personal transfers | Three isolated crash windows; 82 backend tests; CI `43584590857` | Payroll failure-mark checkpoint and broader clearing, database, lock, worker, and migration faults remain unrun |
| E7 | PARTIAL | Independent ledger/projection and clearing reconciliation; audited exception assignment/resolution | 83 backend tests; healthy, projection-drift, settled, and missing-callback fixtures; CI `43591228075` | Missing-result, incomplete-terminal, event-corruption, stale/out-of-order clearing, wrong-beneficiary, and corrupted-ownership cases remain unrun |
| E8 | PARTIAL | Policy B, separate payment roles, maker/checker, tenant concealment, audited role lifecycle, constrained operations | 87 backend tests and 3 focused live API/SQL probes; CI `43596797609` | Full role × endpoint/callback/logging authorization matrix remains incomplete |
| E9 | PARTIAL / CLOSED | PostgreSQL metrics/correlation logs; pool-vs-lock observation; measured projection reads; candidate index decision; five alert fire/clear checks | Three fresh candidate DBs and 19,500 payroll items; 12 projection fixtures; `promtool` 3.13.3; CI `43603381726`, `43605692774`, `43606784685` | W3C trace propagation and retained spans were not implemented; candidate index was rejected |
| E10 | VALIDATED | SHA-256 physical backup, `pg_verifybackup`, base restore, post-backup PITR, independent SQL grade, and simulator settlement replay | 1,379 files / 35,161,072 bytes; backup 3.958s; RPO 118.922s; RTO 670.008s; 0 unbalanced journals and 0 projection mismatches. Evidence commit `8f2dbee`; exact CI run `43624499785` passed both jobs | One local Windows/PostgreSQL 18.7 run; no production SLA, off-host backup durability, repeated variance, or broad failure matrix claim |
| E11 | LOCAL PASS; commit/CI PENDING | Personal banking, corporate payments and payroll, Operations Console, role grants/revocation, responsive UI, genuine screenshots | Backend 95/95; simulator 6/6; lint/build/OpenAPI pass; final real-API Chromium 8/8; 1280×720 and 390×844 screenshots; Tab focus-visible and no horizontal overflow | No screen-reader/axe scan or broad device/browser matrix; E11 direct-main commit and exact CI pending |

Stage-level records are linked from `evidence/round3/e4/LOCAL-VALIDATION.md` through `evidence/round3/e11/LOCAL-VALIDATION.md`. E10 raw command outputs and SQL grades are in `evidence/round3/e10/raw/E10-raw-artifacts.zip`, with original-file SHA-256 values in the adjacent manifest. E11 screenshots are genuine browser output in `evidence/round3/e11/screenshots/`.

## Financial correctness summary

- **Internal transfers and payroll:** authoritative double-entry posting, idempotency receipt, and business result remain inside the bank PostgreSQL transaction. Payroll execution is batch-bounded and keeps the approved `payroll-{batchId}-{itemId}` operation identity.
- **Corporate payment approval:** Policy B requires an immutable independently approved intent; the actor cannot approve their own payment. The generic transfer API cannot debit a corporate source account.
- **External payment booking:** after independent approval and before dispatch, one journal debits the customer's deposit liability and credits `CLEARING_PAYABLE`. Dispatch, outbox replay, callbacks, and reconciliation never create that original booking.
- **Settlement:** a definitive SETTLED outcome posts one balanced journal that clears the payable against the finite `SIMULATION_RESERVE` asset. A definitive pre-settlement rejection reverses the payable to the customer. Rejection after settlement is an exception; any compensation requires a separately authorized reversing journal.
- **UNKNOWN:** the obligation remains pending under its original stable operation ID and digest. Operations inquiry resolves the independent simulator outcome; no retry with a new financial identity and no automatic refund is allowed.
- **Duplicate prevention and recovery:** event delivery is at least once; stable IDs, callback inbox/outbox state, sequencing, and transactional idempotency protect financial effects. E10 proved that the simulator can become SETTLED while the bank database is unavailable, then deliver PENDING and SETTLED after PITR without a second booking, settlement, or reversal.

## Acceptance boundaries

The statuses above are engineering evidence, not a production release certification. E5–E9 partials are intentionally retained. E10 RPO/RTO are one-run lab observations under the frozen definitions, not service objectives. E11 accessibility evidence is a focused semantic/keyboard/viewport review rather than a WCAG certification. The local-only ignored run directories retain additional original logs/data, while the selected non-secret E10 evidence, E11 Playwright transcript, and screenshots are versioned.

Round 4 may begin after E11's exact main-commit CI succeeds and this acceptance/handoff update is pushed. Round 4 owns E12, independent final audit and remediation, and professional Chinese V2 PDF publication. No E12 or PDF work is included here.
