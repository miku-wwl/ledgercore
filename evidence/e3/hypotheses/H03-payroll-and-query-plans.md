# H03 — Payroll claiming, progress, history, and authorization plans

## Research question

Do the current indexes and queries keep batch claiming, batch progress, transaction history, and organization authorization bounded at realistic cardinalities?

## Predictions

1. Payroll item claiming by batch and sequence will use batch/item/result indexes at 500 and 5,000 items; it will not lock the same pending result twice under concurrent `SKIP LOCKED` workers.
2. Batch progress cost will grow with the number of items in the selected batch but will remain independent of other organizations' payroll rows.
3. Transaction-history first-page and keyset-cursor queries will use the source/target-created indexes at a sufficiently large transfer table and return no duplicates or gaps across pages.
4. Organization membership/role authorization will use the composite membership and role indexes with the expected organization/customer predicates.

## Variables and method

- Payroll sizes: 500 and 5,000 items if fixture setup and runtime remain within the disposable host budget. Run 500-item approved batches with 1/10/30/50 concurrent `executeNext` workers; repeat each concurrency level three times.
- Query-plan cardinalities: prepare representative isolated test data before each plan; save raw `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` for ledger aggregation, transaction-history first page and cursor page, pending payroll claim, batch summary/progress, and organization membership/role lookup.
- Record planning/execution time, chosen scan/join nodes, actual versus estimated rows, shared hit/read buffers, and lock waits. Use the same PostgreSQL 18.7 database settings across repetitions.
- Payroll correctness gates: 500 unique successful item receipts, zero duplicate transfers, zero pending/failed results after completion, balanced postings, and projection-to-ledger equality.

## Acceptance and interpretation

Plans must correspond to the saved SQL and data cardinality. A sequential scan on a deliberately small table is not considered an index defect; interpret the plan at measured row counts. A prediction is supported only by the saved plan and measured buffers/latency. Do not add indexes or rewrite queries without evidence, and rerun the financial correctness gates if any production query or index changes.

## Threats to validity

The local database is isolated and synthetic. Query planner choices depend on `ANALYZE` statistics, cache state, and table size. The 5,000-item and 100,000-posting sizes are conditional on local resource preflight and may be reduced only with the reason and actual maximum documented.
