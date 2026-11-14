# H01 — Ledger balance cost as account history grows

## Research question

Does scanning authoritative postings for balance reads and post-write projection refresh become a material cost as one account's ledger history grows?

## Source-grounded basis

`LedgerBalanceReader.ledgerBalanceForLedgerAccount` aggregates `postings` by `ledger_account_id`. `LedgerWriter.transferBetweenCustomerAccounts` reads the source ledger after taking ordered ledger-row locks, then `writeEntryAndRefreshProjections` rebuilds the projection for each affected ledger account from the full posting history. A transfer therefore performs an authoritative source-balance aggregate and two projection-rebuild aggregates.

## Prediction

1. The isolated balance aggregation's p50 latency and buffer work will increase monotonically across exact account histories of 100, 1,000, 10,000, and 100,000 postings.
2. At 100,000 postings, aggregation p50 will be at least 5× the 100-posting p50.
3. The production `TransferService.transfer` transaction p50 at 100,000 postings will be at least 5× the 100-posting p50 because it performs multiple history-dependent aggregates.

## Variables and method

- Independent variable: target account posting count; all fixture entries are balanced, append-only `SIMULATED_DEPOSIT` journal entries in the dedicated performance database.
- Dependent variables: SQL p50/p95/p99 and buffers for balance aggregation; service-transaction p50/p95/p99, throughput, JVM CPU time, and database connection utilization for transfers.
- Fixed: PostgreSQL 18.7, schema V6, same local machine, same Java 21 build and connection-pool configuration, warm caches after recorded warm-up, amount NZ$0.01, same prepared query and JDBC path.
- Histories: 100 / 1,000 / 10,000 / 100,000 postings on the measured source account. The largest size is subject to the preflight safety condition in `PRE-REGISTRATION.md`.
- Per history: 10 warm-up balance reads, 100 timed reads, and 30 real `TransferService.transfer` calls; repeat the configuration three times with independently created accounts and idempotency keys.
- Query plans: save raw `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` output for the aggregation at each feasible history size.

## Interpretation and decision rule

- Support the material-growth prediction if both SQL and service p50 grow by at least 5× from 100 to 100,000 postings and buffer evidence shows work attributable to matching posting rows.
- Reject the prediction if both p50 ratios are below 2× and no material buffer growth is observed.
- Ratios between 2× and 5×, inconsistent repeat results, or a changed plan are inconclusive and must be described without forcing a binary conclusion.
- Do not change the production write path unless this baseline demonstrates a meaningful cost. A candidate projection design requires its own pre-registered hypothesis and must pass ledger, overdraft, idempotency, rollback, concurrency, and independent-reconciliation checks.

## Threats to validity

The local PostgreSQL cache and storage are not representative of production. Bulk fixture creation does not measure the production posting path; all timed transfer calls do. JVM warm-up, background processes, small sample counts, and data/index caching can affect tails. Results are local workload evidence and cannot support capacity claims.
