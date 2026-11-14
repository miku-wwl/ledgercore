# Acceptance Matrix

## Round 3 stage gates

| Stage | Status | Evidence |
|---|---|---|
| Stage 0 baseline | PASS | Fresh `ledgercore_round3_baseline`, PostgreSQL 18.7, V1–V7; 75 tests, 0 failures/errors/skips; `evidence/round3/baseline/BASELINE.md` |
| Policy B design and E4 core | E4 VALIDATED; clearing callback verification pending | ADR 0005; INV-15; commit `0dfcb0c`; GitHub Actions run `43574289360` passed |
| E4 durable events | VALIDATED | `evidence/round3/e4/LOCAL-VALIDATION.md`; 78 backend tests; restart recovery and SQL evidence; OpenAPI, frontend lint/build, 3 Playwright journeys, and CI run `43574289360` pass |
| E5 clearing simulator | PARTIAL; local verification and CI passed | `evidence/round3/e5/LOCAL-VALIDATION.md`; separate process/database, stable operation, authenticated callbacks, restart, settlement/refund/UNKNOWN, balanced SQL; run `43580338058`; naturally underfunded reserve case remains unexercised |
| E6 fault laboratory | NOT_RUN | Pre-registered; no injection run yet |
| E7 independent reconciliation | NOT_RUN | Not started |
| E8 security and audit | NOT_RUN | Not started; Policy B acceptance remains pending |
| E9 observability/performance | NOT_RUN | R2-10/R2-12 pre-registered; no Round 3 measurements yet |
| E10 recovery | NOT_RUN | No backup/restore or PITR claim |
| E11 full stack | NOT_RUN | No Round 3 browser journeys or screenshots yet |

The historical matrix below remains the Round 1/2 record. A Round 3 stage will move to VALIDATED only after its required evidence, direct-main push, and relevant CI run pass.

