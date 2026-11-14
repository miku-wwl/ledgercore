# Round 3 Starting Baseline

- Date: 2026-11-14 (Pacific/Auckland).
- Accepted source: `0db728171f7a28119ed4c4f19437896878f03fc6` on `main`.
- Remote: `origin/main` matched this SHA before the run.
- Working tree: clean before the run.
- Runtime: Temurin Java 21.0.13+9; PostgreSQL 18.7; Maven Wrapper 3.10.0.
- Cluster: Round 2 disposable cluster at `work/pg-round2-audit`, `127.0.0.1:55436`.
- Database: new `ledgercore_round3_baseline`, owner `ledgercore_r2`; it did not exist before this run.
- Persistent port 55432: not used. Existing Round 2 databases: preserved and not queried for this test.
- Command: `.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend\pom.xml clean verify` with `LEDGERCORE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55436/ledgercore_round3_baseline`, user `ledgercore_r2`, empty password; Java and Maven cache were set per `docs/v2/REPRODUCE.md`.
- Result: **BUILD SUCCESS**, 75 tests, 0 failures, 0 errors, 0 skipped; Maven total time 1:56.
- Migration: fresh schema applied V1–V7 successfully; PostgreSQL reported 18.7.
- Scope: baseline only. Round 3 implementation was not present during this run. Browser tests were not part of this baseline command.

The database is dedicated to baseline evidence and is not a migration or recovery test target.
