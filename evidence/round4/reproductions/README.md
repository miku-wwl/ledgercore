# Round 4 reproductions on unremediated code

All runs used synthetic data on disposable PostgreSQL 18.7 clusters (`127.0.0.1:55437`, one cluster per run under the
ignored `work/e6-lab/runs/<run>/`) with a separate bank database and simulator database, and two separate Java
processes. Raw per-run files (run state, checkpoint, process and PostgreSQL logs, SQL grades) stay in the ignored run
directories; the files here are the JSON summaries produced by `assessment/e6/run-clearing-matrix.ps1` and the
recovery harness.

## Exact build under test

| Artifact | SHA-256 |
|---|---|
| bank JAR built by `clean verify` of commit `94a2978be395c028560273c41f6d3f9eb0a14040` (Round 3 code + the three guarded E6 checkpoints, no fix) | `909ca49213c680e5…` (full value in each run's `run.json`) |
| clearing simulator JAR, unchanged since Round 3 | `4ad26cc595b4819f…` |

Runs B, C and D record `trackedTreeDirty: true` because remediation sources were being edited in the working tree while
the matrix ran. They executed the same JAR files as run A (identical SHA-256 in every `run.json`); no JAR was rebuilt
until all pre-fix runs had finished.

## Results

| Case | Run | Fault actually injected | After injection (independent SQL) | Recovery verdict | Reconciliation finding for the payment |
|---|---|---|---|---|---|
| R4-01-A `clearing-callback-commit-crash`, restore after the simulator's callback went DEAD | `1fb80896-3504-4824-94bf-e42a9c605b90` | JVM killed at `CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT` | SETTLED externally, PENDING_CLEARING locally, 0 settlement journals, payable 1,234 minor | **FAIL** — after restart: still no settlement, payable 1,234, callback event `DELIVERED` with only the `durable-fact-observer-v1` receipt | **none** (0 findings) |
| R4-01-B same, prompt restart | `721bd898-1feb-4d26-bcbb-1d3aec5a0dd5` | same checkpoint | same | PASS — only because the simulator redelivered the callback within its 8-attempt budget | none needed |
| R4-01-C `clearing-effect-session-kill` | `69879340-b4d5-48c6-8821-835076d9ab80` | no crash: the settlement session waiting on the reserve row lock was terminated with `pg_terminate_backend` (pid 25564) | the bank answered 200; the simulator marked the callback `DELIVERED`; 0 settlement journals | **FAIL** — unchanged 30 s later and after a restart | **none**, before and after the restart |
| R4-01-D `clearing-effect-precommit-crash`, restore after DEAD | `a3939874-527b-44e2-be29-70377d399ad3` | JVM killed at `CLEARING_EFFECT_POSTED_BEFORE_COMMIT` (journal written, transaction open) | rolled back: 0 settlement journals | **FAIL** — same stranded state as A | **none** |
| R4-01-E `clearing-effect-ack-crash`, prompt restart | `cd95de93-7a36-46d8-bda5-fd6cd3b619ac` | JVM killed at `CLEARING_EFFECT_COMMITTED_BEFORE_ACK` | settlement committed once | PASS (control: the duplicate redelivery is idempotent) | none |
| R4-04 `clearing-lost-callback` | `ecf5a9ab-72dd-4dbf-9653-ebd3527c5d6b` | bank stopped for longer than the simulator's delivery budget; signed inquiries moved the operation to SETTLED (sequence 3) meanwhile | sequence 2 `DEAD` after 8 attempts, sequence 3 blocked behind it; bank still ACCEPTED | **FAIL** — the operator's authenticated inquiry returned SETTLED/3 but re-sent nothing; payment stuck | `CLEARING_CALLBACK_MISSING` (HIGH) was detected; no recovery path existed |

The first attempt of R4-01-C (`dca35e0d-…`) is a harness defect, not a result: its session terminator polled
`pg_stat_activity` inside one transaction, whose statistics snapshot is frozen, so it never saw the waiting session.
The harness now calls `pg_stat_clear_snapshot()` on every iteration and the case was rerun with the same JARs.

## Red/green regression proof

`red-green/` holds the Surefire reports of `ClearingEffectFastPathFailurePostgresIntegrationTests` run against a
`git archive` export of `94a2978` (no fix): the test fails at the reconciliation assertion (empty finding list), and a
scratch-only variant without that assertion fails at the settlement assertion after durable dispatch (`expected: 1 but
was: 0`). `RED-94a2978-spring-swallowed-afterCompletion.txt` is the log line in which Spring's
`TransactionSynchronizationUtils` logged and swallowed the after-commit failure while the callback was still answered
`ACCEPTED`.
