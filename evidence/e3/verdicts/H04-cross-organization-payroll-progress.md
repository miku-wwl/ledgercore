# H04 verdict — cross-organization payroll progress

**Verdict: SUPPORTED.** This supplemental query-plan experiment was registered on `main` at commit `81a9038` after H03's original plan and before the unrelated fixture was inserted. It did not execute payroll items or create transfers/postings.

| Measurement | Before fixture | After fixture |
|---|---:|---:|
| Global payroll item results | 71,500 | 121,500 |
| Results scanned by target progress query | 71,500 | 121,500 |
| Rows returned for the unchanged target batch | 5,000 | 5,000 |
| Total shared buffer hits in saved plan | 962 | 1,361 |
| EXPLAIN execution time | 16.135 ms | 28.207 ms |

The fixture added ten organizations with one DRAFT batch and 5,000 pending items per organization. PostgreSQL continued to use a sequential scan over all `payroll_item_results`; the unrelated rows raised actual scanned rows by 50,000 and shared-buffer hits by 399, while target counts stayed at 5,000 pending, zero succeeded, and zero failed. Execution-time figures are single plan observations, not a capacity claim.

The post-fixture read-only correctness check found 10 organizations, 10 DRAFT batches, 50,000 items/results, zero postings for H04 payer accounts, and zero global financial invariant violations.

## Evidence and provenance

- Preregistration and fixture SQL: `hypotheses/H04-cross-organization-payroll-progress.md`, `scripts/h04-cross-org-progress.sql`.
- Raw plans, counts, environment, and post-fixture invariants: `measurements/h04-cross-org-progress-followup/`.
- Runner: `scripts/run-h04-cross-org-progress.ps1`; post-fixture assertion: `scripts/check-h04-post-fixture-invariants.sql`.
- Figure generated from the preserved raw PostgreSQL output: `analysis/figures/h04-cross-organization-progress.png`.

H04 tests the query shape on this local database and synthetic status distribution. It does not establish remote database, production tenancy, or real payroll performance.
