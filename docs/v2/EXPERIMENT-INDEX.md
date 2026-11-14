# Experiment Index

| ID | Stage | Hypothesis / purpose | Evidence location | Status |
|---|---|---|---|---|
| E0-BASELINE | E0 | Verify the existing V1 build, test, API, browser, and dependency baseline on isolated PostgreSQL | evidence/e0/BASELINE.md | Executed locally; baseline main CI verified |
| E3-H01 | E3 | Measure ledger balance and transfer cost across 100–100,000 posting histories | `evidence/e3/measurements/h01-balance-history-20261114T023439Z/`; `evidence/e3/verdicts/H01-ledger-balance-history.md` | Executed; INCONCLUSIVE under preregistered repeat/plan rule |
| E3-H02 | E3 | Measure independent, hot-payer, opposing-direction, and mixed-funds transfer concurrency | `evidence/e3/measurements/h02-transfer-concurrency-20261114T024011Z/`; `evidence/e3/verdicts/H02-financial-concurrency.md` | Executed; SUPPORTED, financial gates passed |
| E3-H03 | E3 | Measure payroll claim/progress, transaction history, authorization plans, and payroll execution | `evidence/e3/measurements/h03-payroll-query-plans-20261114T025611Z/`; `evidence/e3/verdicts/H03-payroll-and-query-plans.md` | Executed; MIXED; progress subprediction falsified by H04 |
| E3-H04 | E3 | Supplemental test of cross-organization payroll progress scan after H03 exposed one-org limitation | `evidence/e3/measurements/h04-cross-org-progress-followup/`; `evidence/e3/verdicts/H04-cross-organization-payroll-progress.md` | Executed; SUPPORTED, no financial rows written |
| E3-PROJECTION | E3 | Compare authoritative-posting aggregation to an incrementally maintained projection only after a stable meaningful baseline | H01 analysis; any candidate requires a new pre-registration | DEFERRED to Round 2 because H01 is INCONCLUSIVE; production write path unchanged |

The full E3 result synthesis and figures are in `evidence/e3/analysis/SUMMARY.md`; generated figures are derived from the preserved samples and PostgreSQL output. None of the local performance values are production capacity claims.
