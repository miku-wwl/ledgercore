# Acceptance Matrix

| Gate | Acceptance evidence | E0 | E1 | E2 | E3 |
|---|---|---:|---:|---:|---:|
| Main-only workflow | Branch, HEAD, remote, clean tracked tree; direct push and CI | PASS; E0 commit a7fe0de and its GitHub Actions run 43531591907 succeeded | PASS; commit eab8175501e77dfc57b1d9ea404a73ed86c8ae26 and run 43536790093 succeeded | PASS; commit 500089581ac38741f475030ca706cd034a1a9c92 and run 43543154101 succeeded on main | NOT_RUN |
| Backend regression | Maven unit + PostgreSQL integration + Modulith | PASS: 42 tests, 0 failures/errors/skips | PASS: 44 tests, 0 failures/errors/skips locally; CI verify passed | PASS locally: 49 tests, 0 failures/errors/skips; CI full-stack acceptance passed | NOT_RUN |
| Frontend | Lint and production TypeScript/Vite build | PASS | PASS locally and in CI | PASS locally and in CI | NOT_RUN |
| OpenAPI | Generate types from running application and compare tracked contract | PASS: no diff | PASS: generated against V5; CI contract diff passed | PASS: V6 payroll operations/models generated; CI contract drift check passed | NOT_RUN |
| Browser | Real Chromium workflow | PASS: 1 E2E test | PASS: 2 Chromium E2E tests locally; CI browser acceptance passed | PASS locally and in CI: 3 Chromium tests, including Maker/Checker and lost-response recovery | NOT_RUN |
| Dependencies | npm audit and Maven OSV scan | npm: 2 low, 4 moderate, 0 high/critical; baseline CI OSV job passed | PASS: CI npm audit and OSV dependency scan succeeded | PASS: npm has 2 low/4 moderate and 0 high/critical; CI OSV scan succeeded | NOT_RUN |
| Source/schema audit | Ownership, authorization, transactions, locks, projections, migrations | PASS; risks recorded in ARCHITECTURE.md and ADR | PASS locally; V5 constraints, actor/source-owner split, and authorization reviewed | PASS; V6 constraints, immutable intent, approval digest, transaction boundary, and PostgreSQL locks exercised locally and in CI | NOT_RUN |
| Real corporate authorization | PostgreSQL-backed corporate ownership, roles, isolation, Maker/Checker, personal compatibility | NOT_RUN | LOCAL PASS: ownership XOR, role access, cross-organization 404, Maker/Checker policy, corporate transfer and V1 personal account path | n/a | NOT_RUN |
| Payroll correctness | 500 synthetic items, actual postings, no duplicates, approval immutability, recoverable partial completion | NOT_RUN | n/a | PASS locally: 500-item reconciliation plus actual kill/restart from 3 committed / 497 pending; full-stack CI run 43543154101 passed; see `evidence/e2/VALIDATION.md` | NOT_RUN |
| Performance evidence | Hypotheses, repeated actual measurements, query plans, financial invariants | NOT_RUN | n/a | NOT_RUN | HYPOTHESES PREPARED; measurement and query-plan collection not started |
| Final handoff | Complete evidence-linked Round 1 handoff; no E4–E12 or V2 PDF | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |

A NOT_RUN or blocked mandatory gate cannot be represented as PASS. Stage status also requires a direct-main commit and verification of its relevant GitHub Actions run.
