# E8 enterprise security and authorization pre-registration

- Owner: GPT-6 Luna.
- Recorded: 2026-11-15 (NZDT), before E8 implementation tests and controlled authorization experiments.
- Branch: `main` only.
- Baseline: `9d23fd720bd65ca26aca279bb2ff249cca5f3af2`; E7 status commit CI run `43591592467` passed.
- Scope: independently challenge Policy B, tenant boundaries, role governance, session/CSRF behavior, callback authentication, operational authority, and audit attribution. Synthetic identities and funds only.
- Status: **NOT_RUN**. Source inspection is complete; no E8-specific tests or threat experiments have been executed.

## Questions

1. Can any corporate debit path outside a persisted, approved payment intent or approved payroll item move funds?
2. Do all corporate financial APIs and object-level reads deny or safely hide actions outside the actor's active role and organization?
3. Can an administrator, maker, former member, or operations actor escalate into payment approval or bypass Maker/Checker?
4. Do retries, replay, duplicate callbacks, or concurrent approvals create a second effective financial posting?
5. Are financially material actions attributable to authenticated actor and stable operation IDs without exposing credentials or unnecessary personal data?

## Frozen method

- Run backend tests against a fresh disposable PostgreSQL database on the isolated Round 3 cluster; do not use the persistent 55432 service, shared CI state, or prior evidence databases. Preserve the actual endpoint response, SQL before/after snapshot, application logs, test reports, and environment identity under ignored `work/e8-lab/runs/<RunId>/`.
- Use the Spring Boot test profile with an explicit `LEDGERCORE_TEST_DB_URL`. Use generated fictional customers and synthetic NZD minor-unit amounts only. Do not disable constraints, weaken authorization, or seed a real identity.
- Start from read-only inspection of every organization, transfer, payment, payroll, clearing, reconciliation, and operations controller plus the corresponding service boundary. Record existing tests that already satisfy a case; add focused tests only for material uncovered paths.
- For every denied request, compare journal/posting count, transfer rows, payment state, payroll results, idempotency receipts, outbox events, and account projections before/after. Expected: no financial or durable business side effect.
- A positive corporate payment must use create → submit → independent approve and bind approval to the submitted digest/version. A positive payroll debit must use its existing approved batch and item. A positive personal transfer must remain owner-authorized.
- Test invalid/forged callback signatures only against the isolated local endpoint. Never expose or save a signing secret; assert rejected callbacks cannot settle, refund, or mutate the payment. Duplicate accepted callbacks must not duplicate settlement.
- Test session/CSRF controls through the actual HTTP stack. Do not log session cookies, CSRF tokens, passwords, HMAC material, or raw authentication headers in tracked evidence.
- Preserve the existing 83-test backend baseline, generated OpenAPI behavior, frontend lint/build, browser suite, and dependency scan. Any defect discovered must be fixed and the same negative case rerun before acceptance.

## Authorization matrix to execute

| Actor | Corporate debit / payment | Payroll | Organization and operations access | Expected boundary |
|---|---|---|---|---|
| Personal customer | Own personal transfer only | None | No unrelated organization objects | Other customer/account/org IDs fail closed |
| Organization administrator | May manage organization but cannot directly debit or approve by admin status alone | No implied payroll authority | Member/account governance; no cross-organization access | Sensitive grants require a distinct target actor and audit |
| Payment maker | Create/submit payment; no approval or direct corporate transfer | No implied payroll role | Member reads only | Cannot approve own submitted intent |
| Payment approver | Approve/reject a valid submitted intent; no direct debit | No implied payroll role | Member reads only | Digest/version must match; submitter cannot decide |
| Payroll maker | No payment approval or direct corporate transfer | Prepare/submit; no self-approval | Member reads only | Existing approved execution path remains valid |
| Payroll approver | No implied payment authority | Approve/execute a valid distinct-maker batch | Member reads only | Cannot approve own batch |
| Finance viewer | No financial mutation | No batch mutation | Authorized read scope only | Write requests denied without side effects |
| Operations analyst | No direct debit/payment approval | May execute or resume only a previously approved payroll batch; cannot prepare, approve, or alter the approved intent | Reconciliation and scoped exception work only | Recovery is limited to persisted approval and must be actor-attributed |
| Suspended member | No retained sensitive authority | No execution authority | Hidden or denied member/org objects | Add-member cannot silently reactivate |
| Suspended organization | No payment, transfer, payroll, clearing, or mutation | No execution | No data leak through active endpoints | Fail closed |
| Cross-organization outsider | No payment or debit | No batch access | 404/object concealment where applicable | No cross-tenant data disclosure |

The exact HTTP status may be 401, 403, 404, or a documented conflict for the relevant API; acceptance is based on the established disclosure policy plus zero side effects, not on forcing one status across all routes.

## Planned negative and positive cases

