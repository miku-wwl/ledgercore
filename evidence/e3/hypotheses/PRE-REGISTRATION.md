# E3 Experimental Pre-registration

- Round: 1, stage E3.
- Branch policy: `main` only; no branches, worktrees, or PRs.
- Status at registration: hypotheses and workloads only; no E3 timing, load test, or `EXPLAIN ANALYZE` result has been collected.
- Starting source commit: `4ab093cc` (E2 validated; implementation CI run `43543154101` passed).
- Target: a new disposable `ledgercore_e3_perf` database on the local PostgreSQL 18.7 test cluster at `127.0.0.1:55434`.
- Host identified before experiments: HONOR BRI-XX, 32 GiB physical memory. CPU, storage, JVM, PostgreSQL, pool, and database settings must be captured in the environment record immediately before the first measurement.

The individual predictions are fixed in `H01-ledger-balance-history.md`, `H02-financial-concurrency.md`, and `H03-payroll-and-query-plans.md`. The planned independent variables, repetitions, sample counts, workload shapes, and correctness gates are fixed in `../workloads/workload-specification.json`.

No hypothesis may be edited after its measurements begin. If a prediction is contradicted, preserve the result and explain it. Any conditional balance-projection optimization requires a new hypothesis commit on `main` after the baseline result and before the candidate is measured.

The 100,000-posting history size is conditional on preflight confirming adequate free disk, available memory, and safe runtime on the disposable cluster. If unsafe, record the measured maximum and the exact reason; do not substitute synthetic numbers.
