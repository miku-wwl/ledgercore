# LedgerCore V2 Plan B — Round 3 Execution Plan

- Owner: GPT-6 Luna.
- Scope: E4–E11, engineering implementation and evidence only.
- Repository policy: direct commits to `main`; no branches, worktrees, PRs, force pushes, or history rewrites.
- Accepted starting point: `0db728171f7a28119ed4c4f19437896878f03fc6`.
- Next owner: Claude Opus 5.5, Round 4.
- Stop rule: after E11 acceptance and this round's handoff. E12 and final PDF publication remain out of scope.

## Baseline

On 2026-11-14, `main` matched `origin/main` at the accepted SHA with a clean working tree. Round 2's dedicated PostgreSQL 18.7 cluster was started on `127.0.0.1:55436`; a new database, `ledgercore_round3_baseline`, owned by `ledgercore_r2`, was created. The persistent 55432 service and all existing Round 2 evidence databases were not used.

The unmodified Round 2 application passed `mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify`: 75 tests, 0 failures, 0 errors, 0 skips; fresh database migration V1–V7 succeeded. Maven reported 1:56 total build time. See `evidence/round3/baseline/BASELINE.md`.

## Accepted business decision: Policy B

Round 3 adopts independent approval for every corporate outgoing payment. This is a new decision and supersedes only the direct corporate debit rule in ADR 0002; it does not rewrite the Round 2 record. Existing payroll stays on its separately approved batch workflow. Personal account transfers remain compatible.

The financial service boundary must derive authority from persisted approval state. The generic customer transfer API must reject corporate-source debits. Payroll may use the shared ledger only after validating its persisted approved batch and item. A corporate payment may execute only from an immutable, separately approved intent. No request parameter, UI state, caller-supplied flag, event replay, or simulator callback may grant debit authority.

Initial entitlement design distinguishes `PAYMENT_MAKER` and `PAYMENT_APPROVER` from payroll roles. Sensitive role changes are attributable and cannot target the acting administrator's own membership. Suspended memberships are never silently reactivated by an add-member request. Organization creation remains usable: its first administrator can invite another customer and appoint a second administrator, who can establish separate payment roles.

Full rationale and constraints are in `ADR/0005-corporate-payment-approval-and-clearing.md`.

## Stage sequence and gates

