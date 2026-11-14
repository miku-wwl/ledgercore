# H02 — Transfer behavior under independent and hot-account concurrency

## Research question

How do independent account pairs and a shared hot payer behave as transfer client concurrency rises, and do ordered ledger locks prevent opposing-direction deadlocks?

## Source-grounded basis

`LedgerWriter.lockLedgerAccounts` sorts distinct ledger account UUIDs before `FOR UPDATE`. `TransferService` holds these locks through source-balance validation, posting insertion, transfer receipt creation, idempotency finalization, and projection refresh. Shared-payer operations necessarily contend on one ledger row; independent pairs should avoid that hot-row queue.

## Predictions

1. Independent pairs will achieve higher throughput than a single hot payer at concurrency 30 and 50.
2. Hot-payer p95 lock wait and operation latency will rise from concurrency 1 to 30/50, while successful transfer throughput will plateau before the independent-pair workload.
3. Opposing-direction transfers will complete without PostgreSQL deadlocks because both directions lock ledger rows in the same UUID order.
4. Under the funded-success workload, all intended requests will post; under the mixed workload, insufficient-funds requests will produce no transfer or posting and will not make a balance negative.

## Variables and method

- Independent variables: client concurrency 1, 10, 30, and 50; distribution (independent payer/payee pairs, one hot payer, opposing directions, and 80% funded / 20% deliberately insufficient requests).
- Dependent variables: service-transaction throughput, p50/p95/p99 latency, classified error rate, sampled PostgreSQL lock waits, deadlock counter delta, Hikari active/max connections, and JVM CPU time.
- Each concurrency/configuration: 100 operations per client, three repeats, unique actor-scoped idempotency keys. Fixed amount NZ$0.01 for funded transfers; deliberate insufficient case NZ$2,000.00 from a NZ$1,000.00 account.
- Use the actual Spring `TransferService` proxy from concurrent Java workers, so each timing includes transaction begin, JDBC/application work, commit, and task scheduling but excludes HTTP/browser/network latency.
- Capture PostgreSQL lock/deadlock counters before and after each run. Keep independent and hot-account datasets separate.

## Acceptance and interpretation

- Financial acceptance is strict: all recorded journal entries balance, no funded source has a negative ledger balance, no request key creates more than one transfer, projections match authoritative postings, and the opposing-direction run records zero deadlocks.
- Support the hot-account prediction only if measured throughput and latency distinguish the hot workload from independent pairs and lock-wait samples identify the payer row contention; report exact values and repeat variation.
- A failure of throughput to scale is not by itself a correctness failure. Report the measured pool/CPU/database limit and the contention point.

## Threats to validity

This service-level workload excludes HTTP, TLS, browser rendering, and remote network time. Thread scheduling and Hikari limits may dominate before PostgreSQL does. A local run cannot represent production traffic or machine capacity.
