# H02 verdict — financial transfer concurrency

**Verdict: SUPPORTED; financial correctness gates passed for all recorded scenarios.** Three repetitions covered independent account pairs, a single hot payer, opposing-direction pairs, and an 80% funded / 20% intentionally insufficient mix at concurrency 1, 10, 30, and 50.

## Throughput and p95 medians

| Workload | Clients | Operations/s | p95 ms |
|---|---:|---:|---:|
| Independent account pairs | 1 | 83.33 | 15.32 |
| Independent account pairs | 10 | 702.24 | 21.02 |
| Independent account pairs | 30 | 939.50 | 69.66 |
| Independent account pairs | 50 | 1,094.59 | 145.26 |
| One hot payer | 1 | 144.37 | 8.59 |
| One hot payer | 10 | 158.16 | 74.95 |
| One hot payer | 30 | 131.39 | 307.67 |
| One hot payer | 50 | 112.64 | 606.04 |
| Opposing directions | 50 | 109.48 | 743.35 |
| 80% funded / 20% insufficient | 50 | 108.94 | 680.76 |

At 30 and 50 clients, independent-pair throughput clearly exceeds the hot-payer workload. The hot-payer throughput declines after 10 clients while p95 rises; sampled lock waits increase (up to 449 samples in a repeat at 50 clients). Hikari is capped at 10 active connections and queues up to 40 clients at concurrency 50, so these local numbers include pool contention.

## Correctness observations

- All 48 scenario/repeat rows had zero unexpected failures, transfer invariant violations, projection mismatches, negative customer balances, and deadlock-counter increments.
- The mixed workload recorded the intended 20% insufficient requests without overdrafts or unexpected posting failures.
- Opposing-direction scenarios completed with zero PostgreSQL deadlocks.
- The global post-workload invariant record contains all zero counts: unbalanced journal entries, projection mismatches, negative ledger balances, receipt mismatches, and duplicate idempotency keys.

## Evidence and limits

- Raw and summarized samples: `measurements/h02-transfer-concurrency-20261114T024011Z/`.
- First-page and corrected keyset plans use the source/target transfer indexes. The keyset query returned 11 rows after a 2,499-row filtered set at the saved exact microsecond cursor.
- An intermediate manual keyset plan with cursor precision lost by PowerShell is retained as `transaction-history-keyset-page-initial-invalid-cursor-precision.txt`; use `transaction-history-keyset-page-corrected.txt` and the Java-captured plan for interpretation.
- Figure generated from the CSV: `analysis/figures/h02-transfer-concurrency.png`.

The runs use local synthetic workloads and exclude HTTP, TLS, browser latency, and remote database/network time.
