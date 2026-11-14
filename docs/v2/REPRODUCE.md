# Reproducing the Round 1 Baseline

All commands are Windows PowerShell and run from D:\workshop\nov\ledgercore. Use only the disposable LedgerCore test cluster on 127.0.0.1:55434; do not point these commands at the persistent service on 55432 or any user business database.

## Verified local toolchain

- Temurin Java 21.0.13+9.
- Node v26.5.0 and npm 12.0.1.
- PostgreSQL 18.7, disposable cluster at work/pg-v2-round1-e0, database ledgercore_test, port 55434.
- Playwright 1.65.0 with its installed Chromium build.
- Maven dependency cache at work/m2.

## Backend

Set the process-local environment and run the full baseline:

    $env:JAVA_HOME = (Resolve-Path 'work\toolchain\jdk21\jdk-21.0.13+9').Path
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $env:MAVEN_USER_HOME = (Resolve-Path 'work\maven-home').Path
    $env:MAVEN_OPTS = '-Dmaven.repo.local=D:\workshop\nov\ledgercore\work\m2'
    $env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55434/ledgercore_test'
    $env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_test'
    $env:LEDGERCORE_TEST_DB_PASSWORD = ''
    .\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend\pom.xml clean verify

Observed: exit 0, 42 tests passed, no failures, errors, or skips; Flyway validated four migrations; PostgreSQL reported 18.7.

## Frontend and API contract

From frontend:

    npm run lint
    npm run build

Start the packaged application against the same disposable cluster with LEDGERCORE_DB_URL, LEDGERCORE_DB_USERNAME, LEDGERCORE_DB_PASSWORD, and LEDGERCORE_COOKIE_SECURE=false set in the launching process. Use port 8080. Confirm /actuator/health is UP, then run:

    npm run generate:api
    git diff --exit-code -- src/api/schema.d.ts

Observed: health UP; generated OpenAPI TypeScript types matched the tracked file.

## Browser and dependency checks

Keep the backend running on port 8080. From frontend, run:

    npm run test:e2e
    npm audit --registry=https://registry.npmjs.org --audit-level=high

Observed: Chromium E2E 1 passed; npm audit exit 0 with 2 low and 4 moderate findings, no high or critical findings. The audit threshold is high; findings remain recorded, not hidden.

The existing main CI baseline is run 43386644262 for source commit 5eb6a626242bce79b5314be40b286819bdd9ac52. Its verify and OSV dependency-scan jobs succeeded. This is baseline evidence, not CI evidence for a later V2 commit.

## Isolation and cleanup

The temporary 55434 cluster is separate from the persistent PostgreSQL service on 55432. Confirm database name, user, port, and cluster directory before any write or destructive experiment. Keep development, integration, performance, and destructive experiments in distinct databases. Never drop or recursively delete a directory based only on a computed path. For later stages, add the exact start/stop and cleanup commands only after verifying the cluster configuration and target paths.

## E3 performance experiments

The preserved E3 run used the environment recorded in `evidence/e3/environment/environment.json`: Java 21.0.2, PostgreSQL 18.7, database `ledgercore_e3_perf`, Hikari maximum 10, and the local host recorded there. `evidence/e3/hypotheses/` and `evidence/e3/workloads/workload-specification.json` contain the pre-registered design. The measured application source is `230e5873ba7f0d1c7df546eb1e81622fa12a8cc0`.

To reproduce the full workload, start with a fresh, disposable PostgreSQL 18.7 cluster on port 55434 and a new `ledgercore_e3_perf` database owned by the local `ledgercore_test` role. Never reset or drop the database containing the committed results. The E3 runner applies the project's Flyway schema through the test application and then executes the selected harness phase:

    .\evidence\e3\scripts\run-e3.ps1 -Phase all

This runs environment capture, H01, H02, and H03 in order. It is a write workload and is intended to run once per fresh database. H04 requires the H03 `E3 plan-5000` fixture and should be run afterward:

    .\evidence\e3\scripts\run-h04-cross-org-progress.ps1
    .\work\postgresql-binaries\pgsql\bin\psql.exe -X -w -h 127.0.0.1 -p 55434 -U ledgercore_test -d ledgercore_e3_perf -f evidence\e3\scripts\check-h04-post-fixture-invariants.sql

The H04 SQL refuses to add duplicate fixtures. Each H04 runner invocation gets a timestamped output directory; select a fresh database rather than rerunning a workload in the database that already contains evidence fixtures.

Generate the figures from the preserved result files with:

    python evidence\e3\scripts\plot-e3-results.py

The figures use Matplotlib; the version present when this Round 1 set was generated was 3.10.6. The exact per-run samples, plans, SQL output, and environment metadata remain authoritative. These synthetic local results are not production capacity estimates.

## Round 2 additions (independent audit)

### Use a separate disposable cluster and always set the test datasource

