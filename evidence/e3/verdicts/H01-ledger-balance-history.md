# H01 verdict — ledger balance and transfer cost vs. history

**Verdict: INCONCLUSIVE under the pre-registered rule.** The measurements show a strong history-associated increase, but repeat variation at 100,000 postings was over 2× and the original plans changed across repeats. The preregistration explicitly says these conditions prevent a clean PASS verdict.

## Observed values

| Source history | SQL balance p50 median (repeat range) | TransferService p50 median (repeat range) |
|---:|---:|---:|
| 100 | 0.8354 ms (0.6309–1.0297) | 10.9683 ms (9.4283–12.9183) |
| 1,000 | 0.8769 ms (0.7716–0.9871) | 9.9432 ms (9.4632–10.4180) |
| 10,000 | 4.9721 ms (4.3117–5.1772) | 17.2608 ms (16.4784–18.0727) |
| 100,000 | 105.5857 ms (46.8081–107.0666) | 219.9671 ms (99.2828–224.3965) |

The 100-to-100,000 median ratios are 126.4× for the isolated SQL aggregation and 20.1× for the real `TransferService` transaction. The source balance and projection agreed at every measured size. These are local laptop observations, not production capacity estimates.

## Why the verdict is not PASS

- At 100,000 postings, the SQL repeat median range spans 46.8081–107.0666 ms and the service range spans 99.2828–224.3965 ms; each is about 2.3× from minimum to maximum.
- The saved pre-`ANALYZE` plans differ between repeats as PostgreSQL's estimates and chosen bitmap/index scans change. Those original files are retained. Post-`ANALYZE` plans and table statistics are retained separately.
- Both 5× thresholds were exceeded by the aggregate medians, but the pre-registered rule treats repeat inconsistency or a changed plan as inconclusive.

No production balance-read or write-path change was made. A projection candidate requires its own pre-registration, a stable baseline, and full financial correctness gates. The 100,000-posting growth and plan sensitivity are specific questions for the Round 2 reviewer.

## Evidence

- Raw samples and per-repeat summary: `measurements/h01-balance-history-20261114T023439Z/`.
- Original and post-`ANALYZE` query plans and table statistics: the same folder's `query-plans/` and `query-plans-after-analyze/` directories.
- Figure generated from the CSV: `analysis/figures/h01-history-growth.png`.
