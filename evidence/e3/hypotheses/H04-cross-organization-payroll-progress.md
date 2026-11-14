# H04 — Cross-organization payroll progress query

## Registration and scope

- Supplemental E3 query-plan experiment, registered after H03's initial plan capture and before adding the follow-up rows.
- Source code baseline: `230e5873ba7f0d1c7df546eb1e81622fa12a8cc0`.
- Baseline evidence: `measurements/h03-payroll-query-plans-20261114T025611Z/query-plans-after-analyze/payroll-batch-progress-5000.txt`.
- The baseline database had 71,500 payroll item results and one organization. Its 5,000-item progress plan scanned all 71,500 result rows. That did not isolate cross-organization impact.
- This is a database plan experiment only; it does not change production code or execute any synthetic payroll item.

## Question and prediction

When 50,000 pending payroll item results belonging to ten other organizations are added, does the progress query for the original 5,000-item batch continue to read rows outside that batch?

Prediction: the current `PayrollService.countsFor` query will choose a sequential scan of `payroll_item_results`; actual scanned rows and shared-buffer work will rise with global result-table cardinality, even though the target batch and its 5,000 items do not change.

## Method

1. Preserve the H03 5,000-item batch as the baseline target and record its actual pending/succeeded/failed counts and plan at 71,500 result rows.
2. In disposable database `ledgercore_e3_perf`, add ten synthetic organizations with one draft batch and 5,000 pending item results each. Each batch uses five thousand existing synthetic personal payee accounts. No transfers, journal entries, or balance changes are created.
3. Run `ANALYZE` on the payroll tables and repeat the same `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` query for the original target batch.
4. Preserve the SQL, result counts, statistics, and both plans. Verify that the target still reports 5,000 pending, zero succeeded, and zero failed items.

## Decision rules

- Support the prediction if the plan scans the whole result table and actual scanned rows rise by approximately 50,000 while the target result counts stay constant.
- Reject it if the plan changes to batch-bounded result lookups and actual result rows read remain approximately 5,000.
- Report execution time and buffers as local observations only. They are not production capacity claims.

## Limitations

The fixture adds synthetic draft payroll rows without executing them. It measures PostgreSQL's plan and row work for the existing progress query on this machine; it does not model remote database latency or a production distribution of payroll statuses.