| Gate | Acceptance evidence | E0 | E1 | E2 | E3 | Round 2 audit |
|---|---|---:|---:|---:|---:| ---: |
| Main-only workflow | Branch, HEAD, remote, clean tracked tree; direct push and CI | PASS; E0 commit a7fe0de and its GitHub Actions run 43531591907 succeeded | PASS; commit eab8175501e77dfc57b1d9ea404a73ed86c8ae26 and run 43536790093 succeeded | PASS; commit 500089581ac38741f475030ca706cd034a1a9c92 and run 43543154101 succeeded on main | PASS; H04 registration `81a9038`; final evidence commit `9e7a442`; direct-main CI runs 43546776607, 43550956746, and 43552848920 succeeded | PASS: `0a885c8`, `1b6ed68`, and the evidence commits pushed directly to `main`; no branch, worktree, or PR; CI runs 43560923022, 43561592007, and 43563660267 succeeded |
| Backend regression | Maven unit + PostgreSQL integration + Modulith | PASS: 42 tests, 0 failures/errors/skips | PASS: 44 tests, 0 failures/errors/skips locally; CI verify passed | PASS locally: 49 tests, 0 failures/errors/skips; CI full-stack acceptance passed | PASS: fresh isolated `ledgercore_round1_final_test`, 49 tests, 0 failures/errors/skips; E3 methods explicitly executed | PASS: 75 tests (49 + 19 payroll integrity + 7 migration compatibility), 0 failures, fresh `ledgercore_r2_final`; red run on baseline 12/19 failed as designed |
| Frontend | Lint and production TypeScript/Vite build | PASS | PASS locally and in CI | PASS locally and in CI | PASS: lint/build locally; CI full-stack acceptance passed | PASS: lint and build exit 0; no frontend change |
| OpenAPI | Generate types from running application and compare tracked contract | PASS: no diff | PASS: generated against V5; CI contract diff passed | PASS: V6 payroll operations/models generated; CI contract drift check passed | PASS in CI full-stack acceptance; no E3 API change | PASS: regenerated against the remediated JAR, no diff |
| Browser | Real Chromium workflow | PASS: 1 E2E test | PASS: 2 Chromium E2E tests locally; CI browser acceptance passed | PASS locally and in CI: 3 Chromium tests, including Maker/Checker and lost-response recovery | PASS in CI full-stack acceptance; no E3 UI change | PASS: 3 Chromium tests locally (run from the `D:\` path, R2-16) and in CI |
| Dependencies | npm audit and Maven OSV scan | npm: 2 low, 4 moderate, 0 high/critical; baseline CI OSV job passed | PASS: CI npm audit and OSV dependency scan succeeded | PASS: npm has 2 low/4 moderate and 0 high/critical; CI OSV scan succeeded | PASS; E3 CI runs 43546776607 and 43550956746 passed OSV scan | PASS: CI npm audit 2 low / 4 moderate / 0 high-critical; OSV job success; nothing suppressed |
| Source/schema audit | Ownership, authorization, transactions, locks, projections, migrations | PASS; risks recorded in ARCHITECTURE.md and ADR | PASS locally; V5 constraints, actor/source-owner split, and authorization reviewed | PASS; V6 constraints, immutable intent, approval digest, transaction boundary, and PostgreSQL locks exercised locally and in CI | PASS; E3 harness uses actual service proxies and isolated PostgreSQL; no production schema/write-path change | PASS after remediation: R2-01, R2-02 (V7) and R2-08 (V5 callback) fixed; V1–V6 byte-identical |
| Real corporate authorization | PostgreSQL-backed corporate ownership, roles, isolation, Maker/Checker, personal compatibility | NOT_RUN | LOCAL PASS: ownership XOR, role access, cross-organization 404, Maker/Checker policy, corporate transfer and V1 personal account path | n/a | NOT_RUN | PASS against Policy A; direct corporate debit by `PAYROLL_MAKER` recorded as owner decision OD-1 (R2-03) |
| Payroll correctness | 500 synthetic items, actual postings, no duplicates, approval immutability, recoverable partial completion | NOT_RUN | n/a | PASS locally: 500-item reconciliation plus actual kill/restart from 3 committed / 497 pending; full-stack CI run 43543154101 passed; see `evidence/e2/VALIDATION.md` | NOT_RUN | PASS: DB-level intent/result integrity, fail-closed completion, shared-payer and overlapping-executor concurrency, unclassified-failure and authority-loss tests |
| Performance evidence | Hypotheses, repeated actual measurements, query plans, financial invariants | NOT_RUN | n/a | NOT_RUN | PASS: H01 INCONCLUSIVE by rule; H02 SUPPORTED; H03 MIXED; H04 SUPPORTED. Raw plans, samples, figures, and zero-invariant checks preserved in `evidence/e3/` | PASS: R2-P1 SUPPORTED; R2-P2 INCONCLUSIVE by rule (cost established); R2-P3 per-prediction verdicts; all pre-registered at `1b6ed68` |
| Final handoff | Complete evidence-linked Round 1 handoff; no E4–E12 or V2 PDF | NOT_RUN | NOT_RUN | NOT_RUN | PASS: report committed and pushed on `main` | PASS: `ROUND-2-AUDIT.md` and `ROUND-2-HANDOFF.md`; Round 3 not started |
| Payroll intent/result integrity (DB level) | Direct-SQL attempts to move, rewrite, delete, or forge payroll rows are rejected by PostgreSQL | n/a | n/a | n/a | n/a | PASS: V7; `PayrollIntegrityPostgresIntegrationTests`; `evidence/round2/reproductions/` |
| Migration compatibility | Checksums pinned; populated older schemas upgrade without rewriting history; inconsistent history refused | n/a | n/a | n/a | n/a | PASS: `MigrationCompatibilityPostgresIntegrationTests` (fresh, V4, V5, V6 → V7, refusal) locally and in CI |
| Independent findings disposition | Every Round 2 finding has severity, evidence, and a disposition | n/a | n/a | n/a | n/a | PASS: 18 findings in `evidence/round2/findings/FINDINGS-REGISTER.md` |

A NOT_RUN or blocked mandatory gate cannot be represented as PASS. Stage status also requires a direct-main commit and verification of its relevant GitHub Actions run.
