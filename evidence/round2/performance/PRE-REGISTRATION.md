# Round 2 performance follow-ups — pre-registration

- Round: 2 (independent audit of E0–E3), owner Claude Opus 5.5. Branch policy: `main` only.
- Status at registration: **no Round 2 performance measurement has been taken.** Round 1 evidence under
  `evidence/e3/` is read-only input; its hypotheses, raw data, and verdicts are not edited.
- Source under test: the Round 2 remediation commit `0a885c8ee6a7e2409362169614f992a076eff779` and, for R2-P3 only,
  the Round 1 baseline `b584b9570eee2d2b4c08505b19336b08dfc37a77` exported with `git archive` (no checkout, branch, or
  worktree).
- Target: fresh disposable databases named `ledgercore_r2_perf_*` on a separate PostgreSQL 18.7 cluster at
  `127.0.0.1:55436` (data directory `work/pg-round2-audit`). The Round 1 cluster on 55434 and the persistent
  service on 55432 are not used. The harness refuses any other database name.
- Host: the same laptop class recorded in `evidence/e3/environment/environment.json` (8 cores / 16 threads,
  Windows 11); each run writes its own `environment.json`. Java 21.0.13, HikariCP maximum 10 (as in E3).
- Predictions below are fixed. If a prediction fails, the result is preserved and explained, not re-run until it
  passes. Results are local, synthetic workload observations, never capacity claims.

## R2-P1 — H04 remediation acceptance: batch-bounded payroll progress

**Question.** With 50,000 unrelated payroll results from ten other organizations present, does the Round 2 progress
SQL keep its work bounded by the target batch while returning the same counts as the Round 1 SQL?

**Method.** Two fresh databases: `ledgercore_r2_perf_p1_v6` (V1–V6) and `ledgercore_r2_perf_p1_v7` (V1–V7).
`scripts/r2-p1-fixture-target.sql` creates the target organization with one PROCESSING batch of 5,000 items
(3,000 PENDING, 2,000 FAILED); `scripts/r2-p1-fixture-unrelated.sql` adds ten organizations × one DRAFT batch ×
5,000 PENDING items. Both stages end with `VACUUM (ANALYZE)` of the touched tables. `scripts/r2-p1-measure.sql` runs at
stage S1 (target only) and S2 (after unrelated rows) in both databases: the Round 1 `countsFor`/`viewFor` SQL
(`R1_COUNTS`, `R1_VIEW`) everywhere, and the Round 2 SQL (`R2_COUNTS`, `R2_VIEW`) in the V7 database. Each statement
is prepared, warmed five times, explained with `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`, and timed 30 times server-side.

**Predictions.**
1. `R2_COUNTS` and `R2_VIEW` read at most 5,050 rows from `payroll_item_results` at S1 and S2.
2. Their shared-buffer totals change by at most 10% from S1 to S2.
3. `R1_COUNTS` scans at least 55,000 result rows at S2 (replicating H04 on the fresh fixture).
4. Every query reports 3,000 pending, 0 succeeded, 2,000 failed for the target, and Round 1 and Round 2 counts agree
   for all eleven batches.

**Decision rule.** SUPPORTED if 1, 2, and 4 hold. REJECTED if a Round 2 query reads more than 10,000 result rows at
S2 or any count differs. Prediction 3 validates the fixture. Timings are reported descriptively only.

## R2-P2 — H01 follow-up with controlled planner statistics

**Rationale.** Round 2 analysis of the preserved H01 plans found that the 100,000-posting repeat spread coincided with
a plan change caused by stale statistics after the bulk fixture load (estimates of 1 and 44 rows for 100,000 actual
rows produced index scans with ~100,750 buffer accesses, versus a bitmap heap scan with ~3,250), and that the remaining
post-`ANALYZE` spread tracked shared-buffer state. H01's verdict stays INCONCLUSIVE; this is a separate experiment.

