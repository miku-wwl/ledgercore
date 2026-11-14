# LedgerCore V2 Plan B — Round 1 handoff

**Outgoing owner:** GPT-6 Luna
**Incoming owner:** Claude Opus 5.5
**Repository:** `https://github.com/miku-wwl/ledgercore`
**Handoff date:** 2026-11-14
**Working branch:** `main` only

## 1. Scope and stop point

Round 1 covered E0–E3: V1 baseline audit, corporate identity and authorization, durable payroll, and financial concurrency/performance experiments. The round is complete and recorded in [`STATUS.md`](STATUS.md), [`ACCEPTANCE-MATRIX.md`](ACCEPTANCE-MATRIX.md), and the evidence tree under [`evidence/e3`](../../evidence/e3).

Only Round 1 was authorized. E4–E12 and final V2 PDF production were not started. The V1 implementation and V1 textbook remain the compatibility baseline. The next engineering action belongs to Claude Opus 5.5: independently audit E0–E3, remediate or explicitly disposition findings, then stop before E4 until Round 2 is dispositioned.

## 2. Binding plan and repository state

The four-round model/scope assignment and main-only rules are in [`EXECUTION-PLAN.md`](EXECUTION-PLAN.md). All Round 1 source, experiment, evidence, and documentation commits were made directly on `main`; no branch, worktree, PR, detached HEAD, force-push, or history rewrite was used.

Key E3 commits:

