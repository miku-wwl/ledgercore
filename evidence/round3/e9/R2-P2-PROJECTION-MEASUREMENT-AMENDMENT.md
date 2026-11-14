# R2-P2 projection-read measurement amendment

- Registered: 2026-11-15 NZDT, before the supplemental R2-P2 measurement.
- Scope: add measured read-only `LedgerBalanceReader.projectionBalanceForLedgerAccount` timings and its `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` plan to the existing controlled R2-P2 harness.
- This does not alter ledger aggregation, projection writes, transfer authorization, or the original R2-P2 decision rule. The new projection-read samples are collected after each repeat's transfer workload so they do not warm the projection path before transfer measurements.
- The earlier E9 baseline measured authoritative ledger reads and transfer service calls but did not preserve projection-read timings. Therefore this supplement is a current-source observation, not a matched before/after comparison and not evidence that a projection write-path optimization is safe.
- Run against a new disposable `ledgercore_r2_perf_*` database with the existing four history sizes (100, 1,000, 10,000, 100,000 postings), three repeats each, JDK 21.0.13, PostgreSQL 18.7, Hikari 10/10, refreshed statistics and checkpointed fixtures. Preserve the exact source SHA, environment, samples, plans, and independent ledger/projection checks under ignored `work/e9-lab/runs/`.
- No balance write-path candidate will be implemented in this supplement. Report R2-10 as a measured history-dependent cost and an unresolved optimization risk unless all financial, concurrency, rollback, replay, recovery, and reconciliation gates are separately completed.
