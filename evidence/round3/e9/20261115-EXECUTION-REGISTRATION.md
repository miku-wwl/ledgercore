# E9 Observability and Capacity Execution Registration

## Registration state

- Registered: 2026-11-15 NZDT, on `main`, before any Round 3 capacity measurements.
- Current source baseline: `1f65e8b110c1b9414b22848015d983c96cf1b144` (same application source as the E8 implementation commit; this later commit only records E8's exact CI result and residual risks).
- E8 gate: implementation commit `3a1beed048a878077865d571d50dac0060470e87`, exact GitHub Actions run `43596797609`, full-stack acceptance and Maven OSV passed.
- Existing E9/R2-10/R2-12 registration is preserved at `PERFORMANCE-PRE-REGISTRATION.md`. This supplement pins the current Round 3 source and concrete instrumentation/measurement protocol; it does not change the original decision rules.
- Status: **REGISTERED; no E9 measurement has run yet.**

## Instrumentation plan

Use Spring Boot Actuator and Micrometer with a Prometheus registry as the smallest local metrics surface. Keep JSON logs already configured by the application. No hosted telemetry, Grafana stack, real bank, or production service is part of this stage.

Metric names will be low-cardinality. Tags may contain only fixed outcomes, operation classes, and event types; never customer, account, organization, payment, payroll, correlation, idempotency, beneficiary, or secret values.

| Required signal | Planned source and unit |
|---|---|
| Transfer duration | `TransferService` call duration in seconds; bounded outcome tag. |
| Payroll item and batch duration | One item transaction and one batch execution/resume invocation in seconds. |
| Corporate approval delay | Persisted `submitted_at` to successful approval in seconds. |
| Event publication backlog and retry | Database-derived pending/processing/dead counts and oldest-pending age; retry counter from durable delivery transitions. |
| Clearing delay and UNKNOWN | Persisted instruction-to-terminal callback duration; database-derived UNKNOWN count. |
| Reconciliation exceptions | Database-derived open exception count. |
| Pool acquisition | Hikari acquisition timer/pending-connection metrics; report separately from PostgreSQL locks. |
| PostgreSQL lock waiting | A database-derived count of current sessions waiting on `wait_event_type = 'Lock'`; never infer it from application latency or Hikari pending threads. |
| Rollback and duplicate event detection | Counters incremented at the completed transaction/error and consumer-receipt boundaries. |
| Recovery duration | Duration of the same-operation recovery/resolution action; bounded recovery-kind tag. |

Correlate HTTP response headers, sanitized JSON application events, durable outbox records, simulator dispatch, and callbacks using the validated `X-Correlation-ID` plus stable operation identity. Worker logging must restore and clear MDC on every success/failure path. Logs must not emit credentials, session/CSRF values, HMAC material, full bank instructions, request bodies, or exception messages from external systems.

## Correctness objectives and testable alerts

These are local synthetic-lab objectives, not production SLAs:

- Correctness: zero duplicate effective financial operations, zero unbalanced journal entries, zero projection mismatches in a healthy fixture.
- Event delivery: a pending event older than 30 seconds is observable and raises the local outbox alert.
- Clearing: any persisted UNKNOWN outcome is observable and raises the local unknown-outcome alert until reconciled.
- Reconciliation: any open financial exception is observable and raises the local exception alert.
- Database saturation: current Hikari acquisition wait and PostgreSQL lock wait are separate signals and can be induced separately in an isolated test.

Commit Prometheus-compatible alert rules and evaluate them against real locally exposed samples plus a test fixture that triggers each threshold. A testable alert means its expression/rule fires on the injected fixture and clears when the underlying business record is resolved; no external notification is claimed.

## Performance experiment protocol

All runs use only explicit fresh disposable databases on the isolated PostgreSQL 18.7 cluster. The persistent PostgreSQL service is prohibited. Use JDK 21.0.13, the same Hikari maximum/minimum pool settings as the existing harness (10/10), fixed clients and workload order, controlled `ANALYZE`/checkpoint state, and only synthetic funds/identities. Capture environment, source SHA, database identity, every raw latency sample, query plans with buffers, Hikari state, PostgreSQL lock/deadlock statistics, and independent ledger/projection/transfer/result counts.

### R2-12 pending-item claim path

- Baseline source: current `main` at this registration's source SHA. Run the existing `Round2PerformanceFollowUpHarness.r2p3PayrollWithinBatchGrowthAndOverhead` unchanged before production SQL/index changes.
- Candidate: one forward-only V10 partial index for pending payroll results and a claim query that starts from the batch's pending results, joins the immutable batch item, preserves ascending `sequence_no`, and retains `FOR UPDATE OF r SKIP LOCKED`. V1–V9 remain byte-identical.
- Use new independent database/run IDs for baseline and candidate and the same harness/workload code, fixture sizes (three 500-item batches and one 5,000-item batch), fixed worker count, and repeated trials. Record claim and item execution samples by order decile, plans/buffers, dead tuples/autovacuum state, duplicate/transfer/result counts, and ledger/projection invariants.
- Keep the candidate only if repeated trials show lower median claim latency and lower late-batch growth without worse p95 behavior, ordering, recovery, concurrency, or any financial invariant. Otherwise retain the baseline and publish the negative result.

### R2-10 ledger-history cost

- Re-run `Round2PerformanceFollowUpHarness.r2p2ControlledStatisticsBalanceHistory` against the current Round 3 source with 100, 1,000, 10,000 and 100,000 posting histories, three repeats, refreshed planner statistics, and checkpointed state.
- Record both authoritative posting aggregation and existing projection reads, and capture actual end-to-end transfer samples and `EXPLAIN (ANALYZE, BUFFERS)` plans. Do not change the financial balance gate or projection write path unless a separately measured candidate passes the concurrency, insufficient-funds, rollback, idempotency/replay, crash/recovery, and independent-reconciliation gates from the original registration.
- Reject any candidate that produces a balance mismatch, overdraft, duplicate, deadlock regression, rollback leak, or recovery discrepancy. If the gate cannot be completed within this stage, keep the ledger aggregation unchanged and report R2-10 as an unresolved, measured risk.

## Frozen reporting rules

- Publish raw samples, environment metadata, plans, exact commands, source SHAs, and all repetitions in ignored `work/e9-lab/runs/<RunId>/`; tracked evidence contains summaries and reproducible paths, not secrets or fabricated aggregates.
- Every reported percentile is derived from preserved raw samples. State rejected hypotheses and unrun scenarios.
- Keep local observations separate from capacity claims. No production banking SLA, throughput guarantee, or universal scaling claim is allowed.
- A database lock-wait measure and a pool-wait measure must be induced/observed separately before acceptance; end-to-end latency alone is not a lock metric.
- No E10 backup/PITR or E11 UI work begins in this E9 stage.