**Method.** Identical to H01 (histories 100 / 1,000 / 10,000 / 100,000 on a fresh source account; three repeats with
independent accounts; 10 warm-up and 100 timed `LedgerBalanceReader` reads; 30 real `TransferService.transfer` calls of
NZ$0.01) with one change: after each fixture load, `ANALYZE ledger_accounts`, `ANALYZE postings`,
`ANALYZE account_balance_projection`, and `CHECKPOINT` run before the plan capture and warm-up. Database
`ledgercore_r2_perf_p2`; harness method `Round2PerformanceFollowUpHarness.r2p2ControlledStatisticsBalanceHistory`.

**Predictions.**
1. For histories ≥ 10,000, all six saved plans use the same access path for `postings`, and each estimated row count is
   within a factor of 2 of the actual count.
2. At 100,000 postings, max/min of the three repeat p50 values is ≤ 1.5 for both the SQL read and the transfer service.
3. The 100 → 100,000 ratio of median p50 is ≥ 5× for both the SQL read and the transfer service.

**Decision rule.** SUPPORTED (material, stable history-dependent cost) if 1–3 hold. REJECTED if both ratios are below
2×. Otherwise INCONCLUSIVE, with the failing prediction explained.

## R2-P3 — H03 within-batch growth attribution and V7 overhead

**Rationale.** Round 2 re-analysis of H03's raw samples found that in the 5,000-item batches at one worker the median
per-item latency rose from 7.8–8.1 ms in the first execution-order decile to 21.4–21.8 ms in the last, while 500-item
batches stayed flat (~8–9 ms). Two history-dependent mechanisms are candidates: (a) every item's transfer aggregates the
payer's full posting history (source-balance check and payer projection rebuild), and the payer gains one posting per
item; (b) the pending-item claim walks the batch in sequence order past completed items.

**Method.** Build A = baseline export (V6, database `ledgercore_r2_perf_p3_base`); build B = Round 2 (V7, database
`ledgercore_r2_perf_p3_r2`). Identical harness method `r2p3PayrollWithinBatchGrowthAndOverhead` in each: three 500-item
batches, then one 5,000-item batch, each with a fresh payer and NZ$1.00 items to 5,000 pre-created personal accounts,
executed one item at a time through `PayrollItemExecutor.executeNext` (the H03 boundary). In the 5,000-item batch, at
0 / 1,250 / 2,500 / 3,750 / 4,999 completed items, the claim query and the payer balance aggregate are explained and
timed (20 measured executions each after 5 warm-ups).

**Predictions.**
1. Build B reproduces the growth: last-decile median per-item latency ≥ 2× the first-decile median in the 5,000-item
   batch, and ≤ 1.3× in each 500-item batch.
2. Attribution: between the first and last checkpoint, (2 × increase in payer-balance p50) + (increase in claim p50)
   accounts for at least 50% of the increase in median per-item latency (first vs last decile).
3. Overhead: build B's median of the three 500-item per-item p50 values is ≤ 1.15× build A's.

**Decision rule.** Each prediction is reported SUPPORTED or REJECTED with its numbers. No Round 2 production change is
conditional on R2-P3; it informs the Round 3 handoff.

## Commands

All commands run from `D:\workshop\nov\ledgercore` (uppercase drive letter; see `docs/v2/REPRODUCE.md`).

    # R2-P1 (per database: create from a V6 template, apply V7 for the v7 database, then)
    psql -X -v ON_ERROR_STOP=1 -d <db> -f evidence/round2/performance/scripts/r2-p1-fixture-target.sql
    psql -X -d <db> -v stage=S1 -v is_v7=<true|false> -f evidence/round2/performance/scripts/r2-p1-measure.sql
    psql -X -v ON_ERROR_STOP=1 -d <db> -f evidence/round2/performance/scripts/r2-p1-fixture-unrelated.sql
    psql -X -d <db> -v stage=S2 -v is_v7=<true|false> -f evidence/round2/performance/scripts/r2-p1-measure.sql

    # R2-P2 / R2-P3 (LEDGERCORE_TEST_DB_URL points at the ledgercore_r2_perf_* database; role granted pg_checkpoint)
    mvnw -f backend/pom.xml -Dtest=Round2PerformanceFollowUpHarness#<method> -Dsurefire.failIfNoSpecifiedTests=false
         -Dr2.evidence.dir=<absolute evidence/round2/performance path> -Dr2.run.label=<label>
         -Dr2.source.commit=<sha> test
