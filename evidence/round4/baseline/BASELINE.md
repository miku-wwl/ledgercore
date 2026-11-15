# Round 4 baseline reproduction

**Date:** 2026-11-15 (NZDT). **Operator:** Claude Opus 5.5 (Round 4). **Source:** `main` = `origin/main` =
`ba36f90957fc65f83cc83112a4ba35f6cfc1bf55`, tracked tree clean, single branch `main`, one worktree, remote
`git@github.com:miku-wwl/ledgercore.git`. A peer coding session for this repository existed but was idle; no other agent
wrote to the worktree during Round 4.

## Environment

| Item | Value |
|---|---|
| JDK | Temurin 21.0.13+9 (`work/toolchain/jdk21`) |
| Maven | wrapper 3.10.0, local repository `work/m2` |
| PostgreSQL | 18.7 (x86_64-windows, msvc-19.44), new disposable cluster `work/round4-lab/pg-r4` on `127.0.0.1:55450`, trust auth on loopback only, role `ledgercore_r4` |
| Untouched services | `55432` (persistent owner service), `55434`, `55436`, `55438` (earlier rounds) |

```powershell
initdb -D work\round4-lab\pg-r4 -U postgres --auth-local=trust --auth-host=trust --no-locale --encoding=UTF8
# postgresql.conf: listen_addresses = '127.0.0.1', port = 55450
psql -h 127.0.0.1 -p 55450 -U postgres -c "CREATE ROLE ledgercore_r4 LOGIN"
psql -h 127.0.0.1 -p 55450 -U postgres -c "CREATE DATABASE ledgercore_r4_baseline OWNER ledgercore_r4"
psql -h 127.0.0.1 -p 55450 -U postgres -c "CREATE DATABASE ledgercore_r4_sim_baseline OWNER ledgercore_r4"
$env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55450/ledgercore_r4_baseline'
$env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_r4'; $env:LEDGERCORE_TEST_DB_PASSWORD = ''
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend\pom.xml clean verify
$env:CLEARING_SIMULATOR_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55450/ledgercore_r4_sim_baseline'
$env:CLEARING_SIMULATOR_TEST_DB_USERNAME = 'ledgercore_r4'; $env:CLEARING_SIMULATOR_TEST_DB_PASSWORD = ''
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f clearing-simulator\pom.xml clean verify
```

## Results

| Check | Result |
|---|---|
| Backend `clean verify` | **BUILD SUCCESS — 95 tests, 0 failures, 0 errors, 0 skipped** (3 min 04 s) |
| Simulator `clean verify` | **BUILD SUCCESS — 6 tests, 0 failures, 0 errors, 0 skipped** |
| Flyway history (bank) | V1–V11 applied, all `success = t`; checksums: V1 −1500817779, V2 1445661118, V3 −1568142027, V4 512865030, V5 −1572619169, V6 −371991565, V7 638694306, V8 −1628563391, V9 −1866598791, V10 794183703, V11 1670107448 |
| Integrity triggers | 35 triggers on 13 tables (audit, callback inbox, instructions, payment intents, idempotency, journals, consumer receipts, outbox, payroll batches/items/results, postings, transfers) |
| Balanced journals | 1,263 journals, 0 unbalanced, after all 95 tests |
| Reserve identity | `SIMULATION_RESERVE` 61,400,650 = 606 customer deposits 61,400,350 + `CLEARING_PAYABLE` 300 (minor units) — the identity that makes the insufficient-reserve branch unreachable (R4-02) |
| Handoff CI | run 43628403609 (`ba36f90`) success |

The expected migration-refusal log line (`V7` refusing inconsistent payroll history in an isolated schema) appears
during `MigrationCompatibilityPostgresIntegrationTests` and is the tested behaviour, not a failure.
