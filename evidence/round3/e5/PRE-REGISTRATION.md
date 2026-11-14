# E5 Clearing Simulator and Callback Reliability Pre-registration

- Registered: 2026-11-14, before E5 implementation and outcome measurements.
- Starting main commit: `4ac391ea96987a56996818fad6f7e16bd9ca2113` (E4 documentation update; E4 implementation is validated at `0dfcb0c`).
- Status: PRE-REGISTERED; implementation and experiments are NOT_RUN.

## Question and predictions

Can an independently restartable, synthetic clearing simulator persist an operation in its own PostgreSQL database, accept the same stable operation identity at least once, and return authenticated callbacks that the bank applies without duplicate or unbalanced postings?

Predictions:

1. The bank books the approved payment once to `CLEARING_PAYABLE` before any simulator dispatch. Dispatch failure, restart, timeout, or callback replay never creates a second booking.
2. The simulator stores the operation under the persisted `operation_id` and immutable approval digest. A repeated request with the same identity and digest returns the existing operation; reuse with different facts is rejected.
3. The bank accepts only a valid HMAC-SHA-256 callback signed with the configured simulator secret. Invalid signatures, stale timestamps, wrong operation/digest, altered duplicate callback IDs, and conflicting sequence numbers cause no ledger effect.
4. A definitive pre-settlement rejection writes exactly one balanced refund from `CLEARING_PAYABLE` to the customer deposit. Settlement writes exactly one balanced journal from `CLEARING_PAYABLE` to `SIMULATION_RESERVE`, only when the reserve can fund it. Insufficient reserve never makes the reserve negative or releases the customer obligation.
5. An UNKNOWN/timeout keeps the original operation identity and booked amount. Reconciliation queries the same persisted simulator operation; it does not create a new financial operation. A later authoritative result produces at most one settlement or refund.
6. A separate simulator process restart recovers its saved operation and callback-delivery state from the simulator database. The bank callback inbox and simulator operation store remain independently queryable.

## Isolated setup and controls

- Use two new databases on the isolated PostgreSQL 18.7 cluster: one banking database and one simulator database, each with its own JDBC URL and database owner. Do not use the persistent PostgreSQL service on port 55432.
- Run the simulator as a separate executable process with a separate connection pool. Start, stop, and restart it independently from the banking JAR. Capture process IDs, application logs, exact commands, database names, and before/after SQL snapshots.
- Use only synthetic people, organizations, accounts, routes, and payments. The simulator's fictional routing catalogue selects deterministic outcomes such as settled, rejected, and an UNKNOWN-then-inquiry result; the banking API must not accept a caller-supplied outcome or bypass callback verification.
- Use one stable payment/clearing `operation_id`, intent digest, callback ID, and monotonically increasing callback sequence. Sign the callback timestamp and exact raw request body with HMAC-SHA-256; reject timestamps outside the configured short skew window.
- Fund the simulation reserve only through balanced ledger funding entries. Never modify ledger projections directly, insert financial journal rows with ad hoc SQL, contact a real bank, move real money, or use paid cloud resources.

## Required cases

| Case | Required observation |
|---|---|
| Dispatch of an approved operation | One simulator operation, same operation ID and digest as the bank instruction; booking precedes dispatch |
| Duplicate dispatch / process restart | One simulator operation and one effective bank booking; retries return persisted state |
| Settled callback | Valid signature and digest; one `CLEARING_SETTLEMENT`; balanced postings; reserve decreases only by the payment amount |
| Definitive rejection before settlement | One `CORPORATE_PAYMENT_REFUND`; customer deposit restored once; no settlement entry |
| Unknown outcome and later inquiry | Payment remains booked and does not create a new operation; later authenticated resolution produces one terminal journal |
| Insufficient simulation reserve | Settlement is refused; no negative reserve, projection edit, or duplicate booking; retry/reconciliation uses the same operation |
| Callback security and replay | Invalid HMAC/timestamp/digest rejected; exact duplicate idempotent; altered duplicate and stale/out-of-order callback create no second posting |
| Rejection after settlement | No automatic refund; preserve the settled ledger and record the contradictory callback for controlled review |
| Independent persistence | Stop and restart simulator; SQL proves the operation survives in the simulator DB and callback/instruction facts survive in the bank DB |
| Independent reconciliation | Direct SQL balances, account projections, operation identity, callback inbox, and journal references agree across both databases |

## Verdict rules

PASS only when the two databases and separate process boundary are proven, every executed result is authenticated and attributable, all ledger entries balance and projections reconcile independently, and retries/restarts preserve one operation identity without duplicate financial effects. Record each unexecuted case as NOT_RUN; record any mismatch as FAIL; retain raw logs and SQL output. No E6 fault-injection controls or E7 exception-resolution workflow are claimed by this E5 registration.
