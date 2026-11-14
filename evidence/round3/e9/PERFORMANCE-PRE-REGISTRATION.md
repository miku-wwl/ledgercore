# E9 Performance Follow-up Pre-registration

- Registered: 2026-11-14, before any Round 3 performance measurements.
- Starting baseline commit: `0db728171f7a28119ed4c4f19437896878f03fc6`.
- Status: pre-registered; no Round 3 performance measurement has run.

## Questions

1. Does a batch-bounded pending-only claim path reduce R2-12 latency growth as completed payroll items accumulate, without violating ordering, recovery or V7 constraints?
2. Can an incremental account balance projection safely reduce R2-10 history aggregation cost under concurrency, hot-account contention, insufficient funds, idempotency replay, rollback, crash recovery and independent ledger reconciliation?

## Predictions and decision rules

For R2-12, a partial pending-only index/query should scan a bounded subset of the target batch, lower claim work as completed rows grow, and preserve `SKIP LOCKED` concurrency. Keep only if repeated runs show lower median claim latency without worse financial/operational outcomes.

For R2-10, do not change the posting path unless an isolated candidate transaction can serialize balance check/update with postings and retain ledger as independent truth. Reject the candidate on any projection mismatch, overdraft, deadlock regression, duplicate, rollback leak, or recovery discrepancy. Performance improvement alone is insufficient.

## Controls

Commit this registration before measurement. Use independent fresh disposable DBs, unchanged workload shape and fixed PostgreSQL/JDK/Hikari/client concurrency. Capture raw per-operation samples, plans with buffers, checkpoint/statistics state, pool wait, lock wait and independent financial counts. Run repeated baseline and candidate trials; publish all repetitions, negative findings and pre/post source SHAs. Synthetic results are not production SLO/capacity claims.
