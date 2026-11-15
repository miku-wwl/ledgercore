# LedgerCore V2 — Round 4 Plan (Claude Opus 5.5)

**Starting point:** `main` = `origin/main` = `ba36f90957fc65f83cc83112a4ba35f6cfc1bf55` (Round 3 handoff), clean tree.
**Scope:** independent final audit; reproduction and remediation of confirmed defects; the acceptance-critical E5–E9
gaps; E12 independent assessment; source freeze; Chinese V2 PDF textbook. `main` only — no branch, worktree, pull
request, force push or history rewrite. V1–V11 migrations are immutable; fixes use V12+. `miku-wwl/temp-temp` is
read-only supplementary evidence and is never used as runnable source.

## Isolation

| Resource | Use |
|---|---|
| `127.0.0.1:55432` | persistent owner service — never contacted |
| `127.0.0.1:55434/55436/55438` | earlier rounds' clusters — read-only, left running as found |
| `127.0.0.1:55450`, `work/round4-lab/pg-r4` | Round 4 verification cluster (role `ledgercore_r4`, one database per purpose) |
| `127.0.0.1:55437` + `work/e6-lab/runs/<run>` | E6 fault lab, one disposable cluster per run (bank and simulator databases) |
| `127.0.0.1:18095` / `18094` | lab bank / lab clearing simulator processes |
| `127.0.0.1:55460` | E12 assessment cluster |

## Sequence and gates

| Step | Content | Gate |
|---|---|---|
| A | Baseline reproduction, inventory, findings register (`ROUND-4-FINDINGS.md`) | 95 backend + 6 simulator tests on fresh databases; V1–V11 history recorded |
| B | Reproduce R4-01..R4-03 (and any new P0/P1) on unremediated code at an exact SHA with real faults; then fix with regression tests | Pre-fix verdicts preserved; post-fix verdicts PASS on the same harness |
| C | E5–E9 acceptance-critical gaps (clearing failure paths, E6 crash classes, E7 negative fixtures, E8 executed matrix, E9 trace context and real alert) | Honest VALIDATED/PARTIAL per stage |
| D | E12 suite: case specs, run scripts, independent SQL graders, hard gates, exact-SHA CI | Verdict ACCEPTED / ACCEPTED_WITH_NONBLOCKING_LIMITATIONS / BLOCKED |
| E | Source freeze (`E12-SOURCE-FREEZE.md`) | Accepted code SHA with completed CI |
| F | Chinese textbook from the frozen source; page-level visual QA | Manifest with measured counts and PDF SHA-256 |
| G | Final docs, CI, handoff (`ROUND-4-HANDOFF.md`) | `HEAD` = `origin/main`, clean tree |

Any unresolved P0/P1 financial or security defect blocks E12 acceptance and publication.
