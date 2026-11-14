# E6 case inventory and scope

## Executed end to end in the E6 lab

| Case | Injection and independent evidence | Recovery | Result |
|---|---|---|---|
| Crash before financial commit | `TRANSFER_PRE_COMMIT`; exact application PID killed at a persisted checkpoint. SQL: zero transfer, idempotency receipt and outbox row; both balances unchanged; zero unbalanced entries, negative projections and projection mismatches. | Restart and same key create one transfer; one balanced posting; one delivered event. | PASS |
| Crash after commit before response | `TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE`; exact application PID killed after the proxied transaction committed. SQL: one transfer/receipt/event, `PENDING:0`; balances 99000/101000 minor. | Restart and replay returns the checkpoint transfer id; no duplicate; event delivered. | PASS |
| Crash after event persistence before dispatch | Scheduler disabled. The transfer returns HTTP 200 after the durable checkpoint; then the exact app process is killed. SQL independently observes one transfer and one pending event with zero attempts. | Restart with dispatcher enabled; replay returns same transfer id; event delivered. | PASS |

Run JSON and logs are preserved under `work/e6-lab/runs/` (ignored local evidence). See `LOCAL-VALIDATION.md` for exact run ids and stage counts.

## Implemented hook, not yet executed

| Case | Status | Limitation |
|---|---|---|
| Crash between transfer rollback and `markItemFailed` | NOT RUN | A profile-gated `PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK` checkpoint exists. The current end-to-end harness does not create and approve a payroll batch fixture or resume its worker. |

## Not executed by the E6 lab

| Fault category | Status and related evidence |
|---|---|
| Duplicate clearing callback | Not injected by E6. E5 integration and two-process evidence cover exact callback replay and one effective booking/settlement: `evidence/round3/e5/LOCAL-VALIDATION.md`. |
| Crash during external clearing; clearing timeout before or after simulator acceptance | Not injected by E6. E5 covers PENDING/UNKNOWN inquiry, simulator restart and recovery, but not these named timeouts or a process kill at every clearing boundary. |
| Database connection interruption | Not run. |
| PostgreSQL lock contention and transaction timeout | Not run. |
| Worker interruption at other payroll points | Not run. |
| Controlled migration failure in an isolated schema | Not run. |
| UNKNOWN clearing outcome inquiry/recovery | E5 PASS across separate bank/simulator processes and databases; not rerun by E6. |
| Unauthorized approval / payroll overdraft | Not in the three E6 customer-transfer fixtures. Existing E5/E8 evidence must be considered separately; these E6 verdicts make no approval-authorization claim. |

## Grader scope

The E6 SQL grader independently checks transfer and receipt counts, receipt success, outbox count/state/attempts, journal balance, projection-to-posting agreement, negative projected balances, and the two fixture balances. It proves only the synthetic personal-transfer path in these three cases. It does not grade clearing simulator tables, corporate approval state, payroll results, audit actor correctness, reserve liquidity, or migration state. Those remain outside the executed E6 coverage.
