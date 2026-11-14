# LedgerCore V2 Plan B — Execution Plan

Decision: Luna/Opus collaborative engineering. Repository: https://github.com/miku-wwl/ledgercore.

## Four rounds

| Round | Owner | Authorized scope | Gate |
|---|---|---|---|
| 1 | GPT-6 Luna | E0 baseline, E1 corporate identity and authorization, E2 payroll, E3 concurrency and performance | Complete only with source-backed tests, real PostgreSQL evidence, reproducible experiments, handoff, and direct-main CI |
| 2 | Claude Opus 5.5 | Independent architecture, financial correctness, concurrency, testing, and evidence audit of E0–E3; remediation or explicit disposition | Round 2 must complete or be explicitly dispositioned before Round 3 |
| 3 | GPT-6 Luna | E4 durable events, E5 clearing simulator, E6 fault injection, E7 reconciliation, E8 security, E9 observability, E10 recovery, E11 operations console | Only after Round 2 disposition |
| 4 | Claude Opus 5.5 | E12 final assessment, final engineering audit, remediation, and V2 PDF publication | Use accepted implementation and real experiment evidence |

Only Round 1 is authorized now. E4–E12 and final V2 PDF publication are out of scope for this round. The V1 textbook remains unchanged.

## Scope distribution

- E0: audit current source, execute and record baseline, identify migration risks.
- E1: organizations, memberships, corporate account ownership, role authorization, minimal usable UI.
- E2: immutable Maker/Checker payroll workflow, durable per-item execution, recovery, synthetic 500-item validation.
- E3: hypothesis-first PostgreSQL concurrency and performance experiments with actual measurements and query plans.
- E4–E11: Round 3 only, after independent Round 2 review.
- E12 and publication: Round 4 only.

## Operating rules

- Develop, test, commit, and push directly on the existing main branch. No branches, worktrees, PRs, detached HEAD, force pushes, or history rewrites.
- Verify branch, status, HEAD, and remote before each stage. Preserve unrelated user changes.
- One model has repository write ownership at a time. Do not run another coding agent against this worktree concurrently.
- Each stage has explicit acceptance, source-backed test results, evidence, a coherent commit, a direct push, and verified GitHub Actions.
- If protection blocks a direct push, stop and report the owner-action blocker. Do not bypass protection.
- Experiments are hypothesis-first. Preserve raw outputs and negative results. Never invent results or convert synthetic data into production claims.
- All money, organizations, accounts, and employees are fictional. No real bank, payment rail, cloud service, or money movement.
- The final V2 PDF is reserved for Round 4; no final V2 PDF work may begin in Round 1.

## Advancement conditions

Round 1 may hand off only after E0–E3 evidence and the Round 1 handoff are committed and pushed, relevant CI is verified, and all incomplete or blocked items are explicit. Round 2 must challenge the implementation and either remediate or record a reasoned disposition for its findings before E4 begins. Round 4 publication requires accepted implementation and traceable real measurements.
