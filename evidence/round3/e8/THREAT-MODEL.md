# E8 Threat Model and Tested Controls

## Scope and status

This document records the security boundary reviewed for E8 and the evidence collected on 2026-11-15. The result is **PARTIAL**. It is a local simulation review, not a penetration test, regulatory assessment, or certification. All identities and funds used in the evidence are synthetic.

The security boundary includes the cookie-authenticated React/API entry point, Spring Security and organization authorization, payment and payroll services, PostgreSQL ledger/audit/outbox state, and the asynchronous clearing callback boundary. The external clearing simulator is a separate process and database; its callbacks are inputs, never authority to create a corporate debit.

Relevant trust boundaries are:

1. Browser session and CSRF token → authenticated API request.
2. Customer and organization membership → organization-scoped object lookup.
3. Payment maker → persisted submitted intent → independent payment approver.
4. Payroll maker → immutable approved batch/item → constrained execution or Operations resume.
5. Application transaction → PostgreSQL journal, postings, projections, audit, and outbox.
6. Outbox delivery and simulator callback → payment lifecycle, with callback authentication and idempotent state handling.

## Threat/control/evidence matrix

| Threat | Control reviewed or exercised | Evidence and result | Coverage |
|---|---|---|---|
| Corporate debit through an old endpoint or confused service call | Generic customer transfer is not authority to debit an organization account; corporate payment debit follows persisted approval. | `TransferPostgresIntegrationTests.receiptRecorderCannotBypassApprovedCorporateOperationBoundary`; organization API attempts. Denied attempts left account balance, transfer/idempotency rows, journals, and postings unchanged. | Partial: direct service `transferAndRecord` is tested; every transfer/service/API variant is not enumerated. |
| Maker self-approval and administrator privilege bypass | Approval requires the payment approver role and a different actor from the maker. Organization admin status does not imply payment authority. | Maker, org admin, Finance Viewer, and Operations approval attempts denied; independent approver succeeds. Two concurrent approval requests result in one booking, one clearing instruction, and one approval fact. | Tested for exercised payment endpoints; full route matrix remains open. |
| Altered submitted financial intent | Persisted intent and approval digest/version are checked; existing database integrity trigger protects submitted beneficiary/account and amount fields. | Direct SQL changes to beneficiary account reference or amount rejected; saved intent unchanged. Approval evidence reports digest match and one balanced booking. | Partial: payer, routing, currency, alternate API mutation and all replay/conflict variants were not separately exercised. |
| Sensitive role self-grant | Role assignment rejects self-targeting for the acting administrator. | Self-grant attempts through grant-role and add-member endpoints denied. | Partial: explicit grant to a distinct target and complete role lifecycle matrix not covered by this run. |
| Suspended membership retaining authority | Suspension is durable; add-member does not silently reactivate; explicit reactivation is separately attributable. | Suspended payment approver cannot approve; re-add remains suspended; explicit reactivation works. Grant/suspend/reactivation audit facts identify actor and target. | Partial: suspended payroll roles and all organization operations are not covered here. |
| Cross-tenant IDOR and payment data leakage | Organization-scoped payment lookup and lists; sensitive beneficiary instructions are role-redacted. | Cross-organization payment get/list/recent probes conceal the target. Maker/approver see payment instructions; org admin, Finance Viewer, and Operations receive `[REDACTED]`. Raw HTTP responses are retained under ignored `work/e8-lab/runs/20261115-live-api-probe/raw/`. | Partial: payment object/read routes were exercised; payroll, account, clearing, reconciliation, and every write route were not all traversed. |
| CSRF and session fixation | Cookie-authenticated state change requires CSRF; authentication rotates the session identifier. | Full-stack HTTP integration test rejects a write without CSRF and asserts the session ID changes after login/registration. | Partial: altered-token and route-by-route CSRF probes were not run. |
| Forged, replayed, or conflicting clearing callbacks | Callback HMAC and lifecycle checks protect the asynchronous payment boundary; duplicate processing must not book twice. | Existing callback security and lifecycle tests remain in the passing 87-test suite, including invalid/stale HMAC cases. | Partial: E8 did not run the full wrong-operation/payment/digest, altered duplicate sequence, conflicting callback, or replay matrix. |
| Payroll authority and Operations as a financial superuser | Operations may resume only work already independently approved; it cannot originate or approve the intent or directly debit. | Operations draft execution denied without financial effects. A separately approved partial batch resumes to completion with original approver preserved and Operations actor recorded. Direct corporate transfer and payment approval attempts denied. | Partial: every payroll API and every recovery/correction route was not covered. |
| Audit tampering or over-disclosure | Financial facts carry stable actor/operation references; payment audit/outbox records omit beneficiary account/routing data. | SQL snapshots show authenticated maker/approver, matching approval digest, one booking/instruction, and no beneficiary bank instructions in payment audit/outbox facts. | Partial: exhaustive log/credential review and all material action types (rejection, retry, cancellation, correction) were not executed. |
| Dependency vulnerabilities | Existing npm audit and Maven OSV workflows enforce repository policy. | Exact E8 commit `3a1beed048a878077865d571d50dac0060470e87` passed GitHub Actions run [43596797609](https://github.com/miku-wwl/ledgercore/actions/runs/43596797609), including Maven OSV and the full-stack workflow's dependency checks. No separate local dependency scan is claimed. | CI passed; E8 remains partial for other matrix gaps. |

## Residual threats and limitations

- E8 does not satisfy the pre-registered requirement to exercise every corporate financial API and every role/object combination. In particular, suspended organization, payroll-maker/approver route combinations, payroll and clearing IDOR, reconciliation writes, and several correction/recovery routes remain untested.
- Callback testing in this stage did not cover every altered, out-of-order, conflicting, or replay sequence.
- Credential and application-log review for passwords, cookies, CSRF tokens, HMAC values, and private keys remains outstanding. The E8 raw HTTP evidence intentionally excludes raw authentication headers and session credentials.
- The evidence covers application behavior and database state in local disposable PostgreSQL only. It does not establish production configuration, operational controls, legal compliance, or regulatory certification.
- Exact-commit CI, including the dependency scan, passed for E8 commit `3a1beed` in run `43596797609`. E9 may start after this outcome and the remaining limitations are recorded in the round status.
