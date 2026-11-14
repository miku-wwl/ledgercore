# Experiment Index

| ID | Stage | Hypothesis / purpose | Evidence location | Status |
|---|---|---|---|---|
| E0-BASELINE | E0 | Verify the existing V1 build, test, API, browser, and dependency baseline on isolated PostgreSQL | evidence/e0/BASELINE.md | Executed locally; baseline main CI verified |
| E3-HIST-* | E3 | Measure history growth and query plans at required posting scales | evidence/e3/hypotheses, workloads, measurements, query-plans, analysis, verdicts | NOT_STARTED |
| E3-CONC-* | E3 | Measure independent, hot-account, opposing-order, and payroll concurrency | evidence/e3/* | NOT_STARTED |
| E3-PROJECTION-* | E3 | Compare posting aggregation and a candidate projection update only if measured baseline justifies it | evidence/e3/* | NOT_STARTED |

No E3 hypothesis or performance result has been created or measured yet. E0 timings are build and test observations, not a capacity benchmark. All performance graphs must be generated from preserved measurements.