Round 2 used its own cluster so the preserved Round 1 databases on 55434 stayed untouched:

    work\postgresql-binaries\pgsql\bin\initdb.exe -D work\pg-round2-audit -U postgres -A trust -E UTF8 --locale=C
    # add to work\pg-round2-audit\postgresql.conf: listen_addresses = '127.0.0.1', port = 55436
    work\postgresql-binaries\pgsql\bin\pg_ctl.exe -D work\pg-round2-audit -l work\pg-round2-audit.log -w start
    psql -h 127.0.0.1 -p 55436 -U postgres -c "CREATE ROLE ledgercore_r2 LOGIN"
    psql -h 127.0.0.1 -p 55436 -U postgres -c "CREATE DATABASE ledgercore_r2_<purpose> OWNER ledgercore_r2"

`application-test.properties` no longer has a default URL (finding R2-13). Set `LEDGERCORE_TEST_DB_URL`,
`LEDGERCORE_TEST_DB_USERNAME`, and `LEDGERCORE_TEST_DB_PASSWORD` for every Maven run; an unset URL fails fast instead
of reaching the persistent service on 55432. Round 2 result: `clean verify` on a fresh database runs 75 tests
(49 Round 1 + 19 payroll integrity + 7 migration compatibility) with 0 failures.

### Run local browser tests from the `D:\` path

On this Windows host, starting Vite from a lower-case `d:\workshop\...` working directory made the Vite 8 dependency
optimizer bundle a second copy of React into the `@base-ui` chunks, and every E2E test failed with
"Invalid hook call". Run the dev server and Playwright from `D:\workshop\nov\ledgercore\frontend` (upper-case drive
letter). Do not delete `node_modules\.vite` while a shell's working directory is inside it (Windows `EBUSY`). CI on
Linux is unaffected.

### Migration compatibility and the stale Round 1 database

`MigrationCompatibilityPostgresIntegrationTests` migrates isolated schemas inside the test database: fresh to latest,
populated V4 → latest, populated V5 → latest, populated V6 → V7, and a corrupted V6 that V7 must refuse. It also pins
the V1–V6 Flyway checksums. The Round 1 database `ledgercore_test` on 55434 contains a pre-commit draft of V5
(finding R2-04): discard it; never run `flyway repair` against it.

A populated V4 database can only be upgraded by an application build that contains
`LegacyTransferOwnerBackfillCallback` (finding R2-08; ADR 0004).

### Reproductions and performance follow-ups

- SQL reproductions: run each script in `evidence/round2/reproductions/` with `psql -X -v ON_ERROR_STOP=0 -f <script>`
  against a fresh database migrated to V6 (R2-01, R2-02) or an empty database (R2-08).
- Performance follow-ups R2-P1–R2-P3: commands and decision rules are in
  `evidence/round2/performance/PRE-REGISTRATION.md`. The harness `Round2PerformanceFollowUpHarness` refuses any database
  not named `ledgercore_r2_perf_*`; `CHECKPOINT` requires `GRANT pg_checkpoint TO ledgercore_r2`. R2-P3 exports the
  baseline with `git archive b584b95 backend` into a scratch directory; it never checks out another commit.

## Round 3 isolated baseline

Round 3 uses the existing disposable cluster `work/pg-round2-audit` on port 55436, never the persistent service on 55432. Check its configuration and status before use:

    work\postgresql-binaries\pgsql\bin\pg_ctl.exe status -D work\pg-round2-audit
    work\postgresql-binaries\pgsql\bin\pg_ctl.exe -D work\pg-round2-audit -l work\pg-round3-baseline.log -w start
    work\postgresql-binaries\pgsql\bin\psql.exe -X -h 127.0.0.1 -p 55436 -U postgres -d postgres -Atc "SELECT datname FROM pg_database ORDER BY datname"

The observed starting test used a newly created database `ledgercore_round3_baseline` owned by `ledgercore_r2`. It passed 75 tests, 0 failures/errors/skips, and applied V1–V7 to an empty schema. That database is preserved as baseline evidence; do not reuse it for destructive experiments. Create a new purpose-specific database for each independent experiment:

    work\postgresql-binaries\pgsql\bin\psql.exe -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55436 -U postgres -d postgres -c "CREATE DATABASE ledgercore_round3_<purpose> OWNER ledgercore_r2"

Set `JAVA_HOME` to `work\toolchain\jdk21\jdk-21.0.13+9`, add its `bin` to process `PATH`, set `MAVEN_USER_HOME` to `work\maven-home`, and `MAVEN_OPTS` to `-Dmaven.repo.local=D:\workshop\nov\ledgercore\work\m2`. For tests, explicitly set:

    $env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55436/ledgercore_round3_<purpose>'
    $env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_r2'
    $env:LEDGERCORE_TEST_DB_PASSWORD = ''
    .\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend\pom.xml clean verify

Record the exact target database and confirm the output's JDBC URL. For recovery or clearing tests, use a separate cluster/database and explicit credentials; never run destructive scenarios against the baseline database. Stop only the verified disposable cluster after no test or application process depends on it:

    work\postgresql-binaries\pgsql\bin\pg_ctl.exe -D work\pg-round2-audit -m fast -w stop

