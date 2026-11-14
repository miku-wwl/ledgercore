# LedgerCore V2 Status

## Current execution

| Round | Owner | Scope | Status |
|---|---|---|---|
| 1 | GPT-6 Luna | E0–E3 | **FINISHED** (`b584b95`, CI run 43553101864) |
| 2 | Claude Opus 5.5 | Independent audit and remediation of E0–E3 | **ACCEPTED_WITH_NONBLOCKING_RISKS** — `ROUND-2-AUDIT.md`, `ROUND-2-HANDOFF.md` |
| 3 | GPT-6 Luna | E4–E11 | **IN_PROGRESS — E4 VALIDATED; E5–E9 PARTIAL; E10 PRE-REGISTERED** — latest E9 evidence commit `7aad85f` passed exact CI `43606784685` |
| 4 | Claude Opus 5.5 | E12, final audit, V2 PDF | **NOT_STARTED** |

- Next owner: Claude Opus 5.5 (Round 4, after E11 handoff).
- Branch policy: direct work on main only; no branch, worktree, or PR.
- Final V2 PDF: reserved for Round 4; not started.

## Round 3 execution

- Starting commit: `0db728171f7a28119ed4c4f19437896878f03fc6`; `main` and `origin/main` matched and the working tree was clean.
- Stage 0: **BASELINE PASS**. On 2026-11-14, fresh disposable database `ledgercore_round3_baseline` on the isolated PostgreSQL 18.7 cluster passed `clean verify`: 75 tests, 0 failures/errors/skips; fresh V1–V7 migration succeeded. See `evidence/round3/baseline/BASELINE.md`.
- Policy B: **DECIDED** in `ADR/0005-corporate-payment-approval-and-clearing.md`; E4 payment approval boundary and local verification are complete.
- E4: **VALIDATED** — commit `0dfcb0c6afddb4bc10af98aa16eba4764a6eb02a` pushed directly to `main`; GitHub Actions run `43574289360` passed both full-stack acceptance and OSV scan. Local evidence: 78 backend tests, outbox restart/SQL verification, OpenAPI, frontend lint/build, and 3 Playwright journeys in `evidence/round3/e4/LOCAL-VALIDATION.md`.
- E5: **PARTIAL** — bank `clean verify`: 81 tests passed; simulator `clean verify`: 4 passed; separate bank/simulator processes and databases completed settlement, UNKNOWN inquiry, simulator restart, callback replay, and balanced-ledger SQL checks. A test injects the insufficient-reserve exception to verify no settlement and retry recovery; naturally underfunded reserve behavior remains unexercised. GitHub Actions run `43580338058` passed verification, browser acceptance, and OSV. Evidence: `evidence/round3/e5/LOCAL-VALIDATION.md`; preregistration is preserved in `PRE-REGISTRATION.md`.
- E6: **PARTIAL** — commit `e7c2bafb8b0eedf10d725c63568e2dc52529ea19` was pushed directly to `main`; GitHub Actions run [43584590857](https://github.com/miku-wwl/ledgercore/actions/runs/43584590857) passed full-stack acceptance and OSV. Three isolated personal-transfer process-kill/SQL-grade/recovery cases passed (pre-commit, post-commit/pre-response, and persisted outbox/pre-dispatch). Post-fix backend `clean verify` passed 82 tests, 0 failures/errors/skips. Payroll checkpoint and broader clearing, database, lock, worker, and migration faults remain unexecuted. Evidence: `evidence/round3/e6/LOCAL-VALIDATION.md` and `CASE-INVENTORY.md`.
- E7: **PARTIAL** — commit `19ddf2140ff9efd0d3f761da2680e78ed81afd4e`; exact CI run [43591228075](https://github.com/miku-wwl/ledgercore/actions/runs/43591228075) passed. 83 backend tests passed on a fresh PostgreSQL 18.7 database; OpenAPI generation, frontend lint/build, a zero-finding healthy fixture, exact projection-drift detection, Operations-only exception handling, a real two-process settled payment, and exact missing-callback detection passed locally. Missing-result, incomplete-terminal, event-corruption, stale/out-of-order clearing, wrong-beneficiary, and corrupted ownership scenarios remain unrun. See `evidence/round3/e7/LOCAL-VALIDATION.md`.
- E8: **PARTIAL** — implementation commit `3a1beed048a878077865d571d50dac0060470e87` was pushed directly to `main`; exact GitHub Actions run [43596797609](https://github.com/miku-wwl/ledgercore/actions/runs/43596797609) passed full-stack acceptance and Maven OSV. Fresh PostgreSQL 18.7 `clean verify`: 87 tests, 0 failures/errors/skips; focused live API/SQL probe: 3 tests passed. Policy B, role-redacted payment views, tenant concealment, approval concurrency, role suspension/reactivation, CSRF/session rotation, and constrained Operations payroll resume were exercised. The complete role × route/callback/logging matrix remains incomplete.
- E9: **PARTIAL / CLOSED** — implementation/cleanup commits `2dc8976`/`cb2601f` passed exact CI runs [43603381726](https://github.com/miku-wwl/ledgercore/actions/runs/43603381726) and [43605692774](https://github.com/miku-wwl/ledgercore/actions/runs/43605692774); evidence/status commit `7aad85fb966b0a51079891ef2759ad703ac063df` passed exact CI [43606784685](https://github.com/miku-wwl/ledgercore/actions/runs/43606784685). The V10 payroll index candidate was rejected after three fresh candidate databases. The R2-P2 projection-read supplement completed 12 fixtures with zero projection mismatches; at 100,000 postings authoritative SQL p50/p95 was 39.165/51.389 ms and projection-read p50/p95 was 0.330/0.558 ms. `promtool` 3.13.3 verified all five alert rules fire and clear on fixtures. W3C trace propagation and retained spans remain unimplemented. See `evidence/round3/e9/`.
- E10: **PRE-REGISTERED; NOT_RUN** — frozen recovery protocol in `evidence/round3/e10/PRE-REGISTRATION.md`; no E10 cluster, database, backup, or recovery measurement has been created yet.
- E11 remains NOT_STARTED. No E12, textbook, or final PDF work has begun.
- Round 3 plan and pre-registration: `ROUND-3-PLAN.md`, `evidence/round3/e4/PRE-REGISTRATION.md`, `evidence/round3/e5/PRE-REGISTRATION.md`, `evidence/round3/e6/PRE-REGISTRATION.md`, `evidence/round3/e9/PERFORMANCE-PRE-REGISTRATION.md`.
- Stop after E11 and the Round 3 handoff. E12 and the final PDF remain Round 4 work.

## Round 2 outcome (independent audit of E0–E3)

- Baseline `b584b95` independently reproduced: 49 tests, 0 failures, on a fresh disposable database.
- Confirmed and fixed P1 defects: R2-01 (payroll items could leave non-draft batches), R2-02 (payroll results could be
  deleted or forged and incomplete batches completed), R2-08 (V5 could not upgrade a V4 database holding transfers).
  Fixed P2/P3: R2-05, R2-06, R2-07, R2-09 (H04 progress scan), R2-13, R2-16. No P0 found.
- New forward-only migration V7; V1–V6 byte-identical with pinned checksums; V5 upgrade callback (ADR 0004).
- R2-03 (direct corporate debit by `PAYROLL_MAKER`) is the established Policy A; recorded as owner decision OD-1.
- Tests: 75 (49 + 19 payroll integrity + 7 migration compatibility), 0 failures locally and in CI runs 43560923022
  (`0a885c8`), 43561592007 (`1b6ed68`), and 43563660267 (`340272e`); 3 Chromium E2E pass; OpenAPI unchanged.
- Performance: R2-P1 SUPPORTED (batch-bounded progress); R2-P2 INCONCLUSIVE by rule but history-dependent ledger cost
  established; R2-P3 attributes within-batch payroll slowdown; Round 1 E3 interpretations annotated, not rewritten.
- Evidence: `evidence/round2/`; findings: `evidence/round2/findings/FINDINGS-REGISTER.md`.

## Round 1 record

The sections below are the Round 1 record and are kept as written.

## Stage status

| Stage | Status | Evidence / next action |
|---|---|---|
| E0 | VALIDATED | Commit a7fe0de280b61b66e87cf14c95aa4cf30d0982b8; local gates passed; CI run 43531591907 succeeded |
| E1 | VALIDATED | Commit eab8175501e77dfc57b1d9ea404a73ed86c8ae26 pushed to main; GitHub Actions run 43536790093 succeeded |
| E2 | VALIDATED | Commit `500089581ac38741f475030ca706cd034a1a9c92` is on `main`; GitHub Actions run `43543154101` passed full-stack acceptance, OpenAPI/browser checks, and OSV scan; local evidence is in `evidence/e2/VALIDATION.md` |
| E3 | VALIDATED — includes explicit inconclusive/mixed findings | H01–H03 executed from pre-registered workloads; supplemental H04 isolated the payroll progress scan. Raw samples, PostgreSQL plans, four data-derived figures, verdicts, invariants, local regression results, and Claude Opus 5.5 handoff are in `evidence/e3/` and `ROUND-1-HANDOFF.md`. H01 is INCONCLUSIVE; H03 is MIXED. No production optimization was made. |

## Verified starting point

- Repository: https://github.com/miku-wwl/ledgercore
- Baseline branch: main.
- Baseline source commit: 5eb6a626242bce79b5314be40b286819bdd9ac52.
- At audit start, local main and origin/main matched and the tracked working tree was clean.
- V1 application source and V1 textbook are the protected compatibility baseline.

The E0 local test database was a separate disposable PostgreSQL 18.7 cluster on 127.0.0.1:55434, database ledgercore_test. The existing persistent PostgreSQL service on port 55432 was not used. E2E generated synthetic test identities only in the disposable database.

## E3 acceptance

- E3 harness source was added on `main` at `137fa983751943c89873b1df33c299a8d6c2507d`; the environment metadata fix is `230e5873ba7f0d1c7df546eb1e81622fa12a8cc0`. CI run `43546776607` passed full-stack acceptance and OSV scan. Final evidence/handoff commit `9e7a4421c5ee7f4bdc69cce3cfbb3c1503e8078a` also passed both jobs in run `43552848920`.
- H01–H03 ran against separate database `ledgercore_e3_perf`; environment capture and detailed per-run evidence are preserved in `evidence/e3/measurements/`. H04 was preregistered at `81a903876ea3ac3214113dbf7bacb86f40307454`; CI run `43550956746` passed.
- H02 completed 48 concurrency scenarios with zero unexpected failures, transfer invariant violations, projection mismatches, overdrafts, or opposing-direction deadlocks.
- H03 completed 24 payroll scenarios (500 and 5,000 items × concurrency 1/10/30/50 × three repeats); all 66,000 measured items completed with zero recorded financial invariant violations. Its 5,000-item progress plan scanned all 71,500 result rows.
- H04 added 50,000 pending DRAFT items across ten synthetic organizations; the unchanged target batch remained 5,000 pending while the progress plan scanned 121,500 results. Post-fixture global invariants remained zero; H04 payer accounts had no postings.
- H01's 100-to-100,000-posting median p50 ratios were 126.4× for balance SQL and 20.1× for transfer service, but repeat variation and plan changes invoke its pre-registered INCONCLUSIVE rule. The batch-progress scale-independence prediction was rejected. These are local observations, not capacity claims.
- Final local regression: `mvn clean verify` on fresh database `ledgercore_round1_final_test`: 49 tests, zero failures/errors/skips. Frontend lint and production build passed. Full record: `evidence/e3/validation/LOCAL-VALIDATION.md`.
- Complete Round 1 handoff: `docs/v2/ROUND-1-HANDOFF.md`. E4–E12 and final V2 PDF work remain unstarted.

## E2 acceptance

- Durable payroll batches, immutable intent items, separate result rows, SHA-256 approval binding, Maker/Checker authorization, and bounded per-item transfer execution are implemented in V6 and the `payroll` Modulith module.
- Local backend: `clean verify`, 49 tests, 0 failures/errors/skips. The payroll PostgreSQL suite covered 500 synthetic employees, duplicate submission/execution, content mutation racing approval, insufficient funds, and transfer/result rollback.
- Local frontend: lint, production build, and 3 Chromium E2E tests passed. The payroll browser path imports CSV, rejects Maker self-approval, executes as an independent approver, and recovers the UI after a deliberately lost HTTP response.
- A separate packaged-JAR experiment force-killed the application with 3 of 500 items committed, then restarted it and resumed to 500/500. PostgreSQL evidence verified 500 transfer and idempotency rows, zero unbalanced transfers, zero projection mismatches, and NZ$500.00 final payer balance.
- Dependency audit: 2 low and 4 moderate npm findings; no high/critical findings. GitHub Actions OSV scan passed in run `43543154101`.
- Full reproduction and experiment observations: `evidence/e2/VALIDATION.md`; design decision: `docs/v2/ADR/0003-durable-payroll-intent-and-item-execution.md`.
- GitHub Actions run: https://github.com/miku-wwl/ledgercore/actions/runs/43543154101 — both full-stack acceptance and OSV jobs succeeded for the E2 commit.

## E0 stage acceptance

- Commit: a7fe0de280b61b66e87cf14c95aa4cf30d0982b8.
- Direct push to origin/main succeeded; the run checked out the same SHA.
- GitHub Actions: https://github.com/miku-wwl/ledgercore/actions/runs/43531591907 — verify and OSV dependency-scan jobs succeeded.

## E1 local acceptance

- Scope: organization membership and roles, corporate account ownership, source-owner audit columns, authorization, and minimal organization/transfer UI.
- Backend: `mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify` — 44 tests, 0 failures/errors/skips; V5 validated against disposable PostgreSQL 18.7 database `ledgercore_e1_test`.
- Frontend: `npm run lint` and `npm run build` passed; OpenAPI TypeScript definitions generated from the running application.
- Browser: `npm run test:e2e` — 2 Chromium tests passed, including the organization admin → PAYROLL_MAKER grant → corporate transfer path.
- Reproduction details: `evidence/e1/VALIDATION.md`.
- E1 implementation commit: eab8175501e77dfc57b1d9ea404a73ed86c8ae26.
- Direct push to `origin/main` succeeded; GitHub Actions: https://github.com/miku-wwl/ledgercore/actions/runs/43536790093 — full-stack acceptance and OSV dependency scan succeeded.
