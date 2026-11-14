# E8 Local Validation

## Result

**E8 status: PARTIAL.** Policy B and several material authorization boundaries were exercised with synthetic identities and funds. The required role × route matrix, complete callback threat set, and broad logging review are not complete. Exact-commit CI passed. This report does not claim regulatory certification.

## Environment and runs

| Run | Environment | Command/result |
|---|---|---|
| Focused live API and SQL probe | Isolated PostgreSQL 18.7 at `127.0.0.1:55438`, disposable DB `ledgercore_e8_liveprobe_20261115`; packaged application on port 8080, test profile, clearing API enabled, scheduler disabled. | `-Dtest=OrganizationPostgresIntegrationTests` with `-Dledgercore.e8.evidence-dir=work/e8-lab/runs/20261115-live-api-probe/raw`; 3 tests, 0 failures/errors/skips. Raw responses, status/path records, SQL snapshots, focused Maven log, and environment note are preserved in ignored `work/e8-lab/runs/20261115-live-api-probe/`. |
| Final backend regression | Fresh disposable DB `ledgercore_e8_finalverify_20261115_02`, PostgreSQL 18.7 at `127.0.0.1:55438`, JDK 21.0.13; V1–V9 applied by the test configuration. | `backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify`; 87 tests, 0 failures/errors/skips; Maven exit 0, BUILD SUCCESS (2:35). Full log and environment/summary are in ignored `work/e8-lab/runs/20261115-final-backend-verification-02/`. |
| Frontend regression | Existing frontend source; no frontend files changed in E8. | `npm run lint` and `npm run build` passed. Logs are in ignored `work/e8-lab/runs/20261115-final-backend-verification/`. |
| OpenAPI contract | Packaged application with clearing enabled. | Generated TypeScript schema; tracked `frontend/src/api/schema.d.ts` has no content diff. Logs are in the same ignored local run directory. |

Both PostgreSQL runs used the isolated Round 3 cluster rather than the persistent local database. All tested funds and identities were synthetic. No migration was added or changed; V1–V9 remain unchanged.

## Tested behavior

- **Policy B:** corporate payment follows create → submit → independent approve. The approval digest/version matches the submitted intent; one booking creates one balanced journal entry with two postings and one clearing instruction.
- **No unauthorized debit:** corporate debit attempts through generic transfer/service entry points are denied. Before/after SQL snapshots show the denied attempt left the synthetic account at 100000 minor units, with zero corporate transfer and idempotency rows, four journal entries, and eight postings.
- **Maker/Checker and retry:** maker self-approval and approval attempts by organization admin, Finance Viewer, and Operations are denied. Concurrent requests from the authorized approver produce one booking, one instruction, and one approval audit fact.
- **Role-based data minimization:** payment get/list/recent responses expose bank instructions only to `PAYMENT_MAKER` and `PAYMENT_APPROVER`; organization admin, Finance Viewer, and Operations see `[REDACTED]`. Cross-organization payment get/list/recent probes are concealed.
- **Role governance:** an administrator cannot target their own membership through grant-role or add-member. Suspending a payment approver disables approval; add-member does not reactivate the suspended membership; explicit reactivation and role changes are attributed to actor and target.
- **Intent integrity:** direct SQL updates to submitted beneficiary account reference and amount are rejected by the existing database integrity boundary.
- **Operations boundary:** Operations cannot execute a draft payroll batch, approve a payment, or make a generic corporate transfer. Operations can resume a partially executed, independently approved payroll batch; the original approver remains the approval actor, and the resuming actor is recorded.
- **Session controls:** the full-stack HTTP test denies a cookie-authenticated write without CSRF and checks that the session identifier changes after authentication.
- **Existing regression:** callback authentication tests for invalid/stale HMAC, personal transfers, payroll integrity, and the rest of the existing backend suite passed as part of the 87-test clean verification. This does not substitute for the unrun callback conflict and full role/API matrix cases below.

## Independent SQL grading from the live probe

The raw snapshots are in `work/e8-lab/runs/20261115-live-api-probe/raw/`.

| Snapshot | Balance (minor units) | Corporate transfers / idempotency | Journal entries / postings | Result |
|---|---:|---:|---:|---|
| Before denied direct debit | 100000 | 0 / 0 | 4 / 8 | Baseline |
| After denied direct debit | 100000 | 0 / 0 | 4 / 8 | No financial or success-receipt side effect |
| After independent approval | 99000 | 0 / 0 | 5 / 10 | One 1000-minor-unit NZD booking; one balanced journal entry (2 postings), one clearing instruction, approval digest matches submitted version |

Audit/outbox snapshot facts contained amount/currency, payment/operation IDs, digest/version, and booking reference; no beneficiary account reference or routing code was present.

## Remaining required work and exact gate

The following are **not claimed as passed**: suspended-organization coverage; every payroll maker/approver and payroll object route; clearing and reconciliation IDOR/read/write coverage; every corporate debit service/controller path; all altered payer/beneficiary/routing/currency mutations; rejection/cancellation/retry/correction audit variants; changed CSRF-token probes; wrong callback operation/payment/digest and conflicting/out-of-order duplicate sequences; exhaustive log/credential scan; and a local dependency scan.

The recorded test/report set is therefore **PARTIAL** even though the current local backend and frontend checks passed. E8 implementation commit `3a1beed048a878077865d571d50dac0060470e87` was pushed directly to `main`; exact GitHub Actions run [43596797609](https://github.com/miku-wwl/ledgercore/actions/runs/43596797609) passed full-stack acceptance (backend, simulator, API contract, browser, and frontend steps) and Maven OSV. Do not modify V1–V9; any future schema correction must use a new forward-only migration.
