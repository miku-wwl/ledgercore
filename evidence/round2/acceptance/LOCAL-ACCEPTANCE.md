# Round 2 final local acceptance

Source: the Round 2 working tree on top of `1b6ed68` that is committed as the Round 2 evidence and documentation
commit (production code identical to `0a885c8`; changes since then: `application-test.properties` without a default
URL (R2-13) and an environment-metadata format fix in the opt-in performance harness).
Host: Windows 11, Java 21.0.13, Node 26.5.0 / npm 12.0.1, PostgreSQL 18.7 disposable cluster `127.0.0.1:55436`.

| Gate | Command | Result |
|---|---|---|
| Backend: unit, PostgreSQL integration, Modulith, migration compatibility | `mvnw --batch-mode --no-transfer-progress -f backend/pom.xml clean verify` with `LEDGERCORE_TEST_DB_URL` → fresh `ledgercore_r2_final` | exit 0, BUILD SUCCESS, **75 tests, 0 failures, 0 errors, 0 skipped** |
| Test datasource safety (R2-13) | `mvnw -Dtest=LedgerCoreApplicationTests test` with `LEDGERCORE_TEST_DB_URL` unset | fails fast at datasource creation (`'url' must start with "jdbc"`); no connection attempted |
| Application start | `java -jar backend/target/ledgercore-0.0.1-SNAPSHOT.jar --server.port=8080` → fresh `ledgercore_r2_final_e2e` | `/actuator/health` 200 UP; Flyway applied V1–V7 |
| OpenAPI contract | `npm run generate:api`; `git diff --exit-code -- src/api/schema.d.ts` | no diff |
| Browser | `npx playwright test` (Chromium), Vite and Playwright started from `D:\workshop\nov\ledgercore\frontend` | **3 passed** (banking flow, organization flow, payroll Maker/Checker with lost-response recovery) |
| Frontend lint / build | `npm run lint`; `npm run build` | exit 0 / exit 0 |
| Dependency audit | CI step `npm audit --audit-level=high`; CI OSV scan | 6 findings (2 low, 4 moderate), 0 high/critical; OSV job success (run 43560923022) |
| Evidence integrity | `git diff --quiet -- evidence/e0 evidence/e1 evidence/e2 evidence/e3`; V1–V6 checksum pin test | Round 1 evidence unchanged; V1–V6 byte-identical (checksums pinned) |
| V7 acceptance of real Round 1 data | `evidence/round2/migrations/v7-preflight-readonly.sql` in read-only sessions on the Round 1 cluster | 0 violations in `ledgercore_e1_test` (2,517 items), `ledgercore_round1_final_test` (503), `ledgercore_e3_perf` (121,500) |

GitHub Actions results for the pushed Round 2 commits are recorded in `docs/v2/ROUND-2-HANDOFF.md` §6.
