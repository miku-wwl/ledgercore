# P5 PostgreSQL query-plan experiment

Status: completed for the P5 synthetic-data review. These timings describe this local experiment only; they are not service-level targets or production capacity claims.

## Environment and data

- PostgreSQL 18.7, 64-bit Windows build; `shared_buffers=128MB`.
- Local host: AMD Ryzen 7 8845HS, 32 GiB RAM. The experiment used a disposable `ledgercore_perf` database on the same machine as PostgreSQL.
- Application baseline: Spring Boot 4.0.9, Java 21.0.13, schema migrations V1–V4.
- Dataset: 1,000 synthetic customers, 10,000 synthetic accounts, 60,000 journal entries, 120,000 postings, and 50,000 transfers. The transfer set is deterministic and connects the first 1,000 accounts in a ring. Each journal entry has two balanced postings; transfer rows passed the database constraints at commit.
- The scripts use only generated `example.test` identities and a placeholder password hash. They contain no real user data or credentials.

## Query plans and measured results

The queries reproduce the SQL shapes used by the account list, transaction history, ledger balance reader, and reconciliation services. Measurements below are the PostgreSQL `Execution Time` from `EXPLAIN (ANALYZE, BUFFERS)` with the existing V1–V4 indexes. Buffer counts are shared-buffer hits reported by PostgreSQL.

| Query | Observed plan | Rows | Time | Shared hits |
|---|---|---:|---:|---:|
| Account list and balance projection | Bitmap scan on `ix_accounts_owner_created`; indexed lookups for the customer ledger and projection; sort of 10 accounts | 10 | 0.386 ms | 88 |
| Transaction history | `BitmapOr` over `ix_transfers_source_created` and `ix_transfers_target_created`; fetch 100 matching transfers; hash joins to the account names; sort and limit to 51 | 51 | 7.790 ms | 346 |
| Ledger signed-posting aggregate | Index lookup of the ledger account, then bitmap lookup on `ix_postings_ledger_account`; aggregate 101 postings | 1 | 0.828 ms | 107 |
| Reconciliation ownership check | Index-only scan using `uq_accounts_id_owner` | 1 | 0.023 ms | 3 |
| Reconciliation ledger aggregate | Same indexed Posting aggregate; warm repeat of the preceding ledger query | 1 | 0.179 ms | 107 |
| Reconciliation projection read | Primary-key lookup on `account_balance_projection` | 1 | 0.022 ms | 3 |

The history plan uses sequential scans of the 10,000-row `accounts` table twice to build small hash tables for the source and target names. The transfer predicates still use both transfer indexes. PostgreSQL estimated the hash joins cheaper for this dataset; this is recorded as observed planner behavior, not assumed to be ideal for every account count or cache state.

## Index comparison

The comparison script drops one read index or index pair in the disposable database, runs the same query, restores the migration-defined index, and runs `ANALYZE` again.

| Query | With migration index | Without that index | Plan change |
|---|---:|---:|---|
| Account list | 0.386 ms, 88 shared hits | 1.612 ms, 214 shared hits | Owner bitmap scan became a sequential scan of 10,000 accounts; 9,990 rows were filtered out. |
| Transaction history | 7.790 ms, 346 shared hits | 11.678 ms, 1,109 shared hits | Two transfer bitmap index scans became a sequential scan of 50,000 transfers; 49,900 rows were filtered out. |
| Ledger aggregate | 0.828 ms, 107 shared hits | 10.037 ms, 1,485 shared hits | Posting bitmap scan became a sequential scan of 120,000 postings; 119,899 rows were filtered out. |

Each comparison restored the original index. `compare-index-strategies.sql` does not leave the scratch schema without its indexes when it completes successfully.

## Reproduction

Create a fresh disposable database owned by the local test role, point the test-profile datasource at it, and start the packaged application once so Flyway applies V1–V4. Stop the app, then run these scripts with `psql` against that database:

```text
psql --set=ON_ERROR_STOP=on -d ledgercore_perf -f backend/docs/performance/seed-performance-data.sql
psql --set=ON_ERROR_STOP=on -d ledgercore_perf -f backend/docs/performance/performance-queries.sql
psql --set=ON_ERROR_STOP=on -d ledgercore_perf -f backend/docs/performance/compare-index-strategies.sql
```

The scripts refuse to run outside the `public` schema of a database named `ledgercore_perf`. Seeding also refuses pre-existing customers, accounts, journal entries, postings, or transfers. The index comparison runs in a transaction so an error rolls back any dropped index. The seeding script creates synthetic rows that satisfy the journal balancing and transfer-to-posting constraints, then recomputes balance projections and analyzes the tables.

## Limits

This was one local, single-user SQL experiment with a deterministic distribution and a warm cache for repeated query shapes. It did not measure concurrent application requests, end-to-end latency, tail latency, throughput, failover, or production hardware. Planner choices and timings depend on database size, statistics, PostgreSQL settings, cache state, storage, and workload. Treat the results as evidence for the examined queries and indexes only.
