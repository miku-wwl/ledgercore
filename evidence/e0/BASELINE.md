# E0 Baseline Evidence

- Baseline source SHA: 5eb6a626242bce79b5314be40b286819bdd9ac52.
- Branch: main; local main equaled origin/main before E0 documentation. No tracked changes were present at audit start.
- Database: disposable PostgreSQL 18.7 cluster, 127.0.0.1:55434, database/user ledgercore_test, Flyway schema version 4. The persistent service on 55432 was not used.
- Toolchain: Temurin 21.0.13+9, Node v26.5.0, npm 12.0.1, Playwright 1.65.0.

## Observed checks

| Check | Command / method | Result |
|---|---|---|
| Backend + integration + Modulith | backend/mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify | Exit 0; 42 tests; 0 failures, 0 errors, 0 skipped; PostgreSQL 18.7; Flyway validated four migrations |
| Frontend lint | npm run lint | Exit 0 |
| Frontend build | npm run build | Exit 0; TypeScript and Vite production build |
| OpenAPI | Run packaged app on disposable DB; npm run generate:api; git diff --exit-code -- src/api/schema.d.ts | Health UP; no schema diff |
| Browser | npm run test:e2e | Exit 0; 1 Chromium test passed in 19.5 seconds |
| Frontend dependency scan | npm audit --registry=https://registry.npmjs.org --audit-level=high | Exit 0; 2 low, 4 moderate, 0 high, 0 critical |
| Maven dependency scan | Existing baseline CI run 43386644262 | OSV dependency-scan job succeeded |
| Existing main CI | https://github.com/miku-wwl/ledgercore/actions/runs/43386644262 | Success for baseline source SHA; verify and dependency-scan jobs succeeded |

## Source observations

- V1 has customers and personal accounts only; accounts.owner_customer_id is NOT NULL and AccountEntity/Account domain require that owner.
- One CUSTOMER_DEPOSIT ledger is linked to an account. The SIMULATION_RESERVE is the explicit source for opening funding and simulated deposits.
- TransferService is one READ_COMMITTED transaction that reserves the actor-scoped key, checks personal source ownership, locks ledger rows, posts the journal and transfer, and finalizes its successful receipt.
- LedgerWriter locks distinct ledger IDs in UUID order, checks authoritative posting balance, writes journal/postings, and rebuilds projections from postings in the same transaction.
- V1 Flyway history is V1–V4. PostgreSQL constraints and triggers enforce journal balance, immutable ledger history, transfer/journal agreement, and idempotency receipt lifecycle.
- CustomerPrincipal is the server-side authenticated identity. Security requires authentication for business routes and CSRF for state-changing requests.

## Limitations and findings

- Corporate ownership, organization memberships, payroll, and worker recovery are not implemented or tested in E0.
- npm reports six low/moderate advisories through existing frontend tooling dependencies; the configured high-severity CI threshold passes. No forced major dependency update was applied.
- The E2E flow wrote only random synthetic identities to the disposable database.
- The V1 baseline CI and the later E0 documentation-commit CI are distinct runs; both are linked above.
- No E3 load test, performance result, or production-capacity claim exists.

## E0 stage commit acceptance

- E0 documentation commit: a7fe0de280b61b66e87cf14c95aa4cf30d0982b8, pushed directly to origin/main.
- CI run: https://github.com/miku-wwl/ledgercore/actions/runs/43531591907.
- Observed run conclusion: success. Full-stack acceptance and Maven OSV dependency scan both succeeded.
