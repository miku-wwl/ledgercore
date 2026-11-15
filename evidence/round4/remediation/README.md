# Round 4 post-fix evidence

Same harness, cases and isolation as `../reproductions/` (two Java processes, separate bank and simulator databases on a
per-run PostgreSQL 18.7 cluster, real JVM kills or a real `pg_terminate_backend`, independent SQL grades), run against
the remediated build.

| Artifact | SHA-256 (prefix; full values in each run's `run.json`) |
|---|---|
| bank JAR built by `clean verify` of fix commit `d52920840452fabfab808c8801b0f21fdd08a846` | `5f2882680bb4d01e…` |
| simulator JAR of the same commit | `368dd4de76c3e200…` |

Runs A–E executed at `d529208` (clean tracked tree). The recovery cases and A* executed at `d185416`, which changes only
the lab harness (reserve command on an ephemeral port, optional fault-run poll interval); the JAR files are the same.

## Results

| Case | Run | After injection (SQL) | Recovery (SQL) |
|---|---|---|---|
| R4-01-A callback-commit crash, restore after DEAD | `7b9db041-b189-4060-a287-8d376b203a18` | settlement already **1**: while the after-commit fast path was frozen at the checkpoint, the durable worker applied the effect before the kill (receipt `clearing-financial-effect-v1`). The fault-window check therefore reports FAIL; financially correct. | PASS — one settlement, payable 0 |
| R4-01-A* same, dispatcher poll 60 s so the kill precedes the worker | `2896d3f6-7df0-4b70-8804-8ae73ffba01a` | PASS — 0 settlements, work item `PENDING` with 0 attempts, simulator callback failed with `ResourceAccessException` | **PASS** — the simulator's callback went `DEAD` after 8 attempts; 26.1 s after the restart (the bank's start-up time) the work item's first attempt posted the one settlement; no redelivery, no operator |
| R4-01-B callback-commit crash, prompt restart | `c27c6a84-6d36-402a-9786-f9cb83d4b785` | settlement already 1 (same race as A) | PASS |
| R4-01-C settlement session terminated (no crash) | `8a66907a-2e06-441a-92e3-603c748c3fbf` | PASS — exactly one settlement; the durable worker waited for the lock and completed after the fast path was killed | PASS |
| R4-01-D crash inside the uncommitted settlement, restore after DEAD | `5f36c5d0-ec42-43da-b5c5-7078a04f0262` | PASS — 0 settlements; the work item was `PROCESSING` (its worker held the lock and was killed) | PASS — after restart the lease expired, attempt 2 applied the settlement once; the simulator's callback was `DEAD`, so no redelivery was involved |
| R4-01-E crash before the acknowledgement | `7d2d48c0-e923-4a0a-af70-c5bdde213091` | PASS — settlement committed once | PASS — redelivered duplicate had no effect |
| R4-02 genuine reserve shortage | `d1bc003c-0510-4856-bac8-bd30cd6ed410` | PASS — lab sweep left the reserve at 5,000 minor; the 8,000-minor settlement was refused 5 times and dead-lettered; payable kept 8,000; one CRITICAL `CLEARING_RESERVE_INSUFFICIENT`; no settlement, no refund | PASS — after a server restart still owed (`CLEARING_FINANCIAL_EFFECT_PENDING` CRITICAL + `DEAD_OUTBOX_EVENT`); both resolutions refused with 409; audited replenishment of 100.00; two governed retries answered 200 and 400; exactly one settlement; reserve 7,000, treasury 85,000, assets 92,000 = liabilities 92,000; both exceptions resolved after a clean recheck |
| R4-04 lost callbacks | `f57893f2-33d6-4a78-8d9e-a091c231e91b` | PASS — bank down past the delivery budget: sequence 2 `DEAD`, sequence 3 blocked; bank still `ACCEPTED` | PASS — reconciliation detected `CLEARING_CALLBACK_MISSING`; its authenticated inquiry re-armed sequence 2, sequence 3 followed; all three delivered in order; one settlement; the exception resolved after a clean simulator recheck |

The A/B observation is itself evidence for the fix: a stuck or killed fast path no longer blocks or loses the effect,
because the durable worker owns it. A* and D are the runs that exercise the crash-then-restart path deterministically.
