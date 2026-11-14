# E11 local validation — full-stack banking and operations

## Verdict

**Local E11 acceptance: PASS.** All identities and funds were synthetic. Playwright drove the React application against the real local Spring Boot APIs, a separate clearing simulator, and isolated PostgreSQL databases. No V1–V7 migration was edited; E11 adds no database migration.

**Final E11 acceptance: VALIDATED.** Feature/evidence commit `fdd4e0d7ef7aef7f944b24db5463efe9c34141b4` and CI-fix commit `de0624c34cbd11f7d847e5dc5c48ee5efc20a475` passed exact GitHub Actions run [43626858589](https://github.com/miku-wwl/ledgercore/actions/runs/43626858589), including both verification/OSV jobs and all 8 browser tests. The preceding run [43625867646](https://github.com/miku-wwl/ledgercore/actions/runs/43625867646) found that CI had not started the independent clearing simulator; commit `de0624c` adds simulator startup with an isolated database and the shared callback/signature configuration. This was a CI environment gap, not a skipped browser requirement.

## Implementation

- Corporate payments now use the backend payment API and server pagination. The UI distinguishes submitted, awaiting approval, pending/unknown clearing, and confirmed settlement; Maker and Checker are separate actors.
- The Operations Console uses organization-scoped API data for metrics, exact payment/operation/event/correlation lookup, audit and event timelines, DEAD-event retry, clearing inquiry, and reconciliation exceptions. An exception can only be resolved after assignment and a fresh clean scan; audited facts are not edited.
- Role-aware navigation refreshes organization access so revocation takes effect across sessions. The organization page can grant and revoke payment and Operations roles.
- New role-protected operations endpoints return organization-scoped metrics, redacted audit records, and exact search results. A distinct role cannot access or infer another organization's operation. The OpenAPI TypeScript contract was generated from the live V11 app at `127.0.0.1:18096`.
- The independent simulator now derives response sequence numbers from persisted callback records. Repeated inquiry of a terminal operation no longer invents a callback sequence. PostgreSQL integration regressions cover repeated terminal inquiries and replay.

## Verification performed

- Backend `mvn clean verify` on fresh disposable database `ledgercore_e11_verify`: **95 tests, 0 failures, 0 errors, 0 skipped**; build succeeded.
- Clearing simulator `mvn clean verify` on separate fresh database `ledgercore_e11_sim_verify`: **6 tests, 0 failures, 0 errors, 0 skipped**; includes the stable terminal-sequence regressions.
- Frontend `npm run lint`: exit 0.
- Frontend `npm run build` (`tsc -b` and Vite production build): exit 0.
- OpenAPI TypeScript generation from the running V11 backend completed successfully.
- Chromium Playwright final run: **8 passed, 0 failed, 0 skipped**, one worker. The checked-in browser suite covers the 12 required journeys across these eight tests:
  1. Personal registration, transfer, history, ownership, and lost-response recovery.
  2. Organization onboarding and payroll-maker role grant; no corporate funds leak into generic transfers.
  3. Corporate payment draft, Maker/Checker approval, real simulator settlement, and rejection of self-approval.
  4. Payroll import/submission, independent approval, one-time execution, and lost-response recovery.
  5. UNKNOWN clearing inquiry and settlement recovery; synthetic missing-callback reconciliation, assignment, fresh clean scan, and audited resolution.
  6. Cross-organization data concealment and tenant isolation.
  7. Backend rejection of reconciliation by a member without the Operations role.
  8. Operations-role revocation while a payment is pending, followed by denied inquiry and removed UI access.
- Responsive/accessibility probes ran in the Operations journey at **390 × 844**: mobile navigation was visible, `document.documentElement.scrollWidth <= 390`, and Tab moved focus to an interactive element matching `:focus-visible`. The controls exercised by the browser use accessible names/labels and semantic roles.
- Screenshots were captured directly from the running browser after the real journeys; they were visually inspected at full resolution.

## Screenshot and source register

| Evidence | Dimensions | Capture point and source |
|---|---:|---|
| [`screenshots/corporate-payments-desktop.png`](screenshots/corporate-payments-desktop.png) | 1280 × 720 | Settled Maker/Checker payment; `frontend/e2e/enterprise-operations.spec.ts` and `frontend/src/pages/PaymentsPage.tsx` |
| [`screenshots/operations-console-desktop.png`](screenshots/operations-console-desktop.png) | 1280 × 720 | Assigned missing-callback exception with audit/event context; same E2E file and `frontend/src/pages/OperationsPage.tsx` |
| [`screenshots/operations-console-mobile.png`](screenshots/operations-console-mobile.png) | 390 × 844 | Mobile exception detail, visible keyboard focus, and bottom navigation; same E2E file and `frontend/src/components/BankLayout.tsx` |
| [`logs/playwright-e11-final.log`](logs/playwright-e11-final.log) | — | Final 8-test Chromium transcript; screenshot and viewport behavior came from the same run. |

The images are authentic Playwright screenshots, not generated or reconstructed. The operations capture shows an assigned exception awaiting a fresh clean scan; it does not imply that the exception was already resolved. The E2E assertion separately verifies that the exception resolves only after that scan.

## Local reproduction

With the dedicated PostgreSQL cluster and the bank/simulator processes running on the loopback ports recorded under `work/e11-lab/runs/20261115-fullstack/`:

```powershell
Set-Location D:\workshop\nov\ledgercore\frontend
npx openapi-typescript http://127.0.0.1:18096/v3/api-docs -o src/api/schema.d.ts
npm run lint
npm run build
$env:VITE_API_TARGET = 'http://127.0.0.1:18096'
npx playwright test
```

Backend and simulator commands/logs, database identities, and startup scripts are retained under the ignored local run directory. The portable browser transcript and genuine screenshots are included above. No persistent/default database was used.

## Limits

This is local Chromium and PostgreSQL integration evidence, not a production deployment. Accessibility review covered semantic controls, accessible names/labels, keyboard focus visibility, and one 390-pixel responsive viewport; it did not include a screen-reader audit, axe/WCAG automated scan, or broad device/browser matrix. The E2E suite covers the required end-user cases but is not a complete authorization matrix for every role × endpoint × callback/log path; E8 remains PARTIAL for that reason.
