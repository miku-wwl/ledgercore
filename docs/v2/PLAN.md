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

## Round 3 execution state

Round 3 began from accepted main SHA `0db728171f7a28119ed4c4f19437896878f03fc6`. Stage 0 baseline passed on a new disposable PostgreSQL 18.7 database (75 backend tests, no failures). Policy B, independently approved corporate payments, is recorded in `ADR/0005-corporate-payment-approval-and-clearing.md`. The active scope, dependencies, preservation rules and stage gates are in `ROUND-3-PLAN.md`. Stop after E11; do not start E12 or final PDF publication.

As of 2026-11-15, E4 is VALIDATED; E5–E9 remain PARTIAL with their individual gaps preserved. E10's physical backup, base restore, amended PITR and cross-system settlement replay passed local acceptance (RPO 118.922s, RTO 670.008s); E11's full-stack local suite passed (95 backend tests, 6 simulator tests, and 8/8 Chromium tests). E10 and E11 remain formally PARTIAL until their direct-main commits and exact GitHub Actions runs pass. See `evidence/round3/e10/LOCAL-VALIDATION.md`, `evidence/round3/e11/LOCAL-VALIDATION.md`, and the final acceptance/handoff after CI.
