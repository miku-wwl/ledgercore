# LedgerCore V2 Plan

## Product scenario

Friday Payday: a fictional New Zealand bank supports a corporation preparing, independently approving, and executing payroll for 500 synthetic employees. The authoritative ledger remains in one PostgreSQL transaction boundary inside the Spring Modulith application. All amounts use integer minor units and all funds have explicit simulated accounting sources.

## Round 1 stages

| Stage | Objective | Exit evidence |
|---|---|---|
| E0 | Audit V1 architecture and execute the baseline | Source audit, exact test and scan results, migration risks, plan and reproducible commands |
| E1 | Add organizations, memberships, corporate account ownership, backend authorization, and a minimal UI | Real PostgreSQL migration and integration tests for ownership, membership, Maker/Checker, and V1 compatibility |
| E2 | Implement resumable enterprise payroll | Approved immutable intent, one transaction per item, durable receipts and progress, retry/recovery evidence, reconciled 500-item synthetic run |
| E3 | Measure concurrency and query behavior before changing the write path | Committed hypotheses, repeatable workloads, raw measurements, real EXPLAIN ANALYZE BUFFERS output, analysis and verdict |

Round 2 is an independent Claude Opus 5.5 audit and remediation of E0–E3. Round 3 owns E4–E11 after Round 2 disposition. Round 4 owns E12 and final V2 PDF publication. See EXECUTION-PLAN.md for the binding scope and workflow.
