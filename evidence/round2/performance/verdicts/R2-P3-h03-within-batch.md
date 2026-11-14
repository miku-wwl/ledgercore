# R2-P3 verdict — within-batch payroll latency growth and V7 overhead

**Verdict per prediction: P3.1 REJECTED, P3.2 SUPPORTED, P3.3 SUPPORTED on its pre-registered metric but not
informative.** Pre-registered in `../PRE-REGISTRATION.md` (commit `1b6ed68`).

Raw output: build A (baseline `b584b95`, V6, database `ledgercore_r2_perf_p3_base`)
`../r2-p3-h03-within-batch-baseline-b584b95-20261114T053412Z/`; build B (Round 2 `1b6ed68`, V7, database
`ledgercore_r2_perf_p3_r2`) `../r2-p3-h03-within-batch-round2-1b6ed68-20261114T053907Z/`. Each contains `summary.csv`,
per-item `samples.csv`, `checkpoints.csv`, saved plans, and `environment.json`. Build A's harness copy predates the
settings-format fix, so its `environment.json` has an `environment-correction.md` beside it; build B's is already
readable. Both runs used the same cluster settings (shared buffers 128 MB, work_mem 4 MB, JIT on). Build A ran first,
build B second, on an otherwise idle machine. All 6,500 items in each build completed exactly once
(`unique_transfers` = items) with matching projections.

## Results (one worker, items executed through `PayrollItemExecutor.executeNext`)

| Scenario | Build A items/s | A p50 ms | A first → last decile ms | Build B items/s | B p50 ms | B first → last decile ms |
|---|---:|---:|---|---:|---:|---|
| 500 items, r1 | 33.27 | 31.388 | 14.769 → 35.986 (2.44×) | 33.96 | 30.269 | 16.230 → 34.653 (2.14×) |
| 500 items, r2 | 22.44 | 44.319 | 14.323 → 70.111 (4.89×) | 24.90 | 41.286 | 17.073 → 56.107 (3.29×) |
| 500 items, r3 | 42.78 | 13.626 | 15.418 → 7.705 (0.50×) | 38.66 | 19.933 | 16.345 → 9.875 (0.60×) |
| 5,000 items | 64.29 | 14.516 | 7.902 → 23.047 (2.92×) | 59.53 | 16.436 | 11.049 → 18.928 (1.71×) |

5,000-item deciles — A: 7.9, 10.5, 11.4, 12.9, 14.6, 14.8, 16.9, 17.6, 21.5, 23.1; B: 11.1, 14.1, 16.8, 16.0, 14.6, 16.1,
17.0, 18.3, 19.3, 18.9 ms.

Checkpoints in the 5,000-item batch (p50 of 20 executions):

| Completed items | Payer postings | A claim ms | A payer balance ms | B claim ms | B payer balance ms |
|---:|---:|---:|---:|---:|---:|
| 0 | 2 | 0.417 | 0.471 | 0.828 | 0.587 |
| 1,250 | 1,252 | 2.244 | 0.962 | 10.373 | 1.413 |
| 2,500 | 2,502 | 6.668 | 2.236 | 6.169 | 2.131 |
| 3,750 | 3,752 | 8.085 | 2.671 | 7.841 | 2.632 |
| 4,999 | 5,001 | 7.485 | 3.648 | 8.102 | 2.182 |

## Predictions

1. **REJECTED.** Build B's 5,000-item growth was 1.71× (required ≥ 2×; build A 2.92× reproduces Round 1's 2.7×), and
   the 500-item batches grew 2.1–4.9× in both builds (required ≤ 1.3×).
2. **SUPPORTED.** Claim growth plus twice the payer-balance growth explains 13.42 of 15.16 ms (88.6%) in A and 10.47 of
   7.87 ms (133%) in B. More than 100% shows the decomposition of isolated query timings is approximate.
3. **SUPPORTED on its metric** (median 500-item p50: B 30.269 ms vs A 31.388 ms, 0.96×), but the 500-item batches were
   dominated by the early-phase effect below, so this does not measure V7.

## Interpretation

- **Mechanism of the within-batch growth (both builds).** Two history-dependent costs grow by one unit per completed
  item: (a) the claim query walks the batch in `sequence_no` order and probes the PENDING partial index for every
  completed item; `payroll_item_results` updates are never HOT (in A's database 6,500 updates, 0 HOT, because `status`
  is in the partial-index predicate), so each completed item leaves dead index entries that every later claim steps
  over until vacuum; (b) each item's transfer aggregates the payer's full posting history twice (balance check and
  projection rebuild). B's claim p50 falling from 10.4 ms at 1,250 to 6.2 ms at 2,500 completed items is consistent with
  autovacuum removing those dead entries mid-batch, which also explains B's flatter decile curve and the sudden
  mid-batch drop in both builds' third 500-item batch.
- **Early-phase effect (not explained).** In both builds the 500-item batches, which start seconds after the harness
  bulk-loads 5,000 accounts, ran 2–5× slower than later work and degraded within each batch, with a near-identical
  shape in A and B. Round 1 H03's 500-item batches, which ran after more setup work, were flat. Post-load autovacuum is
  the leading hypothesis; Round 2 did not isolate it.
- **V7 overhead.** Between-run variation exceeded any plausible V7 cost (the unaffected payer-balance query differed
  1.25× between runs at checkpoint 0). An exploratory, not pre-registered, micro-measurement
  (`../exploratory/v7-trigger-overhead.sql`, output alongside) timed the two lookups V7's transition trigger adds for
  2,000 real SUCCEEDED results: **0.038 ms per result** (warm), about 0.3–0.5% of an 8–15 ms item.

## Consequences for Round 3

A payroll batch's per-item cost is O(completed items) for the claim (until vacuum) plus O(payer history) for the
transfer, so long batches and long-lived payer accounts degrade. Candidate remedies, each needing its own
pre-registration and the payroll correctness suite: a claim access path that does not revisit completed items (for
example a pending-only index ordered by batch and sequence), more aggressive autovacuum settings for
`payroll_item_results`, and the gated incremental balance projection discussed in R2-P2. Round 2 changes none of them.
