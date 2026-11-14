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
| E5 | NOT_STARTED | Separately restartable clearing simulator and separate DB; authenticated callbacks; stable operation identity; balanced booking, settlement and rejection entries; UNKNOWN reconciliation. |
| E6 | NOT_STARTED | Safe `inject` / `grade` / `restore` laboratory; real fault hooks behind explicit test configuration; independent SQL verdicts and case inventory. |
| E7 | NOT_STARTED | Independent SQL reconciliation across ledger, receipts, payroll, payment intent, clearing, events, terminal states and organization boundaries; controlled exception workflow. |
| E8 | NOT_STARTED | Authorization matrix; Policy B negative and positive tests; entitlement governance; audit trail; callback and operations authority tests. |
| E9 | NOT_STARTED | Correlated structured telemetry and metrics; distinguish pool, lock, SQL, business and clearing delay; pre-registered R2-10/R2-12 measurements. |
| E10 | NOT_STARTED | Actual isolated backup/restore; WAL/PITR only if run; V7/V8+ integrity checks; cross-system clearing review; measured RPO/RTO. |
| E11 | NOT_STARTED | Real API-backed responsive React banking, corporate payment/payroll and operations flows; 12 Playwright journeys and genuine screenshots. |

A stage is not VALIDATED until its required tests, independent database evidence, direct-main commit/push, and relevant GitHub Actions run are complete. Partial, blocked, failed, and unexecuted checks stay explicit.

## Non-negotiable preservation rules

- Preserve INV-01–INV-14 and every V1–V7 migration byte-for-byte. Use forward migrations V8+ only.
- The authoritative ledger remains inside the Spring Boot/PostgreSQL transaction. Events never post money.
- Preserve payroll operation key `payroll-{batchId}-{itemId}`, owner = batch creator, operation = `TRANSFER`, and customer key prefix reservation.
- Keep event delivery at least once; idempotency and persisted business state prevent duplicate financial effects.
- Keep the external simulator, its persistent operations, and its credentials outside the banking DB transaction and ownership boundary.
- No real bank, real money, paid cloud infrastructure, fabricated evidence, unsafe force-success/skip controls, or changes to unrelated labs.
- Do not start E12, textbook authoring, or final V2 PDF work.
