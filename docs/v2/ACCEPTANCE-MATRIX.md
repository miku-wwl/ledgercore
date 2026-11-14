# Acceptance Matrix

| Gate | Acceptance evidence | E0 | E1 | E2 | E3 |
|---|---|---:|---:|---:|---:|
| Main-only workflow | Branch, HEAD, remote, clean tracked tree; direct push and CI | PASS; E0 commit a7fe0de and its GitHub Actions run 43531591907 succeeded | LOCAL PASS; direct-main push and CI pending | NOT_RUN | NOT_RUN |
| Backend regression | Maven unit + PostgreSQL integration + Modulith | PASS: 42 tests, 0 failures/errors/skips | LOCAL PASS: 44 tests, 0 failures/errors/skips | NOT_RUN | NOT_RUN |
| Frontend | Lint and production TypeScript/Vite build | PASS | LOCAL PASS | NOT_RUN | NOT_RUN |
| OpenAPI | Generate types from running application and compare tracked contract | PASS: no diff | PASS: types generated from the running V5 application | NOT_RUN | NOT_RUN |
| Browser | Real Chromium workflow | PASS: 1 E2E test | PASS: 2 Chromium E2E tests, including corporate payroll-maker transfer | NOT_RUN | NOT_RUN |
| Dependencies | npm audit and Maven OSV scan | npm: 2 low, 4 moderate, 0 high/critical; baseline CI OSV job passed | CI pending; no dependency manifest changes in E1 | NOT_RUN | NOT_RUN |
| Source/schema audit | Ownership, authorization, transactions, locks, projections, migrations | PASS; risks recorded in ARCHITECTURE.md and ADR | PASS locally; V5 constraints, actor/source-owner split, and authorization reviewed | NOT_RUN | NOT_RUN |
| Real corporate authorization | PostgreSQL-backed corporate ownership, roles, isolation, Maker/Checker, personal compatibility | NOT_RUN | LOCAL PASS: ownership XOR, role access, cross-organization 404, Maker/Checker policy, corporate transfer and V1 personal account path | n/a | NOT_RUN |
| Payroll correctness | 500 synthetic items, actual postings, no duplicates, approval immutability, recoverable partial completion | NOT_RUN | n/a | NOT_RUN | NOT_RUN |
| Performance evidence | Hypotheses, repeated actual measurements, query plans, financial invariants | NOT_RUN | n/a | NOT_RUN | NOT_RUN |
| Final handoff | Complete evidence-linked Round 1 handoff; no E4–E12 or V2 PDF | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |

A NOT_RUN or blocked mandatory gate cannot be represented as PASS. Stage status also requires a direct-main commit and verification of its relevant GitHub Actions run.
