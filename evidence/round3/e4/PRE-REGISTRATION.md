# E4 Event Reliability Pre-registration

- Registered: 2026-11-14, before E4 implementation/failure measurements.
- Starting source: `0db728171f7a28119ed4c4f19437896878f03fc6`.
- Status: pre-registered; no E4 experiment has run.

## Question and prediction

Can the banking application persist business facts and one outbox event atomically, then deliver at least once across crashes/retries without a duplicate financial effect?

Prediction: rollback before commit leaves neither fact nor outbox row; commit followed by process loss leaves both; restart dispatches the existing event; repeated delivery is observable but consumer idempotency yields one effect; retry exhaustion is visible and manually recoverable.

## Workload and controls

Use a new disposable PostgreSQL database for each destructive scenario. Execute a personal transfer, an approved payroll item, and an independently approved corporate payment. Use stable event and business operation IDs. Capture database rows and journal postings with direct SQL before/after process restart. Never use a production/persistent database. Fault hooks must require an explicit test profile and be disabled by default.

## Required cases

Before commit; after commit before dispatch; dispatch failure; duplicate delivery; consumer crash and restart; concurrent publishers and consumers; retry exhaustion; pending event recovery after application restart.

## Verdict rules

PASS only if observed database state proves atomic event persistence, eventual retry/recovery, correct attribution and exactly one effective financial posting. A delivery exception alone is not a financial failure if the committed event recovers. Record unexecuted cases as NOT_RUN and any mismatch as FAIL; preserve raw logs and SQL output.