## Round 3 E10 recovery experiment

The completed one-run backup/PITR/cross-system experiment and its measured RPO/RTO are in
[`evidence/round3/e10/LOCAL-VALIDATION.md`](../../evidence/round3/e10/LOCAL-VALIDATION.md). Selected raw command results,
manifests, SQL grades, and the tested Windows restore helper are committed alongside it. The original PostgreSQL data,
WAL archive, simulator state, and complete application/server logs remain under the ignored
`work/e10-lab/runs/20261115-recovery/`. Use the versioned pre-registration and amendment to run a new experiment with
new, dedicated database directories and a new recovery target. Do not reuse the old recovered directories as a fresh
source. The E10 cluster used bank port 55439, simulator port 55440, base-restore port 55441, and PITR port 55442; all
services were loopback-only and separate from the persistent service on 55432.

## Round 3 E11 local full-stack suite

The final run used a dedicated PostgreSQL 18.7 cluster under
`work/e11-lab/runs/20261115-fullstack/postgres-data` on `127.0.0.1:55443`, with only the named databases
`ledgercore_e11_bank` and `ledgercore_e11_sim`. The bank and clearing simulator were separate Java processes on ports
18096 and 18097. The isolated test databases were `ledgercore_e11_verify` and `ledgercore_e11_sim_verify`; their
credentials must be supplied through process environment variables. Never point tests or either app at port 55432.

Build before starting the processes. Use the local JDK 21/Maven wrapper recorded in the baseline section and run from
the uppercase `D:\workshop\nov\ledgercore` path. Start the simulator before the bank application. The simulator
process receives `CLEARING_SIMULATOR_DB_URL`, `CLEARING_SIMULATOR_DB_USERNAME`, `CLEARING_SIMULATOR_DB_PASSWORD`,
`CLEARING_SIMULATOR_PORT=18097`, `CLEARING_SIMULATOR_CALLBACK_DISPATCH_ENABLED=true`,
`LEDGERCORE_CLEARING_HMAC_SECRET`, and `LEDGERCORE_BANK_CALLBACK_URL=http://127.0.0.1:18096/api/clearing/callbacks`.
The bank process receives `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55443/ledgercore_e11_bank`, its dedicated
database username/password, matching `LEDGERCORE_DB_URL` / `LEDGERCORE_DB_USER` / `LEDGERCORE_DB_PASSWORD`,
`LEDGERCORE_CLEARING_ENABLED=true`, `LEDGERCORE_CLEARING_SIMULATOR_URL=http://127.0.0.1:18097`, and the same local
HMAC secret. Read that secret from a local file outside Git; do not paste it into a command transcript or commit it.
Bind both apps to `127.0.0.1` only. Starting the migrated apps applies their forward migrations to these dedicated
databases.

Run the frontend checks and browser suite from the uppercase drive path:

```powershell
Set-Location D:\workshop\nov\ledgercore\frontend
npx openapi-typescript http://127.0.0.1:18096/v3/api-docs -o src/api/schema.d.ts
npm run lint
npm run build
$env:VITE_API_TARGET = 'http://127.0.0.1:18096'
npx playwright test
```

Supply `LEDGERCORE_TEST_DB_URL`, `LEDGERCORE_TEST_DB_USERNAME`, and
`LEDGERCORE_TEST_DB_PASSWORD` for backend `mvn clean verify`, targeting a new disposable `ledgercore_e11_verify`
database. Use a separate fresh `ledgercore_e11_sim_verify` for the simulator module. The E2E identities use random
synthetic `example.test` addresses; no database reset is needed between browser runs.

### Stop, reset, and cleanup

1. Stop the bank and simulator processes with Ctrl+C in the shells that own them; verify ports 18096 and 18097 no
   longer have listeners.
2. Stop only the verified E11 cluster with the PostgreSQL `pg_ctl` belonging to the selected local installation:

   ```powershell
   Set-Location D:\workshop\nov\ledgercore
   $e11Data = 'D:\workshop\nov\ledgercore\work\e11-lab\runs\20261115-fullstack\postgres-data'
   .\work\postgresql-binaries\pgsql\bin\pg_ctl.exe status -D $e11Data
   .\work\postgresql-binaries\pgsql\bin\pg_ctl.exe -D $e11Data -m fast -w stop
   ```

3. E2E data is disposable but also part of the local evidence environment. Preserve it until the committed log,
   screenshots, and SQL grades have been reviewed. For a reset, stop both apps, verify the cluster path and the exact
   database names first, then recreate only the two `ledgercore_e11_*` app databases; do not remove the cluster, the
   E10 run, the Round 2 cluster, or any other database as a cleanup shortcut.
4. For final cleanup, leaving the E11 cluster stopped preserves the local experiment and costs no running resources.
   If local raw data is ever retired, verify that the resolved target is exactly under
   `work/e11-lab/runs/20261115-fullstack/` and that all intended evidence has another retained copy before removing
   only that run directory.
