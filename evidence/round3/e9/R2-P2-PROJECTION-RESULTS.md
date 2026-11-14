# R2-P2 projection-read supplement — observed results

## Run identity

- Protocol: `R2-P2-PROJECTION-MEASUREMENT-AMENDMENT.md`, registered before this run.
- Source: `cb2601f103d765e1c43221d7d5d34e59bb4551cf`.
- Database: fresh disposable `ledgercore_r2_perf_e9cand_r2p2proj_r2_20261115`, isolated PostgreSQL 18.7 on port 55438.
- Runtime: JDK 21.0.13+9, Hikari 10/10, 16 logical processors, 33.6 GB RAM. Measurement boundary is direct Spring service proxy calls in one JVM thread, including commit and excluding HTTP.
- Command: the existing `Round2PerformanceFollowUpHarness.r2p2ControlledStatisticsBalanceHistory` test, invoked through the backend Maven test suite. Maven exited 0; 1 test, 0 failures/errors/skips; elapsed test time 160.2 seconds and build time 2:47.
- Raw `samples.csv`, `summary.csv`, `environment.json`, and all 24 SQL plan captures are preserved in ignored `work/e9-lab/runs/20261115-r2p2-projection/r2-p2-h01-controlled-statistics-e9-p2-projection-r2-20261114T143813Z/`.

## Measurements

The table reports the median of each metric's three per-repeat percentiles. Times are milliseconds. “Ledger SQL” is the authoritative posting aggregation, “transfer” is the service-proxy call with commit, and “projection read” is the existing read-only materialized balance projection. These are current-source observations, not matched before/after comparisons.

| Posting history | Ledger SQL p50 / p95 | Transfer p50 / p95 | Projection read p50 / p95 |
|---:|---:|---:|---:|
| 100 | 0.583 / 0.898 | 11.747 / 15.386 | 0.530 / 0.689 |
| 1,000 | 0.916 / 1.200 | 10.934 / 13.511 | 0.326 / 0.615 |
| 10,000 | 4.115 / 6.192 | 17.583 / 21.331 | 0.488 / 0.649 |
| 100,000 | 39.165 / 51.389 | 90.393 / 105.139 | 0.330 / 0.558 |

The authoritative aggregation p50 rises from 0.583 ms at 100 postings to 39.165 ms at 100,000; the transfer service p50 rises from 11.747 ms to 90.393 ms. Projection-read p50 remains between 0.326 and 0.530 ms across these fixtures. At 100,000 postings, the projection query's captured plan is a sequential scan over 22 rows (`Rows Removed by Filter: 21`), 1 shared buffer hit, 0.059 ms execution time. This is a tiny local projection table and does not establish behavior at larger account counts.

The run's 12 fixtures all report `source_projection_matches=true`. Independent SQL found 360 transfers and 360 matching successful idempotency receipts, no negative customer-ledger balances, no unbalanced journal entries, and no ledger/projection mismatches. The separate source-baseline R2-P2 run used a different source SHA and had plan-node variation; the original R2-P2 verdict therefore remains **INCONCLUSIVE** under its preregistered rule. No projection write-path change was attempted.

## Setup failure retained in the run record

An initial fresh-database attempt stopped before measurement when PostgreSQL rejected the harness checkpoint because test role `ledgercore_e7` lacked `pg_checkpoint`. That database was not reused for measurements. `pg_checkpoint` membership was granted to that role only on the isolated local port-55438 cluster, and the successful run used a new database. No persistent PostgreSQL service was accessed.

## Interpretation

The existing projection read is much cheaper than aggregating 100,000 authoritative postings in this fixture, but this measures only a read. It is not evidence that projection writes can safely replace the authoritative balance gate. R2-10 remains an unresolved optimization risk pending separate concurrency, insufficient-funds, rollback, replay, crash/recovery, and reconciliation gates. No production capacity or SLO claim follows from these local synthetic measurements.
