# R2-P1 verdict — batch-bounded payroll progress (H04 remediation acceptance)

**Verdict: SUPPORTED.** Pre-registered in `../PRE-REGISTRATION.md` (commit `1b6ed68`) before measurement.
Raw output: `../r2-p1-progress-20261114T052742Z/` (fixture logs, four measurement logs, `environment.txt`).

## Fixture and correctness

Fresh databases `ledgercore_r2_perf_p1_v6` (V1–V6) and `ledgercore_r2_perf_p1_v7` (V1–V7), identical deterministic
fixtures. S1: target organization, one PROCESSING batch, 5,000 items (3,000 PENDING, 2,000 FAILED); 5,000 results in
the table. S2: plus ten organizations × one DRAFT batch × 5,000 PENDING items; 55,000 results.

Every query returned 3,000 pending / 0 succeeded / 2,000 failed for the target at both stages and in both schemas.
In the V7 database at S2, the Round 1 and Round 2 SQL agreed for all eleven batches. The fixtures created no journal
entries, postings, or transfers.

## Plans (`EXPLAIN (ANALYZE, BUFFERS)` of the cached prepared statement after five executions)

| Stage | Query | Schema | Access to `payroll_item_results` | Result rows read | Shared buffers | Explain ms | Timed p50 ms (30) |
|---|---|---|---|---:|---:|---:|---:|
| S1 | R1_COUNTS | V6 | Seq Scan | 5,000 | 170 | 3.648 | 2.496 |
| S1 | R1_COUNTS | V7 | Seq Scan | 5,000 | 191 | 4.709 | 3.443 |
| S1 | **R2_COUNTS** | V7 | Index Only Scan `ix_payroll_item_results_batch_status` | **5,000** | **14** | 1.880 | 1.849 |
| S1 | R1_VIEW | V6 | Seq Scan | 5,000 | 171 | 5.350 | 3.721 |
| S1 | R1_VIEW | V7 | Seq Scan | 5,000 | 192 | 7.649 | 3.676 |
| S1 | **R2_VIEW** | V7 | Seq Scan (the table holds only the target batch) | **5,000** | **289** | 8.961 | 4.478 |
| S2 | R1_COUNTS | V6 | Seq Scan | **55,000** | 577 | 10.498 | 7.749 |
| S2 | R1_COUNTS | V7 | Seq Scan | **55,000** | 743 | 14.751 | 7.359 |
| S2 | **R2_COUNTS** | V7 | Index Only Scan `ix_payroll_item_results_batch_status` | **5,000** | **15** | 2.477 | 1.159 |
| S2 | R1_VIEW | V6 | Seq Scan | 55,000 | 578 | 12.945 | 10.675 |
| S2 | R1_VIEW | V7 | Seq Scan | 55,000 | 744 | 16.030 | 12.454 |
| S2 | **R2_VIEW** | V7 | Bitmap Heap Scan via `ix_payroll_item_results_batch_status` | **5,000** | **287** | 8.992 | 5.258 |

## Predictions

1. Round 2 queries read ≤ 5,050 result rows at S1 and S2 — **held** (5,000 in every case).
2. Their buffers change ≤ 10% from S1 to S2 — **held** (`R2_COUNTS` 14 → 15, +7.1%; `R2_VIEW` 289 → 287, −0.7%).
3. `R1_COUNTS` scans ≥ 55,000 rows at S2 — **held** (55,000 in both schemas), so the fixture reproduces H04.
4. Counts identical and agreeing across queries and batches — **held**.

## Interpretation and limits

The Round 2 progress work is bounded by the target batch and independent of other organizations' rows, which is the
H04 acceptance criterion. `R2_COUNTS` (used by `finishIfReady`) is an index-only scan of 15 buffers. `R2_VIEW` (used by
every batch view) is bounded but reads the batch's items twice (totals and succeeded amount), so in a database holding
only the target batch it is about 0.8 ms slower at p50 than the Round 1 view; with unrelated rows it is about 2× faster.
A single-pass rewrite (`payroll_items LEFT JOIN payroll_item_results ON item_id AND batch_id`) is a possible later
refinement; it was not needed for acceptance. The extra `batch_id` column makes V7's result table wider, which is why
the unchanged Round 1 SQL reads more buffers on V7 than on V6. Timings are local server-side observations only.
