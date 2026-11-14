# Round 1 final local validation

## Backend regression

Command: `backend/mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify` using Java 21.0.13 and a fresh disposable database `ledgercore_round1_final_test` on the PostgreSQL 18.7 cluster at port 55434.

Result: **BUILD SUCCESS; 49 tests, 0 failures, 0 errors, 0 skipped.** The database is owned by the local `ledgercore_test` role and is separate from `ledgercore_test`, `ledgercore_e1_test`, and the performance database `ledgercore_e3_perf`.

The first final-verification attempt targeted the previously used `ledgercore_test` database and stopped at Flyway validation. That database's installed V5 checksum (`2096504864`) did not match the current source migration (`-1572619169`). No `repair`, schema edit, or migration edit was performed. A newly created isolated regression database passed the complete suite.

Raw Maven output: `../measurements/full-regression-verification-20261114.log`.

## Frontend and experiment figures

- `npm run lint`: passed.
- `npm run build`: passed (`tsc -b` and Vite production build).
- `python evidence/e3/scripts/plot-e3-results.py`: generated four figures from saved measurement CSVs and the H04 PostgreSQL log. Matplotlib 3.10.6 was used.
- PowerShell parser check for the H04 runner: passed after its metadata/output-path correction.
- Scoped whitespace checks passed for authored Markdown, scripts, JSON, and SQL. Captured `psql` plans and terminal logs retain fixed-width alignment spaces; a whole-tree `git diff --check` reports those spaces, so raw evidence was not normalized to silence the formatter.

## CI

- Commit `230e5873ba7f0d1c7df546eb1e81622fa12a8cc0`: GitHub Actions run [43546776607](https://github.com/miku-wwl/ledgercore/actions/runs/43546776607) succeeded for full-stack acceptance and OSV dependency scan.
- H04 preregistration commit `81a903876ea3ac3214113dbf7bacb86f40307454`: run [43550956746](https://github.com/miku-wwl/ledgercore/actions/runs/43550956746) succeeded.
- Complete E3 evidence/handoff commit `9e7a4421c5ee7f4bdc69cce3cfbb3c1503e8078a`: run [43552848920](https://github.com/miku-wwl/ledgercore/actions/runs/43552848920) passed full-stack acceptance and OSV dependency scan.

The E3 runner is opt-in and its `Harness` class is not executed by ordinary Surefire naming. Each explicit H01–H03 method was run successfully and its outputs are preserved under `measurements/`. The standard `clean verify` confirms the E3 harness compiles with the full test source set.
