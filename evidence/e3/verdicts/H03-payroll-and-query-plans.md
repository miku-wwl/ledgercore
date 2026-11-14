# H03 verdict — payroll execution and query plans

**Verdict: MIXED.** Payroll execution and bounded claim plans passed their measured correctness checks. Transaction-history and authorization plans used the expected indexes in this dataset. The original prediction that batch progress remains independent of other organizations is rejected by the supplemental H04 follow-up.

## Payroll execution

The 24 recorded scenarios cover 500- and 5,000-item batches, concurrency 1/10/30/50, and three repeats. All items completed; there were no failures, duplicate transfers, transfer invariant violations, projection mismatches, negative balances, or deadlocks. Global financial invariants were all zero.

| Batch size | Clients | Median items/s | Median p95 ms |
|---:|---:|---:|---:|
| 500 | 1 | 112.50 | 11.00 |
| 500 | 10 | 148.46 | 79.71 |
| 500 | 30 | 153.74 | 216.33 |
| 500 | 50 | 150.41 | 375.06 |
| 5,000 | 1 | 68.10 | 22.54 |
| 5,000 | 10 | 100.08 | 141.02 |
| 5,000 | 30 | 100.31 | 432.72 |
| 5,000 | 50 | 98.25 | 841.85 |

The 5,000-item pending-item claim uses `uq_payroll_items_batch_sequence` and `ix_payroll_item_results_pending`; its saved plan returned one claimed row in 0.356 ms. Concurrent workers did not produce duplicate transfers. Pool saturation and queueing are visible at 30/50 workers.

## Other query plans

- First-page transaction history used `ix_transfers_source_created` and `ix_transfers_target_created`, then a top-N sort over the matching 5,000 transfer rows (7.846 ms in the saved plan).
- Corrected keyset history used the same indexes and returned the next 11 rows after 2,499 eligible rows (6.037 ms). The exact UTC cursor with microseconds is preserved in `history-query-fixture.json`.
- Membership authorization used the membership customer index and role primary key; the observed query took 0.084 ms with seven shared hits in a small synthetic organization dataset.
- The 5,000-item progress query scanned all 71,500 rows in `payroll_item_results` even though it returned counts for one 5,000-item batch. H04 then isolated cross-organization growth and falsified the prediction that this work stays independent of other organizations.

## Evidence

- Raw per-item samples, scenario summary, global invariants, and original/post-`ANALYZE` plans: `measurements/h03-payroll-query-plans-20261114T025611Z/`.
- Cross-organization follow-up: `verdicts/H04-cross-organization-payroll-progress.md` and `measurements/h04-cross-org-progress-followup/`.
- Figure generated from the scenario CSV: `analysis/figures/h03-payroll-concurrency.png`.

The payroll data is synthetic and local. Authorization plan cardinality is small; this does not establish performance for a production-scale role table.
