# E12 Source Freeze — the verifiable publication input

**Frozen accepted source: `2c25ef459f2e49bcc1a8b237a1a652d7c205fbcd`** (`main`), accepted by E12 as
**ACCEPTED_WITH_NONBLOCKING_LIMITATIONS** (`E12-ACCEPTANCE.md`).

The V2 textbook and every later reference to "the accepted LedgerCore V2 source" mean this commit, not a moving `main`
HEAD. Commits after the freeze may change only documentation, evidence and publication sources (`docs/`, `evidence/`,
`manual/`). Any later change to application code, migrations or materially relevant tests invalidates this freeze, the
E12 acceptance and every publication reference to it, and requires a new E12 run.

## 1. What is frozen

| Item | Value |
|---|---|
| Accepted commit | `2c25ef459f2e49bcc1a8b237a1a652d7c205fbcd` |
| Last application-code change | `6119274cd0c91fb5a2183267bc2a626ebeaf02a8` (Operations console metrics, accessibility subset, E12 harness); `2c25ef4` changed only `assessment/e12/run-e12-lab.ps1`, `assessment/e12/README.md` and added superseded attempt-1 evidence |
| Bank migrations | Flyway V1–V15; V1–V11 unchanged (checksums pinned in `MigrationCompatibilityPostgresIntegrationTests`); Round 4 added V12–V15 |
| Simulator migrations | V1–V5; Round 4 added V4 (trace context) and V5 (redelivery after a bank restore); V3 was added in Round 4 commit `d529208` |
| Bank JAR built from it (E12 runs) | SHA-256 `b76031d679f87fe5471ea52c4b16f66d49b0cc6556e68776b767d038e6a77c87` |
| Simulator JAR built from it (E12 runs) | SHA-256 `7aa70c06a4c6f80284343d6e45e422e7ca3c3647fa86bd3aea81e18998872384` |

## 2. Exact-SHA CI

| Commit | Content | CI run | Result |
|---|---|---|---|
| `94a2978` | R4-01 instrumentation (checkpoints, two-process fault lab) | [43680382999](https://github.com/miku-wwl/ledgercore/actions/runs/43680382999) | success |
| `d529208` | R4-01/02/03/04/05 fixes (V12, V13, durable effects, finite reserve, verified resolution, simulator V3) | [43683368640](https://github.com/miku-wwl/ledgercore/actions/runs/43683368640) | success |
| `d185416` | Reserve lab on an ephemeral port; fault-run outbox poll option | [43684838736](https://github.com/miku-wwl/ledgercore/actions/runs/43684838736) | success |
| `292976b` | E5–E9 closure: W3C trace context (V14, simulator V4), alerts, E7/E8 matrices, R4-06 (V15), R4-07 (simulator V5) | [43687665348](https://github.com/miku-wwl/ledgercore/actions/runs/43687665348) | success |
| `6119274` | Operations console §18 metrics, accessibility subset, E12 harness fixes | [43784966787](https://github.com/miku-wwl/ledgercore/actions/runs/43784966787) | success |
| **`2c25ef4`** | **Lab runner fix; attempt-1 evidence kept — frozen** | [**43786857336**](https://github.com/miku-wwl/ledgercore/actions/runs/43786857336) | **success** (both jobs) |

Each run executed: npm audit (high), frontend lint/build, backend and simulator `clean verify` on PostgreSQL, simulator
and bank start-up, generated OpenAPI TypeScript contract comparison, all Playwright journeys, and the Maven OSV scan.

## 3. Test environment and counts at the frozen commit

Local E12 (Windows 11; Temurin 21.0.13+9; PostgreSQL 18.7; fresh `ledgercore_e12_*` databases on `127.0.0.1:55460`;
per-run disposable clusters on `127.0.0.1:55437`; tracked tree clean in every run):

| Suite | Result |
|---|---|
| Backend `clean verify` | 118 passed, 0 failed, 0 errors, 0 skipped |
| Simulator `clean verify` | 9 passed, 0 failed, 0 errors, 0 skipped |
| Playwright (real bank + real simulator + isolated PostgreSQL) | 9 passed |
| Fault lab (11 cases) | 11 injections observed, 11 recoveries PASS, cleanup confirmed for each |
| Restore (E12-G02) | PASS; RPO 5.274 s, RTO 22.963 s (service) / 26.201 s (reconciled) |
| Alert lab (E12-H03) | PASS (both rules fired on a genuine shortage of 3000 minor units and resolved) |
| Trace chain (E12-H04) | complete, 123 spans / 108 traces in the run |
| SQL grader | zero hard-gate violations in every graded database |

## 4. E12 case and grader manifest (SHA-256 of the files at the frozen commit)

| File | SHA-256 |
|---|---|
| `assessment/e12/cases.json` (36 cases, 25 hard gates) | `cab39af965258dfc0f325ceb818511568206d758fa7d3be409d6bbe811312073` |
| `assessment/e12/graders/financial-hard-gates.sql` | `e46e89d222910737ced8ce31f0c353baadbee4302e12eb49d455773ccee84b87` |
| `assessment/e12/run-e12.ps1` | `6fa547b990783b639892f93dca6b6533e257ebdd9dea8b9a57ba2cc2f5c4d642` |
| `assessment/e12/run-e12-lab.ps1` | `c151fe2ff7de98f3ce1a7122cd5007dda84b7d860e9a7ef8f0f77d56aa3135c7` |
| `assessment/e12/run-e12-restore.ps1` | `75b9bbda8cdf4ddfe3b0ed6c7aa9eae71b501cbccefb216f11df738e4c196cb7` |
| `assessment/e9/alert-lab.ps1` | `131505f6480eb58ee8143ac8833f3c02c7b3e16d3bea85e899b67732da5cafa6` |
| `assessment/e9/extract-traces.py` | `e3f60da025dcc69b34b248b68baa339a4c53b7cbba3045991cc71ae465e18086` |
| `assessment/e9/ledgercore-round4-alerts.yml` | `bff0904018046b6ba5f07622d2dc30c78c52af319b07a04340135fc1ae0c7af7` |
| `assessment/e6/ClearingLab.Common.ps1` | `965c21a79ad5701b0bb8241cf3ab074b50856b33c8edf17c38a2369e41bf6f0f` |

Results: `evidence/round4/e12/E12-RESULTS.json`, `evidence/round4/e12/runs/`, `evidence/round4/e12/lab/`,
`evidence/round4/e5-e9/`.

## 5. Unresolved nonblocking risks

See `E12-ACCEPTANCE.md` § 8 and `ROUND-4-HANDOFF.md`: R4-05 re-send path (DEFERRED_WITH_RISK); shared HMAC key without a
direction label (P3); single-person acknowledgement of message-derived CRITICAL exceptions; logical (not PITR) restore
evidence for Round 4; accessibility subset rather than an axe/WCAG audit; unexecuted fault classes; measured but
unoptimized hot-account and cross-organization scan costs; a toy clearing simulator.

## 6. Commits after the freeze

| Commit | Scope | CI |
|---|---|---|
| (recorded in `ROUND-4-HANDOFF.md`) | documentation, evidence and the V2 textbook only | exact-SHA runs listed there |
