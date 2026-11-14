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