| Stage | Initial status | Scope and exit evidence |
|---|---|---|
| Stage 0 | BASELINE PASS; design recorded | Fresh isolated 75-test baseline; Policy B ADR; pre-registered experiments; plan committed. |
| E4 | NOT_STARTED | Approved-operation boundary; durable outbox; at-least-once dispatch; idempotent consumers; actor-attributed payroll and payment events; crash/retry evidence. |
| E5 | PARTIAL | Independent simulator process/database, authenticated callback lifecycle, stable operation identity, balanced booking/settlement/refund, restart and UNKNOWN inquiry are locally verified; CI run `43580338058` passed. A naturally underfunded reserve case remains outstanding. See `evidence/round3/e5/LOCAL-VALIDATION.md`. |
| E6 | PARTIAL | Three isolated personal-transfer crash/outbox cases passed inject → independent SQL grade → restore; 82-test post-fix backend clean verify passed. Commit `e7c2bafb8b0eedf10d725c63568e2dc52529ea19` passed GitHub Actions run [43584590857](https://github.com/miku-wwl/ledgercore/actions/runs/43584590857). Payroll failure-mark hook exists without an executed payroll fixture; other preregistered faults remain unrun. See `evidence/round3/e6/LOCAL-VALIDATION.md` and `CASE-INVENTORY.md`. |
| E7 | PARTIAL | Independent SQL reconciliation and Operations exception workflow implemented; clean payroll/corporate-payment fixtures, projection drift, and missing callback exercised. Unrun anomalies and coverage gaps are in `evidence/round3/e7/LOCAL-VALIDATION.md`; commit `19ddf2140ff9efd0d3f761da2680e78ed81afd4e` passed exact-commit CI run `43591228075`. |
| E8 | PARTIAL | Implementation commit `3a1beed048a878077865d571d50dac0060470e87` passed exact GitHub Actions run [43596797609](https://github.com/miku-wwl/ledgercore/actions/runs/43596797609), including full-stack acceptance and Maven OSV. Fresh PostgreSQL 18.7 backend `clean verify`: 87 tests pass; focused live API/SQL probe: 3 pass. Policy B, approval/replay, role redaction, cross-tenant payment reads, suspension/reactivation, CSRF/session rotation, and constrained Operations payroll resume have evidence. Full role × route, callback variants, and log review remain outstanding. See `evidence/round3/e8/LOCAL-VALIDATION.md` and `THREAT-MODEL.md`. |
| E9 | NOT_STARTED | Correlated structured telemetry and metrics; distinguish pool, lock, SQL, business and clearing delay; pre-registered R2-10/R2-12 measurements. |
| E10 | NOT_STARTED | Actual isolated backup/restore; WAL/PITR only if run; V7/V8+ integrity checks; cross-system clearing review; measured RPO/RTO. |
| E11 | NOT_STARTED | Real API-backed responsive React banking, corporate payment/payroll and operations flows; 12 Playwright journeys and genuine screenshots. |

A stage is not VALIDATED until its required tests, independent database evidence, direct-main commit/push, and relevant GitHub Actions run are complete. Partial, blocked, failed, and unexecuted checks stay explicit.

## Current progress — 2026-11-15

- Stage 0 baseline and Policy B design are committed on `main`; Stage 0 CI run `43567898184` succeeded.
- E4 is **VALIDATED**: commit `0dfcb0c6afddb4bc10af98aa16eba4764a6eb02a` is on `main`, and GitHub Actions run `43574289360` passed. Local backend, browser, API-contract, restart, and SQL evidence is in `evidence/round3/e4/LOCAL-VALIDATION.md`.
- E5 is **PARTIAL**: the independent simulator, bank callback lifecycle, operations inquiry, migration V9, and typed OpenAPI endpoints are implemented on `main`. Local bank `clean verify` passed 81 tests, simulator `clean verify` passed 4, and a two-process/two-database restart exercise passed with balanced entries and zero projection mismatches. GitHub Actions run `43580338058` passed both verification and OSV jobs. A test injects the reserve-insufficient exception and proves safe retry, but a real liquidity-shortfall state remains unexercised because no reserve-drain business operation exists. See `evidence/round3/e5/LOCAL-VALIDATION.md`; the pre-registration remains unchanged.
- E6 is **PARTIAL**: three isolated personal-transfer cases passed an actual process-kill → independent PostgreSQL grade → restart/replay sequence (pre-commit, post-commit/pre-response, and persisted outbox/pre-dispatch). Post-fix backend `clean verify` passed 82 tests with no failures, errors, or skips. Commit `e7c2bafb8b0eedf10d725c63568e2dc52529ea19` passed full-stack acceptance and OSV in GitHub Actions run [43584590857](https://github.com/miku-wwl/ledgercore/actions/runs/43584590857). The payroll failure-mark checkpoint and broader clearing/database/lock/migration faults remain unexecuted. See `evidence/round3/e6/LOCAL-VALIDATION.md` and `CASE-INVENTORY.md`.
- E7 is **PARTIAL**: the bank-only integration fixture reports zero healthy findings, detects exactly one +1-minor-unit projection discrepancy, and verifies Operations-only exception assignment/resolution with immutable facts and audit. A separate bank/simulator two-process run reconciled a settled payment to zero findings; a controlled missing-callback run detected exactly one `CLEARING_CALLBACK_MISSING` after `SENT`, with no settlement/refund. Current full backend `clean verify` passed 83 tests on fresh PostgreSQL 18.7; frontend lint/build and generated OpenAPI passed. E7 commit `19ddf2140ff9efd0d3f761da2680e78ed81afd4e` passed exact-commit GitHub Actions run [43591228075](https://github.com/miku-wwl/ledgercore/actions/runs/43591228075). Remaining unrun cases are explicitly listed in `evidence/round3/e7/LOCAL-VALIDATION.md`.
- E8 is **NOT_RUN / PRE-REGISTERED** against baseline `9d23fd720bd65ca26aca279bb2ff249cca5f3af2` (whose CI run `43591592467` passed). The frozen security matrix, planned API and service-boundary probes, financial side-effect grading, and threat coverage are recorded in `evidence/round3/e8/PRE-REGISTRATION.md`; no E8 test has run.
- E8 is **PARTIAL** after local verification and exact-commit CI run `43596797609` passed for `3a1beed`; the unrun threat/route matrix is documented.
- E9 is **PARTIAL / IN_PROGRESS**. Commit `2dc8976136396afa871c1fee599327d0c0e2fbaf` passed exact GitHub Actions run `43603381726`. Three fresh candidate databases completed 19,500 synthetic payroll items with zero unbalanced entries or projection mismatches; R2-P3 did not meet the registered keep rule, and query plans show the candidate index was unused. The forward-only V11 removal, original claim query, performance-harness projection-read supplement, and 94-test fresh-database `clean verify` are locally verified but await commit/CI. R2-P2 projection-read measurement, W3C span tracing, and PromQL rule-engine evaluation remain pending. See `evidence/round3/e9/R2-P3-CANDIDATE-RESULTS.md` and `LOCAL-VALIDATION.md`.
- E10–E11 remain NOT_STARTED. No E12, textbook, or final PDF work has begun.

## Non-negotiable preservation rules

- Preserve INV-01–INV-14 and every V1–V7 migration byte-for-byte. Use forward migrations V8+ only.
- The authoritative ledger remains inside the Spring Boot/PostgreSQL transaction. Events never post money.
- Preserve payroll operation key `payroll-{batchId}-{itemId}`, owner = batch creator, operation = `TRANSFER`, and customer key prefix reservation.
- Keep event delivery at least once; idempotency and persisted business state prevent duplicate financial effects.
- Keep the external simulator, its persistent operations, and its credentials outside the banking DB transaction and ownership boundary.
- No real bank, real money, paid cloud infrastructure, fabricated evidence, unsafe force-success/skip controls, or changes to unrelated labs.
- Do not start E12, textbook authoring, or final V2 PDF work.
