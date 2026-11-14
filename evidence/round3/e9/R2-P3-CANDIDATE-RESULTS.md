# R2-P3 pending-result claim candidate — measured rejection

## Protocol and source

- Pre-registration: `PERFORMANCE-PRE-REGISTRATION.md` and `20261115-EXECUTION-REGISTRATION.md`.
- Baseline source: `392b1bf82dea096eac1660cd1559ffbc8dff897d`.
- Candidate source: `2dc8976136396afa871c1fee599327d0c0e2fbaf`.
- Runtime: JDK 21.0.13+9, PostgreSQL 18.7 on isolated port 55438, Hikari maximum/minimum 10/10. Each of the three baseline and three candidate trials used a fresh `ledgercore_r2_perf_*` database, one worker, the unchanged 3 × 500 plus 1 × 5,000 item workload, and synthetic identities/funds.
- The measurement boundary is the Spring service proxy call, including commit, not HTTP. Candidate code also adds E9 metrics around payroll execution, so the end-to-end latency difference cannot be attributed to the index alone. Query plans provide direct evidence about whether the index changed the claim plan.
- Raw samples, environment files, and plans remain under ignored `work/e9-lab/runs/20261115-r2p3-baseline/` and `work/e9-lab/runs/20261115-r2p3-candidate/`.

## Results

Values are medians across batch summaries: nine 500-item batches and three 5,000-item batches per source. Each batch p50/p95 is itself calculated from its preserved per-item samples.

| Batch | Measure | Baseline | Candidate | Change |
|---|---|---:|---:|---:|
| 500 items | Median batch p50 (ms) | 30.591 | 33.028 | +8.0% |
| 500 items | Median batch p95 (ms) | 62.567 | 53.253 | -14.9% |
| 500 items | First-decile median (ms) | 13.749 | 15.467 | +12.5% |
| 500 items | Last-decile median (ms) | 31.973 | 43.556 | +36.2% |
| 5,000 items | Batch p50 (ms) | 13.016 | 13.888 | +6.7% |
| 5,000 items | Batch p95 (ms) | 21.867 | 22.746 | +4.0% |
| 5,000 items | First-decile median (ms) | 8.372 | 8.450 | +0.9% |
| 5,000 items | Last-decile median (ms) | 17.897 | 18.719 | +4.6% |

At the 4,999-item checkpoint, all three candidate plans still scan 5,000 ordered `payroll_items` entries and perform 5,000 index probes into the pre-existing pending-result index. The new V10 `(batch_id, item_id) WHERE status='PENDING'` index is not selected by PostgreSQL's ordered claim plan. Candidate plans report roughly 10,129–10,149 shared buffer hits. The baseline has the same scan-and-probe shape and approximately 10,146–10,148 hits.

## Independent financial grade

Each candidate database finished four batches (three at 500 items, one at 5,000), with 6,500/6,500 result rows `SUCCEEDED` and 6,500 distinct linked transfers. Independent SQL found zero unbalanced journal entries and zero account projection mismatches in each database. All three Maven experiment runs exited 0.

## Verdict

**Reject the V10 index/query candidate under the registered keep rule.** The candidate did not lower the 5,000-item p50 or p95, did not reduce first-to-last decile growth, and did not change the late-batch plan. The 500-item results are noisy and do not establish a useful benefit. The candidate is not a production capacity result.

V1–V10 remain immutable. Forward-only V11 removes the unused V10 index on newly migrated databases, and application SQL returns to the earlier ordered `SKIP LOCKED` claim shape. The experiment does not resolve the broader R2-12 claim-path cost; that remains an explicit risk. R2-10 remains separate and unchanged.
