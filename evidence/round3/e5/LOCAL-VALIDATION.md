# E5 Local Validation — Partial

- Date: 2026-11-14 (NZDT).
- Branch: `main`; implementation was performed directly on `main` from `69d40320065814596505650ee20350d282d7468c`. No branch, worktree, PR, or history rewrite was used.
- Status: **PARTIAL; CI passed**. Local code, isolated database, and process evidence are recorded here. E5 remains partial because the live reserve-depletion case remains unexercised.
- The E5 pre-registration in `PRE-REGISTRATION.md` was left unchanged.

## Isolated processes and databases

The bank and simulator ran as separate Java processes against separate PostgreSQL 18.7 databases on the isolated `127.0.0.1:55436` cluster. The persistent PostgreSQL service on port 55432 was not used.

| Component | Process | Port | Database |
|---|---:|---:|---|
| LedgerCore bank | PID 15488 | 8095 | `ledgercore_round3_e5_process_bank_20261114_2150` |
| Clearing simulator, first run | PID 12804 | 8096 | `ledgercore_round3_e5_process_sim_20261114_2150` |
| Clearing simulator, after restart | PID 47380 | 8096 | same simulator database |

The simulator was stopped and restarted while one operation was still pending. The inquiry after restart reused the same operation identity and settled it. Both processes were stopped after the exercise; ports 8095, 8096, and 8080 had no listener at the end of local validation. Raw logs and synthetic login details remain under the ignored `work/e5-process-run/` directory and are not committed.

## Two-database process result

- `SIM-UNKNOWN` payment: bank first retained the booked obligation as `UNKNOWN`; an authorized operations inquiry for the same operation returned `SETTLED`.
- `SIM-PENDING` payment: bank received `PENDING`; the simulator was independently restarted; an inquiry for the same operation then returned `SETTLED`.
- Bank SQL showed both payments `COMPLETED/SETTLED`, each with one booking and one settlement journal entry. Callback sequences were `1:UNKNOWN,2:SETTLED` and `1:PENDING,2:SETTLED`.
- Both booking entries had two postings with total debits equal to total credits (2,000 minor units across the two entries). Both settlement entries also had two postings with matching total debits and credits (2,000 minor units).
- The bank callback-accepted outbox had 4/4 events delivered; 2/2 clearing-instruction events were delivered. The independent simulator database contained two operations, both `SETTLED`, and four callback deliveries. The callback attempt count totaled four across four rows (one attempt per callback).
- An independent projection-versus-posting SQL check found zero mismatches. These queries read each database separately; no cross-database join was used.

## Local build and database tests

- Bank: `backend/mvnw.cmd -f backend/pom.xml clean verify`, Java 21.0.13, fresh isolated database `ledgercore_round3_e5_ci_bank_20261114_2220` — **81 tests passed; 0 failures, 0 errors, 0 skipped**. PostgreSQL applied V1–V9 on the fresh schema. The migration compatibility suite includes a populated V8-to-V9 upgrade and forward-only outcome checks.
- Simulator: `backend/mvnw.cmd -f clearing-simulator/pom.xml clean verify`, Java 21.0.13, fresh isolated database `ledgercore_round3_e5_sim_full_20261114_2206` — **4 tests passed; 0 failures, 0 errors, 0 skipped**. PostgreSQL applied the independent simulator V1 migration.
- Focused bank callback, organization, and reserve-failure run — **3 tests passed**.
- `npm run generate:api` passed against the local bank process with clearing enabled and a local test-only HMAC secret. The generated contract contains typed bank callback and operations-inquiry endpoints. CI's contract-check process now uses the same enabled setting and a CI-only test secret.
- `npm run lint` and `npm run build` passed; TypeScript compilation and Vite production build completed successfully.
- `git diff --check` passed. A comparison against accepted baseline `0db728171f7a28119ed4c4f19437896878f03fc6` confirmed V1–V7 migration files are unchanged; E5 uses forward migration V9.
- An initial bank test invocation without `LEDGERCORE_TEST_DB_URL` failed fast as configured. No database writes occurred in that invocation. The successful verification used the explicitly named disposable database above. A separate attempt to pass Maven `-D` switches through PowerShell was rejected by Maven argument parsing; the final successful full-suite commands used no such switches.

## Callback and financial cases covered locally

| Case | Result |
|---|---|
| Approved operation dispatch, same operation ID and digest | PASS in separate-process run |
| Duplicate delivery and exact callback replay | PASS; one effective booking/settlement |
| Simulator process restart with a pending operation | PASS; state recovered from simulator database |
| Settled callback | PASS; balanced settlement and projection reconciliation |
| Definitive rejection before settlement | PASS in PostgreSQL integration test; one balanced refund |
| UNKNOWN followed by same-operation inquiry and settlement | PASS in separate-process run and integration test |
| Invalid MAC and expired timestamp | PASS; HTTP 401 integration assertions |
| Wrong intent digest, out-of-order sequence, conflicting sequence, and altered callback ID reuse | PASS; exceptions recorded and no second financial effect |
| Rejection after settlement | PASS; illegal transition recorded, no refund posted |
| Reserve-insufficient handling | **PARTIAL**; a test injects `InsufficientLedgerBalanceException` at the financial-effect boundary, verifies the obligation stays booked, the reserve projection and journals do not change, an open CRITICAL exception is recorded, and the exact callback can retry. A naturally underfunded reserve was not produced: the current ledger funding/settlement model has no separate business operation that drains simulation liquidity while leaving the payable funded. No projection edit or ad hoc journal row was used to manufacture that state. |
| GitHub Actions | **PASS** — [run 43580338058](https://github.com/miku-wwl/ledgercore/actions/runs/43580338058), exact head `40ab1343cd4e107b7d2bfb5a57931e97efeb5758`; verify and OSV jobs succeeded, including backend/simulator tests, OpenAPI contract, and browser acceptance |

E6–E11 have not been started in this E5 evidence record. No E12, textbook, or final PDF work is included.
