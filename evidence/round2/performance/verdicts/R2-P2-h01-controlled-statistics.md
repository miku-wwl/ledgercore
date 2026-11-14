# R2-P2 verdict — H01 follow-up with controlled planner statistics

**Verdict: INCONCLUSIVE under the pre-registered rule** — predictions 2 and 3 held; prediction 1 failed on its
"same access path" condition while its estimate condition held. The material question H01 left open is answered
(see Interpretation), but the rule fixed before measurement is applied as written.

Pre-registered in `../PRE-REGISTRATION.md` (commit `1b6ed68`). Raw output:
`../r2-p2-h01-controlled-statistics-round2-20261114T052928Z/` (`summary.csv`, `samples.csv`, `query-plans/`,
`environment.json` with `environment-correction.md`). Database `ledgercore_r2_perf_p2`, PostgreSQL 18.7, Hikari 10,
Java 21.0.13; source `1b6ed68` (ledger and transfer write path identical to the Round 1 baseline apart from the R2-06
reserved-key check).

## Results (median of three repeat p50 values; ms)

| History | SQL balance p50 (repeat range) | TransferService p50 (repeat range) | Plans for `postings` (estimate → actual) | Buffers |
|---:|---:|---:|---|---|
| 100 | 1.0042 (0.7634–1.2139) | 15.2913 (13.2055–16.9313) | Seq Scan ×3 (100 → 100) | 3–9 |
| 1,000 | 0.8791 (0.7943–1.0144) | 12.2978 (12.2918–13.8909) | Seq Scan, Bitmap, Bitmap (1,000 → 1,000) | 35 |
| 10,000 | 4.8567 (4.5235–5.8187) | 19.2000 (18.7211–19.5866) | Seq Scan (10,000), Bitmap (10,043), Bitmap (10,031) → 10,000 | 333 / 329 / 329 |
| 100,000 | 47.1523 (41.4635–53.6659) | 93.0418 (90.8362–106.2056) | Seq Scan (99,661), Bitmap (98,159), Bitmap (99,933) → 100,000 | 3,299 / 3,298 / 3,263 |

## Predictions

1. Same access path for all six plans ≥ 10,000 **and** estimates within 2× — **failed**: estimates were within 1.8% of
   actual, but repeat 1 at both 10,000 and 100,000 used a sequential scan while repeats 2–3 used a bitmap heap scan.
2. Repeat spread at 100,000 ≤ 1.5× — **held**: SQL 1.29×, service 1.17× (Round 1: about 2.3×).
3. 100 → 100,000 median p50 ratio ≥ 5× — **held**: SQL **47.0×**, service **6.1×**.

## Interpretation

- The Round 1 confounder is gone. With fresh statistics every plan estimated the account's rows correctly, and
  buffer work across repeats at 100,000 varied by 1.1% (3,263–3,299), versus 31× in Round 1 (3,251 vs 100,751).
- Prediction 1 failed for a reason it was not designed to detect. The harness creates each repeat's history in the same
  database, so repeat 1's account held about 37% of all `postings` rows and the planner correctly preferred a full
  sequential scan; later repeats held a smaller share and got a bitmap scan. Both paths read the same pages in the same
  time here (54.8 ms vs 55.0 ms in the saved plans). Plan identity was too strict a stability criterion.
- The cost is history-dependent and roughly linear: about 0.47 µs per posting per aggregate (47 ms per 100,000 postings
  on this machine). A transfer performs two full aggregates on the source (balance check and projection rebuild); at
  100,000 postings 2 × 47 ms ≈ 94 ms matches the measured 93 ms service p50, so the source-history aggregates dominate
  the transfer cost at large histories.
- Absolute values differ from Round 1 (different JVM build and cluster instance; small-history transfers are ~4 ms
  slower here), so only within-run ratios and spreads are compared across rounds.

## Engineering decision

Round 2 does not change the ledger write path. A transactionally maintained incremental balance would remove the
history dependence but changes the core overdraft check; it requires its own pre-registered experiment and the
correctness gates listed in `docs/v2/ROUND-2-HANDOFF.md`. The finding is carried as a risk for payroll payers, whose
history grows by one posting per item (R2-P3).
