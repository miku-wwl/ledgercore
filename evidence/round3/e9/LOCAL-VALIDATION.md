# E9 Local Validation — Partial / in progress

## State

- Date: 2026-11-15 NZDT.
- Branch: `main`; instrumentation source commit `2dc8976136396afa871c1fee599327d0c0e2fbaf` passed exact GitHub Actions run [43603381726](https://github.com/miku-wwl/ledgercore/actions/runs/43603381726). The V10 candidate was rejected; forward-only V11 cleanup commit `cb2601f103d765e1c43221d7d5d34e59bb4551cf` passed exact run [43605692774](https://github.com/miku-wwl/ledgercore/actions/runs/43605692774). Its 94-test fresh-database `clean verify` passed locally. The projection supplement and Prometheus rule-engine checks below ran against `cb2601f`.
- E9 is **PARTIAL**: R2-P2 projection-read measurements and Prometheus `promtool` rule evaluation passed. W3C `traceparent` propagation and exported/retained distributed spans remain unimplemented, so the tracing criterion is not satisfied.
- All database writes for tests/experiments used named disposable databases in the isolated PostgreSQL 18.7 cluster on port 55438; synthetic funds and identities only.

## Observability implementation

- `/actuator/prometheus` exposes a Prometheus scrape and `/actuator/ledgercore` exposes the Operations-only local alert snapshot. Integration tests verify the live registry is Prometheus-backed, scraping includes the LedgerCore sample, and active Operations membership is required.
- Low-cardinality metrics cover transfer, payroll item/batch, approval delay, clearing, outbox, reconciliation, rollback, duplicate delivery, recovery action, Hikari acquisition/pending, and PostgreSQL lock-wait signals. No customer, account, organization, payment, payroll, beneficiary, correlation, or idempotency identifiers are metric tags.
- Database gauges are read on a separate raw JDBC connection so they remain observable during Hikari pool saturation. Hikari pending acquisitions and PostgreSQL lock wait sessions are independently induced and cleared in PostgreSQL integration tests.
- Structured JSON application logs preserve the validated `X-Correlation-ID` through HTTP, durable outbox dispatch, simulator request, and callbacks; worker MDC is restored after completion/failure. Sensitive request bodies, credentials, HMAC material, and exception messages are not emitted by these E9 logging paths.
- Alert thresholds and resolve behavior are covered by unit/integration fixtures and `evidence/round3/e9/prometheus-alerts-test.yml`. Official Prometheus `promtool` 3.13.3 (Windows amd64) evaluated the five rules: all five fired at 1m and all five cleared at 2m. The release ZIP SHA-256 was verified as `acae8f2d71baab1acac7064a5af29c40ccb9843c1e7e32baa3d5c476ae7978c9`; the binary and archive are in ignored `work/e9-lab/tools/`. Command: `promtool.exe test rules evidence/round3/e9/prometheus-alerts-test.yml`. These are synthetic rule fixtures, not a running Prometheus server or external alert delivery test.
- Correlation is implemented; W3C `traceparent` propagation and exported/retained distributed spans are not. Do not describe this as end-to-end distributed tracing.

## Performance

- R2-P3 raw baseline and candidate measurements, source SHAs, environment JSON, all item samples, and plans are preserved under ignored `work/e9-lab/runs/20261115-r2p3-baseline/` and `work/e9-lab/runs/20261115-r2p3-candidate/`. The candidate test source commit `2dc8976` passed CI run `43603381726`.
- Candidate results did not satisfy the registered keep rule. At the final 5,000-item checkpoint, PostgreSQL still visited 5,000 ordered payroll items and probed the existing pending-result index 5,000 times; it did not use V10's `(batch_id, item_id) WHERE status='PENDING'` index. See `R2-P3-CANDIDATE-RESULTS.md` for medians and interpretation.
- Each of three candidate databases independently graded 4 completed batches, 6,500 successful results, 6,500 distinct transfers, zero unbalanced journal entries, and zero ledger/projection mismatches.
- Forward-only V11 drops the unused index on newly migrated databases and restores the earlier claim SQL. V1–V10 migration bytes are unchanged. Fresh-database backend `clean verify` on the V11 worktree: 94 tests, 0 failures/errors/skips, PostgreSQL 18.7, JDK 21.0.13; exact local run logs are in ignored `work/e9-lab/runs/20261115-v11-backend-final-r2/`.
- The separately registered R2-P2 supplement completed against source `cb2601f` on a fresh database: 12 history/repeat fixtures, 1 test passed, zero projection mismatches. At 100,000 postings, authoritative SQL p50/p95 was 39.165/51.389 ms, transfer service p50/p95 was 90.393/105.139 ms, and read-only projection p50/p95 was 0.330/0.558 ms. The projection plan scanned 22 rows in 0.059 ms. Full results and interpretation are in `R2-P2-PROJECTION-RESULTS.md`; the write path remains unchanged and R2-10 remains unresolved.

## Limits

- R2-P3 is a single-worker workload. Its measurements are local synthetic observations, not a banking SLO or capacity claim. The end-to-end service boundary includes commit; candidate code adds E9 metric timers, so the end-to-end timing delta cannot be attributed solely to the index. The plan evidence directly shows the candidate did not use that index.
- W3C trace/span propagation remains required for E9 completion. The R2-P2 projection read is a small-table, read-only observation; it is not a safe projection-write candidate. Alert rule evaluation passed only against synthetic fixtures; no Prometheus server or notification integration was run.
