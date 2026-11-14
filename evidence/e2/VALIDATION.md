# E2 — Enterprise Payroll Validation

## Scope and environment

- Round 1, E2, executed on `main` only. E1 starting revision: `f717753401cce8e9934a63758e340bae6d79a7f2`.
- Disposable PostgreSQL 18.7 cluster on `127.0.0.1:55434`, database `ledgercore_e1_test`; persistent port 55432 was not used.
- Temurin Java 21.0.13+9, Node 26.5.0, npm 12.0.1, Playwright 1.65.0 with Chromium.
- Every monetary assertion uses NZD minor units and existing ledger operations. The payroll module does not write account balances directly.

## Automated acceptance

| Check | Observed result |
|---|---|
| `backend/mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify` | Exit 0; 49 tests, 0 failures/errors/skips; total 59.809 s; Flyway validated six migrations on PostgreSQL 18.7 |
| `npm run lint` | Exit 0, no diagnostics |
| `npm run build` | Exit 0; TypeScript and Vite production build passed |
| `npm run test:e2e` | 3 Chromium tests passed in 18.3 s: personal banking regression, corporate maker transfer regression, and payroll workflow |
| `npm run generate:api` against the running V6 application | OpenAPI TypeScript contract generated successfully; schema changes are expected for the new payroll operations and models |
| `npm audit --registry=https://registry.npmjs.org --audit-level=high` | Exit 0; 6 findings (2 low, 4 moderate, 0 high/critical), matching the previously recorded dependency baseline |

`PayrollPostgresIntegrationTests` ran 4 tests in 15.03 s. The 500-item test itself took 10.709 s in the Surefire XML report.

## 500-item payroll and financial reconciliation

`PayrollPostgresIntegrationTests.processesAndReconcilesFiveHundredSyntheticEmployeesExactlyOnce` created 500 synthetic personal customers and accounts, a corporate payer, and a 500-item batch with NZ$6,000.00 total. The payer was funded through the existing simulated reserve ledger flow.

The test observed and asserted:

- Repeating submission returned the same saved content digest.
- The Maker could not approve the submitted batch; a direct database change to submitted item intent was rejected.
- Approval bound the batch to the submitted SHA-256 content digest; late item addition after approval was rejected.
- Two concurrent executions of the same item returned the same transfer receipt, with only one worker claiming it.
- A bounded 136-item execution plus a second execution completed all 500 items; a final repeated execution made no additional transfer.
- Final item state: 500 `SUCCEEDED`, 0 `PENDING`, 0 `FAILED`; 500 transfer rows and 500 payroll-scoped idempotency records.
- Each transfer had exactly two balanced postings. Independent account balance projections matched sums from authoritative postings for the payer and all 500 payees.
- Ending payer projection: 500,000 minor units (NZ$5,000.00); first employee projection: 101,000 minor units (NZ$1,010.00).

Additional PostgreSQL integration cases verified durable insufficient-funds failure without a transfer or overdraft, transaction rollback when writing the payroll result fails after transfer posting has begun, and a content-mutation race that waits on the approval lock and is rejected after approval.

## Real process interruption and restart

This was a separate run against the same disposable PostgreSQL cluster and the packaged application JAR. The opt-in `PayrollRecoveryFixtureHarness` created a separate 500-item, NZ$500.00 batch with 500 synthetic personal payees, independently approved it, and moved it to `PROCESSING`. It wrote its local-only credentials and IDs to the ignored file `work/e2/recovery-fixture.json`.

`evidence/e2/run-process-recovery.ps1` installed a test-only PostgreSQL trigger that paused the result-write transaction for eight seconds, started a real HTTP execution request, observed committed PostgreSQL item states, confirmed `pg_stat_activity.wait_event = 'PgSleep'` for the in-flight payroll result update, and force-terminated the Java server while that item transaction was open.

| Observation | Result |
|---|---:|
| App process killed while `PgSleep` was observed | PID 66872 |
| Items durably committed before kill | 3 |
| Database immediately after kill | 3 succeeded, 497 pending, 0 failed |
| In-flight HTTP request | Connection interruption observed (`HttpRequestException`) |
| App restarted | PID 67136; health returned `UP` |
| Recovery request | Fresh approver login; `limit=1000` |
| Final batch | `COMPLETED`; 500 succeeded, 0 pending, 0 failed; total 50,000 minor units |
| Final transfer / idempotency / payer-source counts | 500 / 500 / 500 |
| Unbalanced transfer count | 0 |
| Ledger-to-projection mismatches | 0 |
| Final payer balance | 50,000 minor units (NZ$500.00) |

The test-only trigger was dropped before the restart, and the experiment script stopped the restarted application after checking the final database state. No credentials are included in the repository evidence.

To reproduce, first run `PayrollRecoveryFixtureHarness` explicitly to create a fresh local-only fixture. Then start the packaged JAR on port 8080 with the runtime database variables pointed at the same disposable database and `LEDGERCORE_COOKIE_SECURE=false`, and run `evidence/e2/run-process-recovery.ps1` from the repository root. The script checks database identity and verifies that the port 8080 listener is the LedgerCore JAR before it terminates that process.

From the repository root, prepare the fixture with:

```powershell
$env:JAVA_HOME = (Resolve-Path 'work\toolchain\jdk21\jdk-21.0.13+9').Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55434/ledgercore_e1_test'
$env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_test'
$env:LEDGERCORE_TEST_DB_PASSWORD = ''
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend\pom.xml -Dtest=PayrollRecoveryFixtureHarness test
```

Start the packaged application in a second terminal with the matching runtime settings:

```powershell
$env:LEDGERCORE_DB_URL = 'jdbc:postgresql://127.0.0.1:55434/ledgercore_e1_test'
$env:LEDGERCORE_DB_USERNAME = 'ledgercore_test'
$env:LEDGERCORE_DB_PASSWORD = ''
$env:LEDGERCORE_COOKIE_SECURE = 'false'
java -jar backend\target\ledgercore-0.0.1-SNAPSHOT.jar
```

Then, from the repository root, run:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\evidence\e2\run-process-recovery.ps1
```

## Browser workflow and lost response

`frontend/e2e/payroll-flow.spec.ts` registered three separate customers, opened two personal payee accounts, created an organization and corporate payer, and assigned distinct `PAYROLL_MAKER` and `PAYROLL_APPROVER` roles. The Maker imported a two-row CSV, reviewed the items and NZ$60.00 total, and submitted it. A Maker self-approval API request returned HTTP 403. The independent Approver approved and executed the batch.

Playwright forwarded the execute request to the server, confirmed HTTP 200, and deliberately aborted the response to the browser. The UI reported the unconfirmed result, refreshed from durable state, and displayed `COMPLETED`, two successful item receipts, and NZ$60.00 without creating duplicate transfers.

## Limitations and handoff risks

- All identities, amounts, ledger entries, and counterparties were synthetic and used simulated funds. No external clearing or bank network exists in this stage.
- The process-kill delay is an injected PostgreSQL trigger used only to make the transaction boundary observable. It does not model machine power loss or every operating-system failure mode.
- The benchmark represents one local PostgreSQL instance and one 500-item payroll workload; E3 owns concurrency/performance experiments and their limits.
- Payroll executes synchronously in bounded HTTP requests. The item transaction and retry model is durable, but a production worker queue and external payment delivery semantics are outside Round 1 E2.
- Dependency findings were not suppressed: the audit remains at 2 low and 4 moderate findings, with no high or critical findings.