| Commit | Contents | CI |
|---|---|---|
| `0242cf9de89243047af66e23ef9d5cf87c47e9f7` | H01–H03 pre-registration and workload design | Run [43543947585](https://github.com/miku-wwl/ledgercore/actions/runs/43543947585) succeeded |
| `137fa983751943c89873b1df33c299a8d6c2507d` | Opt-in E3 Spring performance harness and main-only runner | Its first CI run was canceled after the follow-up fix was pushed |
| `230e5873ba7f0d1c7df546eb1e81622fa12a8cc0` | Correct environment capture; application source measured by H01–H04 | Run [43546776607](https://github.com/miku-wwl/ledgercore/actions/runs/43546776607) passed full-stack acceptance and OSV scan |
| `81a903876ea3ac3214113dbf7bacb86f40307454` | Supplemental H04 pre-registration and guarded SQL/runner | Run [43550956746](https://github.com/miku-wwl/ledgercore/actions/runs/43550956746) passed |
| `9e7a4421c5ee7f4bdc69cce3cfbb3c1503e8078a` | Complete E3 evidence, verdicts, figures, final status, and Round 1 handoff | Run [43552848920](https://github.com/miku-wwl/ledgercore/actions/runs/43552848920) passed full-stack acceptance and OSV scan |

H04's runner metadata and output-directory corrections were made only after its one-time measurement. They do not change the measured application source baseline (`230e5873...`) or its raw results. The environment correction note is in [`measurements/h04-cross-org-progress-followup/corrections.md`](../../evidence/e3/measurements/h04-cross-org-progress-followup/corrections.md).

## 3. Round 1 outcomes

### E0 — validated

Baseline audit, migration risks, financial invariants, and the binding four-round plan were recorded. E0 commit `a7fe0de280b61b66e87cf14c95aa4cf30d0982b8` passed CI run [43531591907](https://github.com/miku-wwl/ledgercore/actions/runs/43531591907).

### E1 — validated

Corporate ownership, memberships/roles, server-derived authorization, source-owner audit fields, and minimal organization workflows are implemented in V5 and the organizations module. PostgreSQL and Chromium coverage, including cross-organization denial and Maker/Checker, passed. Implementation commit `eab8175501e77dfc57b1d9ea404a73ed86c8ae26`; CI run [43536790093](https://github.com/miku-wwl/ledgercore/actions/runs/43536790093) passed.

### E2 — validated

V6 adds durable payroll intent/results, content-digest approval binding, Maker/Checker separation, per-item transactional transfer execution, recovery, and progress. The 500-item payroll tests and an application kill/restart recovery experiment passed; full-stack CI run [43543154101](https://github.com/miku-wwl/ledgercore/actions/runs/43543154101) passed. Details are in [`evidence/e2/VALIDATION.md`](../../evidence/e2/VALIDATION.md) and [`ADR/0003-durable-payroll-intent-and-item-execution.md`](ADR/0003-durable-payroll-intent-and-item-execution.md).

### E3 — validated with explicit result limits

The harness executes real Spring service proxies against the separate PostgreSQL 18.7 database `ledgercore_e3_perf`, including transaction commit in service timings and excluding HTTP. The machine, Java 21.0.2 runtime, database settings, Hikari size 10, and isolation are recorded in [`environment/environment.json`](../../evidence/e3/environment/environment.json). All original H01–H03 preregistrations remain unchanged. A supplemental H04 was preregistered before adding its fixture.

| Experiment | Result | Reviewer-facing finding |
|---|---|---|
| H01 ledger history | **INCONCLUSIVE** by its pre-registered rule | From 100 to 100,000 postings, median p50 grew 126.4× for the isolated balance SQL and 20.1× for `TransferService`. At 100,000, repeat ranges were about 2.3× and the original plans changed across repeats. No production write-path optimization was made. See [`H01 verdict`](../../evidence/e3/verdicts/H01-ledger-balance-history.md). |
| H02 transfer concurrency | **SUPPORTED**; correctness gates passed | At 50 clients, independent pairs reached median 1,094.59 ops/s and p95 145.26 ms; the one-hot payer reached 112.64 ops/s and p95 606.04 ms. Opposing-direction deadlocks, unexpected failures, invariant violations, projection mismatches, and negative balances were all zero. See [`H02 verdict`](../../evidence/e3/verdicts/H02-financial-concurrency.md). |
| H03 payroll and plans | **MIXED** | 24 payroll scenarios completed 66,000 measured items without financial invariant violations. Claim, history, and authorization plans used expected indexes in the measured database. The original 5,000-item progress query scanned all 71,500 global result rows. See [`H03 verdict`](../../evidence/e3/verdicts/H03-payroll-and-query-plans.md). |
| H04 cross-organization progress | **SUPPORTED** supplemental finding | Adding 50,000 DRAFT results from ten other organizations raised the target progress scan from 71,500 to 121,500 rows, while target counts stayed 5,000 pending. Shared hits rose from 962 to 1,361. No H04 payroll execution, transfer, journal entry, or posting was created. See [`H04 verdict`](../../evidence/e3/verdicts/H04-cross-organization-payroll-progress.md). |

H02/H03 global financial invariant JSON and the H04 post-fixture check report zero unbalanced entries, projection mismatches, negative ledger balances, transfer receipt mismatches, and duplicate idempotency keys. H04 payer accounts have zero postings. Raw CSVs, plans, and output logs are preserved under [`evidence/e3/measurements`](../../evidence/e3/measurements). Four figures are generated from those saved measurements by [`plot-e3-results.py`](../../evidence/e3/scripts/plot-e3-results.py); see [`analysis/SUMMARY.md`](../../evidence/e3/analysis/SUMMARY.md).

The local machine, cache state, synthetic data, small sample counts, and 10-connection pool limit prohibit production capacity claims. The authorization plan uses a small dataset. No external clearing, real money, or remote production environment was involved.

## 4. Final validation record

- E3 explicit H01, H02, and H03 harness methods completed successfully; their raw samples and plans are preserved. The harness is opt-in and excluded from ordinary Surefire method discovery; `clean verify` compiles it.
- `mvn clean verify` passed against fresh disposable database `ledgercore_round1_final_test`: **49 tests, 0 failures, 0 errors, 0 skipped**.
- `npm run lint` and `npm run build` passed.
- H04 post-fixture SQL rechecked the five global financial invariant classes; all counts were zero.
- Four result figures regenerated from the recorded CSV and H04 log.
- Detailed record: [`evidence/e3/validation/LOCAL-VALIDATION.md`](../../evidence/e3/validation/LOCAL-VALIDATION.md).

The first final regression attempt used the previously existing `ledgercore_test` database and failed Flyway validation because its stored V5 checksum was stale (`2096504864` versus current source checksum `-1572619169`). No repair or mutation of that database was performed. The final regression ran on the fresh isolated database and passed.

## 5. Claude Opus 5.5 review queue

1. Challenge the H01 INCONCLUSIVE verdict and the large 100,000-posting p50 increase. Inspect original pre-`ANALYZE` plans, post-`ANALYZE` plans, and repeat ranges before deciding whether an incremental projection candidate merits a separately pre-registered test. Preserve the double-entry ledger as authority and require rollback, concurrency, idempotency, overdraft, and independent-reconciliation gates for any candidate.
2. Inspect `PayrollService.countsFor` and decide how batch progress should avoid scanning unrelated tenants' result rows. H04 captures the current query's global row scan and buffers. Any query or index fix should add a PostgreSQL regression fixture with other organizations, then rerun payroll correctness tests.
3. Review H02 lock sampling and Hikari saturation. The results demonstrate the measured local contention pattern, but not production throughput.
4. Audit E0–E3 financial invariants, authorization isolation, transaction boundaries, migration compatibility, and evidence provenance independently. H04's unrelated rows are synthetic DRAFT query fixtures, not executed payroll.
5. Record each finding as remediated or explicitly dispositioned in Round 2 before the owner starts E4.

## 6. Reproduction and preserved work

See [`REPRODUCE.md`](REPRODUCE.md) for the safe fresh-database workflow. Never rerun H01–H04 against the database containing the preserved fixture; create a new disposable cluster/database. H04 SQL and runner guard against duplicate fixture creation. The test database with the stale migration history was left untouched. The performance database contains preserved H01–H03 workload rows and the H04 DRAFT-only fixture; do not treat it as a clean replay database.

**Round 1 ends here. No E4–E12 implementation or final V2 textbook/PDF work is authorized in this handoff.**
