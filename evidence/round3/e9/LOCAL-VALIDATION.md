# E9 Local Validation — Partial / in progress

## State

- Date: 2026-11-15 NZDT.
- Branch: `main`; instrumentation source commit `2dc8976136396afa871c1fee599327d0c0e2fbaf` is pushed and passed exact GitHub Actions run [43603381726](https://github.com/miku-wwl/ledgercore/actions/runs/43603381726). The measured V10 candidate was rejected. The forward-only V11 removal, test updates, and projection-read harness amendment are locally verified and await their direct-main commit and CI run.
- E9 remains **PARTIAL**: the supplementary R2-P2 projection measurement, W3C tracing spans, and actual PromQL rule-engine evaluation remain outstanding.
- All database writes for tests/experiments used named disposable databases in the isolated PostgreSQL 18.7 cluster on port 55438; synthetic funds and identities only.

## Observability implementation

- `/actuator/prometheus` exposes a Prometheus scrape and `/actuator/ledgercore` exposes the Operations-only local alert snapshot. Integration tests verify the live registry is Prometheus-backed, scraping includes the LedgerCore sample, and active Operations membership is required.
- Low-cardinality metrics cover transfer, payroll item/batch, approval delay, clearing, outbox, reconciliation, rollback, duplicate delivery, recovery action, Hikari acquisition/pending, and PostgreSQL lock-wait signals. No customer, account, organization, payment, payroll, beneficiary, correlation, or idempotency identifiers are metric tags.
- Database gauges are read on a separate raw JDBC connection so they remain observable during Hikari pool saturation. Hikari pending acquisitions and PostgreSQL lock wait sessions are independently induced and cleared in PostgreSQL integration tests.
- Structured JSON application logs preserve the validated `X-Correlation-ID` through HTTP, durable outbox dispatch, simulator request, and callbacks; worker MDC is restored after completion/failure. Sensitive request bodies, credentials, HMAC material, and exception messages are not emitted by these E9 logging paths.
- Alert thresholds and resolve behavior are covered by unit/integration fixtures, and the Prometheus-compatible rule file is `prometheus-alerts.yml`. A Prometheus `promtool` or live Prometheus rule evaluator was not run.
- Correlation is implemented; W3C `traceparent` propagation and exported/retained distributed spans are not. Do not describe this as end-to-end distributed tracing.

## Performance

- R2-P3 raw baseline and candidate measurements, source SHAs, environment JSON, all item samples, and plans are preserved under ignored `work/e9-lab/runs/20261115-r2p3-baseline/` and `work/e9-lab/runs/20261115-r2p3-candidate/`. The candidate test source commit `2dc8976` passed CI run `43603381726`.
- Candidate results did not satisfy the registered keep rule. At the final 5,000-item checkpoint, PostgreSQL still visited 5,000 ordered payroll items and probed the existing pending-result index 5,000 times; it did not use V10's `(batch_id, item_id) WHERE status='PENDING'` index. See `R2-P3-CANDIDATE-RESULTS.md` for medians and interpretation.
- Each of three candidate databases independently graded 4 completed batches, 6,500 successful results, 6,500 distinct transfers, zero unbalanced journal entries, and zero ledger/projection mismatches.
- Forward-only V11 drops the unused index on newly migrated databases and restores the earlier claim SQL. V1–V10 migration bytes are unchanged. Fresh-database backend `clean verify` on the V11 worktree: 94 tests, 0 failures/errors/skips, PostgreSQL 18.7, JDK 21.0.13; exact local run logs are in ignored `work/e9-lab/runs/20261115-v11-backend-final-r2/`.
- The separately registered R2-P2 supplement will record authoritative ledger reads, existing projection reads, transfer calls, and their plans for 100/1,000/10,000/100,000 posting histories. It is not yet run; the financial write path remains unchanged.

## Limits

- R2-P3 is a single-worker workload. Its measurements are local synthetic observations, not a banking SLO or capacity claim. The end-to-end service boundary includes commit; candidate code adds E9 metric timers, so the end-to-end timing delta cannot be attributed solely to the index. The plan evidence directly shows the candidate did not use that index.
- Full trace/span propagation, alert evaluation in a Prometheus rule engine, and the supplemental projection-read experiment remain required for E9 completion.
