# E6 local validation — Partial

- Date: 2026-11-14 (NZDT).
- Branch: `main`; all source and evidence-document changes are made directly on `main`.
- The E6 pre-registration was recorded before any E6 fault run and remains unchanged.
- Status: **PARTIAL**. Three isolated crash/outbox cases passed, but the full preregistered fault inventory has not been executed.

## Environment and safety

- PostgreSQL 18.7 cluster is created per run under ignored `work/e6-lab/runs/<RunId>/postgres-data`, listening only on `127.0.0.1:55437`.
- Each run receives a unique `ledgercore_e6_*` database and `e6_*` database-owner role. LedgerCore runs at `127.0.0.1:18095`.
- Fixtures are created through the actual registration, account and transfer APIs. Both accounts begin with the application’s synthetic 100000-minor-unit opening balance; each transfer is 1000 minor units.
- The harness checks database name, database role, server address/port, app jar, Java process PID and listener ownership before injection or stop. `55432` was not touched.
- Fault hooks are absent outside the explicit `e6-lab` profile and enabled-property/confirmation/database/run-directory gates. A backend application-context test also asserts no hook bean under the ordinary `test` profile.
- `restore` preserves the exact run database files, checkpoints, JSON verdicts and process logs; only the case-owned app and PostgreSQL process are stopped.

## Executed runs

| Case | Run id | After-injection SQL verdict | Recovery verdict | Key independent result |
|---|---|---|---|---|
| Pre-commit crash | `d848f7a3-14f5-4261-a6cc-875422f2e0ef` | PASS (`grade-after-injection.json`) | PASS (`restore.json`) | Before recovery: 0 transfers/receipts/outbox, source and target 100000/100000. Replay created one new transfer (different ID from the rolled-back checkpoint), one delivered event, 99000/101000, zero unbalanced entries and projection mismatches. |
| Post-commit / pre-response crash | `6aeb34c8-1a4d-4133-afb0-8de0a33e1f11` | PASS | PASS | Before recovery: one transfer and receipt, outbox `PENDING:0`; replay returned the checkpoint transfer id. Final outbox `DELIVERED:1`; one effective transfer. |
| Persisted outbox / pre-dispatch crash | `40047eba-8071-4681-85af-8c28a7e5ec2a` | PASS | PASS | HTTP 200 was received before killing the app; SQL then showed one transfer and `PENDING:0`. Recovery replay returned the same transfer id and delivered the event. |

Machine-readable per-run artifacts, app stdout/stderr, checkpoint and PostgreSQL logs are in each ignored `work/e6-lab/runs/<RunId>/` directory. An earlier pre-commit attempt exposed and preserved a grader SQL ambiguity; a subsequent run passed independent grading and recovery. Failed setup attempts are also retained as `NOT_INJECTED`/`INJECT_FAILED` artifacts and are not counted as passes.

## Regression

An initial backend `clean verify` on fresh database `ledgercore_round3_e6_tests_20261114_2310` ran 82 tests with 0 assertion failures and 0 skips, but the final Modulith architecture check rejected a new dependency from `transfers`/`payroll` to `faultlab`. The shared hook interface was moved to the already permitted `api` boundary; no financial code or migration changed for this fix.

Post-fix backend `clean verify` passed on fresh disposable database `ledgercore_round3_e6_tests_20261114_2315`, PostgreSQL 18.7 on port 55436. Command: `mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify`; Java 21.0.13; elapsed 2:13; **82 tests, 0 failures, 0 errors, 0 skipped**. This includes Modulith architecture verification and the test asserting that no E6 hook bean is present under the ordinary `test` profile. The initial architecture failure was corrected by moving the shared hook interface to the permitted `api` boundary; this passing run is after that fix.

## Limitations

Only the three cases above have a complete `inject` → independent SQL `grade` → `restore` recovery sequence. The payroll failure-mark gap has a guarded hook but no lab fixture. Clearing callback/UNKNOWN/restart evidence belongs to E5 and does not imply E6 timeout, database interruption, PostgreSQL lock/timeout, worker-kill, or migration-failure coverage. The implementation commit `e7c2bafb8b0eedf10d725c63568e2dc52529ea19` was pushed directly to `main`; GitHub Actions run [43584590857](https://github.com/miku-wwl/ledgercore/actions/runs/43584590857) passed the full-stack acceptance and OSV jobs. E6 remains PARTIAL because the broader preregistered fault inventory is unexecuted. See `CASE-INVENTORY.md`.
