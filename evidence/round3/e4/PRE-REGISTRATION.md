# E4 Event Reliability Pre-registration

- Registered: 2026-11-14, before E4 implementation/failure measurements.
- Starting source: `0db728171f7a28119ed4c4f19437896878f03fc6`.
- Status: EXECUTED — local evidence passes; direct-main implementation is committed; CI verification is pending.

## Question and prediction

Can the banking application persist business facts and one outbox event atomically, then deliver at least once across crashes/retries without a duplicate financial effect?

Prediction: rollback before commit leaves neither fact nor outbox row; commit followed by process loss leaves both; restart dispatches the existing event; repeated delivery is observable but consumer idempotency yields one effect; retry exhaustion is visible and manually recoverable.

## Workload and controls

Use a new disposable PostgreSQL database for each destructive scenario. Execute a personal transfer, an approved payroll item, and an independently approved corporate payment. Use stable event and business operation IDs. Capture database rows and journal postings with direct SQL before/after process restart. Never use a production/persistent database. Fault hooks must require an explicit test profile and be disabled by default.

## Required cases

Before commit; after commit before dispatch; dispatch failure; duplicate delivery; consumer crash and restart; concurrent publishers and consumers; retry exhaustion; pending event recovery after application restart.

## Verdict rules

PASS only if observed database state proves atomic event persistence, eventual retry/recovery, correct attribution and exactly one effective financial posting. A delivery exception alone is not a financial failure if the committed event recovers. Record unexecuted cases as NOT_RUN and any mismatch as FAIL; preserve raw logs and SQL output.

## Executed results (2026-11-14)

| Case | Result | Evidence |
|---|---|---|
| Transaction rollback before commit | PASS | A PostgreSQL trigger rejected `transfer.posted` outbox insertion; the integration test verified transfer, journal, postings, idempotency receipt, and projection all rolled back. |
| Commit, then process exit before dispatch | PASS | `clean verify` ran with the test scheduler disabled and left business outbox rows pending when its JVM exited. A fresh default-profile application JAR restarted against the same isolated database and dispatched the persisted rows. |
| Consumer side effect, crash before delivery acknowledgement | PASS — crash window simulated | The test committed the observer receipt, expired the processing lease, then reclaimed and redelivered the event. The durable receipt remained one row. The failure boundary was reconstructed using PostgreSQL lease state rather than killing the JVM at that exact instruction. |
| Concurrent publishers and consumers | PASS | Eight publisher threads wrote 24 unique event keys; four consumer threads delivered all 24, with 24 receipts. |
| Delivery failures and retry exhaustion | PASS | A controlled handler failure reached attempt 5 and `DEAD`; an authorized OPERATIONS actor retried it, and the retry audit row was verified. |
| Actor attribution | PASS | SQL assertions checked maker and approver identities on corporate payment events and the releasing approver identity on 500 payroll item events. |
| Application restart recovery and no ledger side effect | PASS | On `ledgercore_round3_e4_final`, 1,503 pending rows were drained after application restart. All 1,529 rows had receipts; the approved payment event was delivered once (attempt 1), and the 1,241 journal-entry count did not change. The subsequent three browser journeys created 16 more outbox events; a second application start delivered those too. Final state: 1,545/1,545 delivered and receipted; 1,255 journal entries. |

The backend `clean verify` result on `ledgercore_round3_e4_final` was 78 tests, 0 failures, 0 errors, 0 skipped, 2:05. Frontend lint and production build passed; Playwright passed all 3 existing journeys. Detailed output is in `LOCAL-VALIDATION.md` and the adjacent `.txt` / `.log` evidence files.

E4 local verdict: **PASS**. GitHub Actions for the implementation commit remains pending. The default consumer in this stage records an idempotent durable receipt; it does not connect to an external clearing system. E5 will implement that separate boundary.
