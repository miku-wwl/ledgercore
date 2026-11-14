# E1 Local Validation

Date: 2026-11-14 (Pacific/Auckland)

E1 adds organization membership and role authorization, corporate account ownership, source-owner audit columns for transfers, and the minimal organization and corporate-transfer UI. All test identities and balances were synthetic. PostgreSQL 18.7 ran in a disposable local cluster on `127.0.0.1:55434`, using database `ledgercore_e1_test`; no production database or payment rail was involved.

## Results

| Gate | Result |
|---|---|
| PostgreSQL migration | V1–V5 validated; V5 applied to `ledgercore_e1_test` |
| Backend | `mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify` — 44 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS (44.444 s) |
| Frontend lint | `npm run lint` — passed |
| Frontend production build | `npm run build` — TypeScript and Vite build passed |
| OpenAPI | Generated `frontend/src/api/schema.d.ts` from the running V5 application; organization operations and request/response schemas are present |
| Browser | `npm run test:e2e` — 2 Chromium tests passed (17.7 s); the new test creates an organization, adds a `PAYROLL_MAKER`, and posts a corporate-source transfer |
| Diff hygiene | `git diff --check` — passed |

The PostgreSQL integration test verifies owner XOR enforcement, personal and corporate owner persistence, active-member account visibility, cross-organization 404 behavior, viewer/admin debit denial, Maker/Checker identity policy, corporate debit/credit balances, and use of the existing ledger path. The browser test covers the administrator-to-maker UI path. Existing backend and browser regression suites also passed.

## Reproduction environment

Set `JAVA_HOME` to Temurin 21.0.13+9. Maven integration tests read the `LEDGERCORE_TEST_DB_*` variables from `backend/src/main/resources/application-test.properties`:

```powershell
$env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55434/ledgercore_e1_test'
$env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_test'
$env:LEDGERCORE_TEST_DB_PASSWORD = ''
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify
```

For OpenAPI generation and browser tests, run the application on port 8080 with `LEDGERCORE_DB_URL`, `LEDGERCORE_DB_USERNAME`, `LEDGERCORE_DB_PASSWORD`, and `LEDGERCORE_COOKIE_SECURE=false`, then run `npm run generate:api`, `npm run lint`, `npm run build`, and `npm run test:e2e` in `frontend/`.

## Validation notes

- The first Maven attempt set the runtime `LEDGERCORE_DB_*` variables only. The test profile uses `LEDGERCORE_TEST_DB_*`, so that invocation failed during Spring context setup before integration methods ran. Re-running with the profile-specific variables above passed all 44 tests.
- The first run of the new browser test exposed an ambiguous accessible-name locator for the role select. The locator was narrowed to the exact label and the new test then passed on rerun; the final full browser suite passed both tests.
- Local validation is complete; direct-main push and the corresponding GitHub Actions run remain pending. E1 will not be marked `VALIDATED` until CI is verified.
