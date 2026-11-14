# Round 2 annotations on Round 1 E3 evidence

These annotations record Round 2's independent reading of the preserved Round 1 evidence. They do **not** modify any
file under `evidence/e3/`; the Round 1 hypotheses, raw measurements, figures, and verdicts remain the historical
record. Each statement below cites the Round 1 file it was derived from. Round 2 measurements are kept separately in
this directory.

## Provenance checks (all confirmed)

| Round 1 claim | Check | Result |
|---|---|---|
| H03: 24 scenarios, 66,000 measured items | `h03-…/payroll-concurrency-summary.csv` | 24 rows; 12 × 500 + 12 × 5,000 successes = 66,000; all `COMPLETED`; `unique_transfers` equals item count in every row |
| H03/H02 global invariants zero | `h03-…/global-financial-invariants.json` | all five counters 0 |
| H04: 71,500 → 121,500 scanned rows, 962 → 1,361 buffers, 16.135 → 28.207 ms, target 5,000/0/0 | `h04-cross-org-progress-followup/commands-and-results.log` | matches the verdict table exactly |
| H01 ratios 126.4× (SQL) and 20.1× (service) | `h01-…/balance-history-summary.csv` | medians 0.8354 → 105.5857 ms and 10.9683 → 219.9671 ms reproduce both ratios |
| H02: 1,094.59 ops/s independent, 112.64 ops/s hot at 50 clients | `h02-…/transfer-concurrency-summary.csv` | medians of the three repeats match |

## H01 — the cause of the INCONCLUSIVE verdict

The verdict stays INCONCLUSIVE; that was the pre-registered outcome. The preserved plans identify why the repeats
disagreed:

- `query-plans/ledger-balance-history-100000-repeat-1.txt`: estimate 1,039 rows → Bitmap Heap Scan, 3,251 shared
  buffer hits, 54.9 ms.
- `…-repeat-2.txt` and `…-repeat-3.txt`: estimates **1** and **44** rows for 100,000 actual rows → plain Index Scan in
  `(ledger_account_id, journal_entry_id)` order, 100,751 / 100,744 buffer hits (one heap visit per posting, because
  random UUIDv4 journal IDs make index order unrelated to heap order), 116.8 / 116.2 ms.
- `query-plans-after-analyze/`: all three repeats become Bitmap Heap Scans with 98,964–101,655 estimates and
  3,245–3,266 buffers. Their residual time spread (60–114 ms) follows buffer residency (`read=3250 written=1247`
  versus all hits), not plan choice.
- `table-statistics-before.txt`: the only automatic analyze recorded is at 15:37:44 +13:00, after the H01 run began
  (15:34:39). The preserved files do not timestamp each repeat, so the statistics in effect for each plan cannot be
  reconstructed exactly; the 1- and 44-row estimates show that repeats 2–3 were planned without statistics that
  covered their own fixture rows.

So the repeat spread was produced by uncontrolled planner statistics and buffer state, not by intrinsic run-to-run
variance of the ledger path. The history-dependent cost itself is real: with fresh statistics every plan still reads
all of the account's postings (≈ 3,250 buffers per 100,000). Round 2 tested this in the separately pre-registered
R2-P2 (`verdicts/R2-P2-h01-controlled-statistics.md`).

## H02 — what the lock and latency columns measure

The data are sound; the verdict's attribution needs these qualifications:

1. **The database never saw more than ten sessions.** HikariCP's maximum was 10. In every hot-payer, opposing, and
   mixed scenario at 10, 30, and 50 clients, `pg_max_lock_wait_sessions` is exactly **9** (pool size minus the lock
   holder), while `hikari_max_pending` is 0, 20, and 40. Above ten clients the extra clients waited for a connection,
   not for the row lock.
2. **The hot-payer throughput decline is not database contention.** It fell from 158 to 113 operations/s between 10
   and 50 clients while database-side concurrency stayed constant at 10. The data cannot isolate the cause
   (JVM thread scheduling and CPU on the same laptop, pool hand-off, and the monitor below are candidates).
3. **`pg_lock_wait_samples` scales with run duration.** It counts monitor samples in which at least one session waited.
   Hot runs show about ten samples per second throughout (63 in a 6.3 s run, 227 in 22.9 s, 446 in 44.4 s), so the
   rising totals in the verdict reflect longer runs, not more intense waiting.
4. **The monitor perturbed the system it measured.** `DatabaseMonitor` opened a new unpooled JDBC connection for every
   sample (a new PostgreSQL backend process on Windows) and then slept 25 ms, giving ≈ 10 Hz instead of 40 Hz and extra
   CPU load on the measured host.
5. **Percentiles above the pool size describe the pool.** For independent pairs at 50 clients the p50 is 9.7 ms, lower
   than at 30 clients (17.4–25.9 ms), with p95 144 ms. Raw samples (repeat 1) show per-client medians of 8.5–12.0 ms for
   all 50 clients and 19% of operations above 50 ms: a bimodal pattern consistent with connection hand-off scheduling.
   The hot payer, in contrast, is a strict queue: per-client medians 412–457 ms ≈ 48 × 8.9 ms service time.

Unchanged conclusions: independent pairs out-scale one hot payer; zero deadlocks with opposing directions; all
financial correctness counters zero. Round 2 did not re-run H02, because these qualifications change its
interpretation, not its decision.

## H03 — scope of the payroll measurement

- Every item in a batch debits the same payer, so H03 is a hot-row workload; lock waiters are again capped at 9.
- The harness drives `PayrollItemExecutor.executeNext` directly. It bypasses `PayrollExecutionService`
  (authorization, `markItemFailed`, per-call `finishIfReady`), and any failure aborts a worker, so no payroll failure
  path was measured under concurrency. It is a service-level item-throughput measurement, not an HTTP or browser
  benchmark, and it includes Hikari waiting at 30/50 workers.
- **New observation from the raw samples** (`payroll-concurrency-samples.csv`): in the 5,000-item batches at one worker
  the median per-item latency rises steadily by execution-order decile — 7.8, 9.3, 10.9, 12.5, 13.9, 15.7, 17.5, 18.7,
  19.8, 21.4 ms (repeat 2; repeat 3 is within 0.4 ms) — while 500-item batches stay at 7.8–9.8 ms. This is analysed in
  the pre-registered R2-P3 (`verdicts/R2-P3-h03-within-batch.md`).
- The progress-query part of the MIXED verdict is remediated by V7 (finding R2-09; R2-P1).

## H04

The raw log matches the verdict. H04's fixture used DRAFT-only unrelated batches and a PENDING-only target; R2-P1 used
a PROCESSING target with mixed statuses and confirmed the same global-scan behaviour for the Round 1 SQL before
measuring the Round 2 SQL.
