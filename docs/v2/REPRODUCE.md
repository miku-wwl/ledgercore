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
