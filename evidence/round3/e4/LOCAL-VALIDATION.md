# E4 Local Validation

- Date: 2026-11-14 (Pacific/Auckland).
- Repository: `D:\workshop\nov\ledgercore`, branch `main`.
- Source baseline: Round 3 accepted commit `0db728171f7a28119ed4c4f19437896878f03fc6`; Stage 0 plan commit `869585e4421af450f477ba174a53eb5225715d6f`.
- Implementation commit: `0dfcb0c6afddb4bc10af98aa16eba4764a6eb02a`, pushed directly to `main`.
- Database: isolated PostgreSQL 18.7 on `127.0.0.1:55436`; persistent PostgreSQL on 55432 was not used.
- Result: **E4 VALIDATED.** GitHub Actions run [`43574289360`](https://github.com/miku-wwl/ledgercore/actions/runs/43574289360) passed both full-stack acceptance and OSV dependency scan for the implementation SHA.

## Backend regression and database constraints

`./backend/mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify` ran on a freshly created `ledgercore_round3_e4_final`, owned by `ledgercore_r2`, using Java 21.0.13 and the repository-local Maven cache. Result: **78 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS; 2:05**. Per-suite Surefire counts are in `backend-surefire-summary.txt`; the exact environment and command are in `backend-command-and-result.txt`.

The run applied V1–V8. `MigrationCompatibilityPostgresIntegrationTests` passed its seven fresh, older-schema upgrade, checksum, and refusal cases. A final comparison against the Round 3 starting commit found no changes in V1–V7. The independent rollback test injected a PostgreSQL trigger failure on outbox insertion and confirmed the transfer, journal entry, postings, idempotency row, and projection all remained unchanged.

The outbox integration suite has three tests. It verifies expired-lease replay after a consumer receipt has committed, one idempotent receipt, immutable event facts, five failed attempts to `DEAD`, audited OPERATIONS recovery, concurrent publishers/dispatchers, and whole-transfer rollback if event persistence fails. An eight-thread publisher race wrote 24 distinct event keys; four dispatcher threads delivered all 24 and recorded 24 receipts.

## Application restart experiment

The full Maven test run used the test profile, which disables scheduled dispatch. Its committed business events remained pending after the test JVM exited. A separately started default-profile application JAR connected to the same isolated database and drained those persisted rows:

- Before restart: 1,503 pending rows; the approved corporate payment event was `PENDING`, attempt 0; 1,241 journal entries.
- After restart: no pending or processing rows; the approved payment event was `DELIVERED`, attempt 1, with one `durable-fact-observer-v1` receipt; 1,241 journal entries.
- The test data contained 1,529 events and 1,529 receipts after this restart; none lacked an observer receipt.

The app logs and SQL snapshots are `verified-restart-app.stdout.log`, `verified-restart-app.stderr.log`, `verified-restart-before.txt`, `verified-restart-after.txt`, and `verified-restart-sql-summary.txt`. The consumer records receipts only; it does not post money. The journal count is direct SQL evidence that replay did not cause another financial posting.

The subsequent browser tests created 16 additional durable events while the local app ran with its test dispatcher disabled. A second normal application start drained those events. `post-e2e-drain-before.txt`, `post-e2e-drain-after.txt`, the corresponding app logs, and `database-final-state.txt` show the final isolated database at V8 with **1,545 delivered events, 1,545 receipts, zero events missing a receipt, and 1,255 journal entries**. Journal entries increased from 1,241 to 1,255 during the browser journeys themselves (account opening and transfers); the second outbox drain left that count unchanged.

The consumer-side-effect/acknowledgement crash boundary is reconstructed in the integration test by committing its receipt and expiring the persisted lease before redelivery; the JVM was not killed at that exact instruction. The full application process restart separately proves pending-event recovery.

## API contract and browser regression

The application generated `frontend/src/api/schema.d.ts` from its live OpenAPI document. The generated contract includes the new payment and operations routes. `npm run lint` passed; `npm run build` passed (TypeScript plus Vite production build). `npm run test:e2e` passed all three existing Chromium journeys: personal banking, payroll Maker/Checker with lost-response recovery, and the organization flow. The organization browser check now confirms that a corporate account is not offered as a source for a generic transfer under Policy B. Results are summarized in `frontend-command-and-result.txt` and `playwright-summary.txt`.

## Failed setup attempts and resolved findings

- The first full-run attempt targeted `ledgercore_round3_e4_actor` before that database existed. PostgreSQL returned `FATAL: database ... does not exist`; test contexts consequently failed to start. Creating the database explicitly as `postgres`, owned by `ledgercore_r2`, resolved the environment issue. It was not an application test failure.
- The first application-context attempt expected a Jackson 2 `ObjectMapper` bean. Spring Boot 4 provides Jackson 3 here; the event store now uses Jackson 3 `JsonMapper`.
- The first dead-letter recovery test found that immutable attempt-count enforcement prevented manual retry reset. V8 now allows the reset only for `DEAD -> PENDING`.
- The initial approval trigger rejected the first approval metadata write after submission. It now allows that one state transition while continuing to reject changes to the submitted payment facts and any later approval mutation.
- Historical migration balance fingerprinting initially counted the new zero-balance clearing system account. The comparison now excludes that newly introduced account while still checking customer accounts and historical postings.

No E5 clearing simulator, E6 fault lab, E7 reconciliation, E8 independent authorization matrix, E9 performance run, E10 backup/restore, E11 operations console, E12, textbook, or final PDF work is claimed by this evidence.
