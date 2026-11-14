# LedgerCore V2 Status

## Current execution

- Owner: GPT-6 Luna.
- Round: 1.
- Authorized stages: E0–E3 only.
- Next owner: Claude Opus 5.5.
- Next activity after this round: independent audit and remediation of E0–E3.
- Branch policy: direct work on main only; no branch, worktree, or PR.
- Final V2 PDF: reserved for Round 4; not started.

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

- E3 harness source was added on `main` at `137fa983751943c89873b1df33c299a8d6c2507d`; the environment metadata fix is `230e5873ba7f0d1c7df546eb1e81622fa12a8cc0`. CI run `43546776607` passed full-stack acceptance and OSV scan.
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