| ID | Threat or invariant | Frozen probe | Expected observation |
|---|---|---|---|
| E8-01 | Old corporate transfer endpoint / confused deputy | REST and direct `TransferService.transfer` plus `transferAndRecord` attempts using a corporate source without approved payroll-item state | Rejected; unchanged postings, transfers, projections, and success receipts |
| E8-02 | Maker/Checker and approval replay | Maker attempts own approve/reject; admin attempts approve by admin role; approver repeats approval and concurrent approval requests target the same payment | Self/admin attempts denied; replay/concurrency create at most one booking/instruction and retain exact approval actor/digest |
| E8-03 | Altered financial intent | Attempt to change amount, payer, beneficiary, routing, or currency after submit/approval through service/API and the existing database integrity boundary | Mutation rejected or approval fails closed; no altered debit |
| E8-04 | Sensitive-role self-grant / bootstrap | Admin uses add-member and grant-role routes targeting self; separate second administrator/member receives explicitly granted sensitive role | Self-target denied; distinct-target role grant works and audit records actor/target/role; first administrator can establish a second administrator without unilateral payment execution |
| E8-05 | Suspension and revocation | Suspend a member with payment/payroll roles, attempt debit/approval/execution, call add-member for suspended user, then use explicit reactivation and inspect audit | No suspended authority; no silent reactivation; explicit action is actor-attributed; revoked authority fails closed |
| E8-06 | Personal/payroll compatibility | Execute one owner-authorized personal transfer and one legitimate approved payroll item | Both workflows succeed with balanced journals and stable idempotent identities |
| E8-07 | Tenant IDOR and data disclosure | Substitute another organization's account, payment, payroll batch/item, clearing operation, and reconciliation exception IDs across read/write endpoints | Denied/concealed, no cross-tenant facts or mutation |
| E8-08 | Suspended organization | Try corporate payment, transfer, payroll execution, clearing inquiry, and exception operations after organization suspension | No financial action or information disclosure |
| E8-09 | CSRF and session fixation | Omit/change CSRF token on cookie-authenticated writes; compare pre-login and post-login session identifiers using HTTP response cookies | CSRF write rejected; login rotates or invalidates pre-auth session according to configured Spring protection |
| E8-10 | Forged/duplicate callbacks | Invalid HMAC, wrong operation/payment/digest, callback replay, altered duplicate sequence | Invalid callback cannot alter financial state; exact duplicates are idempotent; conflicts create a scoped exception without second settlement/refund |
| E8-11 | Operations authority | Finance Viewer and Operations actor attempt reconciliation/assignment/resolution and any available financial retry path; submit a correction reference that is not a new balanced reversal | Role scope enforced; no operator can approve or debit; invalid correction rejected; audit records actor and operation |
| E8-12 | Audit, report minimization, logging | Query role-change/payment/payroll/callback/exception audit rows and logs for a synthetic flow; compare organization-scoped reports | Stable actor and operation IDs recorded; unrelated tenant data absent; no password/token/HMAC value or unnecessary credential data in logs |
| E8-13 | Dependency risk | Exact pushed commit runs existing npm audit and OSV thresholds | Required CI dependency gates succeed; findings, if any, are recorded without suppression |
| E8-14 | Constrained operational payroll recovery | Operations actor resumes an already-approved batch; also attempt an unapproved batch, a payment approval, and any direct corporate debit | Only the approved batch may execute/resume; actor and operation are audited; Operations cannot create or approve the intent and cannot directly debit |

## Acceptance and reporting rules

- An authorization matrix row is accepted only when each relevant read/write API is exercised or explicitly linked to a source-backed existing test; no endpoint coverage is inferred from a UI-only test.
- A denied corporate debit must have zero new financial journal/posting rows, zero successful transfer receipt, unchanged source projection, and no false success event.
- Maker/Checker must remain bound to persisted submitted intent and independent actor IDs, including retries/concurrency.
- Role changes must have explicit authenticated actor and target; suspension and reactivation must be separate durable actions.
- Operations authority must not bypass payment approval or mutate the ledger outside an approved, balanced business operation.
- Report only the tested behavior. Mark missing actors/routes/threats NOT_RUN or PARTIAL; do not claim regulatory certification.
- E8 will remain **PARTIAL** while any mandatory authorization role, corporate financial endpoint, or threat probe remains untested or CI is pending/failing.

## Safety limits

- Do not modify V1–V9 migrations. No destructive recovery operation belongs in this security experiment.
- No real bank, money, personal data, paid cloud, or secrets in tracked evidence.
- Do not change authorization solely to make an existing test pass; preserve a concrete negative test for each fixed bypass.
- Do not start E9–E11 until E8 results, limitations, direct-main commit, and exact CI are recorded.

## Pre-execution correction — 2026-11-15

Before any E8 test or threat probe ran, source inspection of `OrganizationAuthorizationService.requireCanExecutePayroll` showed that `OPERATIONS` may execute a batch that has already passed independent payroll approval. This is the existing constrained recovery path required for actor attribution when payroll resumes. The table above and E8-14 replace the initial overly broad interpretation in the first E8 pre-registration commit. E8 will verify that Operations cannot prepare, approve, alter, or directly debit, while a valid previously approved batch remains resumable and actor-attributed. The amendment is recorded before execution; no result has been observed.
