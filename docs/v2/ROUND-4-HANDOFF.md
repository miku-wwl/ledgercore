# LedgerCore V2 — Round 4 Handoff (final round)

Owner: Claude Opus 5.5. Round 4 is the last round of the four-round plan; there is no Round 5. All work was done
directly on `main` (no branches, pull requests, worktrees, force pushes or history rewrites). V1–V11 migrations are
unchanged. `miku-wwl/temp-temp` was not modified or used as a runnable source.

## 1. Commits

| Commit | Content | Exact-SHA CI |
|---|---|---|
| `ba36f90` | Round 3 handoff (starting point) | (Round 3) |
| `94a2978` | R4-01 instrumentation: guarded clearing checkpoints, two-process fault lab | [43680382999](https://github.com/miku-wwl/ledgercore/actions/runs/43680382999) success |
| `d529208` | R4-01/02/03/04/05 fixes, V12, V13, simulator V3 | [43683368640](https://github.com/miku-wwl/ledgercore/actions/runs/43683368640) success |
| `d185416` | Reserve lab on an ephemeral port; fault-run outbox poll option | [43684838736](https://github.com/miku-wwl/ledgercore/actions/runs/43684838736) success |
| `292976b` | E5–E9 closure (W3C trace context V14/simulator V4, alerts, E7/E8 matrices), R4-06 (V15), R4-07 (simulator V5) | [43687665348](https://github.com/miku-wwl/ledgercore/actions/runs/43687665348) success |
| `6119274` | Operations console §18 metrics, accessibility subset, E12 harness fixes | [43784966787](https://github.com/miku-wwl/ledgercore/actions/runs/43784966787) success |
| **`2c25ef4`** | **E12 lab-runner fix; attempt-1 evidence kept — accepted and frozen** | [**43786857336**](https://github.com/miku-wwl/ledgercore/actions/runs/43786857336) success |
| `719a758` | E12 acceptance, source freeze, Round 4 audit, status (docs/evidence only) | [43822282146](https://github.com/miku-wwl/ledgercore/actions/runs/43822282146) success |
| `3319069` | V2 textbook sources, PDF (180 pages) and manifest; this handoff; status | [43876414274](https://github.com/miku-wwl/ledgercore/actions/runs/43876414274) success |
| (follow-up) | Records the publication CI and the stopped clusters (documentation only) | its own exact-SHA run |

## 2. Findings and closure

Seven findings (`ROUND-4-FINDINGS.md`): R4-01, R4-02, R4-03, R4-04, R4-07 (P1) and R4-05, R4-06 (P2). All
CONFIRMED_FIXED; R4-05's re-send path is DEFERRED_WITH_RISK (detection exists). Independent review found no further
P0/P1; one P3 (shared HMAC key without a direction label). Architecture, remediations and the INV-01–INV-19 mapping:
`ROUND-4-AUDIT.md`.

| Stage | Final status |
|---|---|
| E5 clearing | VALIDATED |
| E6 fault laboratory | VALIDATED (acceptance-critical matrix); NOT_RUN: lock-timeout faults, bank submit-timeout process kill, other payroll worker points, storage/network-partition faults |
| E7 reconciliation | VALIDATED; NOT_RUN: forged organization-ownership fixture |
| E8 security | VALIDATED (executed 528-cell matrix); not a penetration test |
| E9 observability | VALIDATED (W3C trace context, gauges, real alert fire/clear); no trace backend or notification delivery |
| E12 | **ACCEPTED_WITH_NONBLOCKING_LIMITATIONS** — 36/36 PASS, 25/25 hard gates at `2c25ef4` (`E12-ACCEPTANCE.md`) |

## 3. Test and evidence summary at the frozen source

Backend 118 passed / 0 failed / 0 errors / 0 skipped; simulator 9 / 0 / 0 / 0; Playwright 9 passed; fault lab 11/11
injected and recovered; restore PASS (RPO 5.274 s, RTO 22.963/26.201 s, one local run); alert lab PASS; trace chain
complete; SQL grader zero hard-gate violations in every graded database. Evidence: `evidence/round4/`.

## 4. Residual risks and unexecuted classes

R4-05 re-send path; P3 HMAC direction label; single-person acknowledgement of message-derived CRITICAL exceptions; Round 4
restore evidence is a logical backup (E10 remains the only PITR run); accessibility subset only; unexecuted fault
classes (lock timeout, storage failure, network partition with both sides alive, PostgreSQL server crash, clock skew
beyond the signature window, repeated-restore variance); measured but unoptimized hot-account and cross-organization
scan costs; toy clearing simulator; no penetration test. Owner decision recommended: make payment approval fail closed
while the clearing integration is disabled.

## 5. Publication

**PUBLISHED** — `manual/dist/LedgerCore-V2-Enterprise-Banking-Reliability-Engineering.pdf`, 180 pages, SHA-256
`2d6dee0675fbfdbf50044a3ce8ef2a945d88058d4695ac6187adb275e6a0710b`, built from the frozen source `2c25ef4`. Details and
every check: `manual/v2/PUBLICATION-MANIFEST.md`.

- Content: preface, 26 chapters, appendices A–I (appendix I, the E12 case catalogue, is generated from `cases.json` and
  `E12-RESULTS.json`); 44 figures (36 SVG diagrams, 8 genuine screenshots), 24 tables, 71 code excerpts with
  permalinks, 56 exercises with worked answers.
- Checks: automated QA PASS (302 outline entries and 4,127 internal links, none unresolved; no overflow, blank or gap
  page); every page inspected on the final build; code text searchable (71/71 excerpts); every font embedded with a
  ToUnicode map; an independent second build has identical text and rendered pixels on all 180 pages.
- Page target 180–260: met at its lower bound. The build after the last content edits had 179 pages; keeping short
  tables whole moved chapter 8's exercises onto their own page. No text was added to reach the count (manifest § 4).

## 6. Local resources

Owned services stopped at the end of Round 4 (2026-11-17, after the publication commit's CI passed): the Round 4
cluster `work/round4-lab/pg-r4` (port 55450) and the E12 cluster `work/e12-lab/pg` (port 55460), each with
`pg_ctl stop -m fast` after the PID and port in its `postmaster.pid` matched the listening process. Both ports were
free afterwards, as were 55437, 18094, 18095, 5173 and 19090. Every E6 lab cluster is stopped by its runner. Run directories under `work/`
are retained (ignored by Git). The persistent service on 55432 and the clusters on 55434, 55436 and 55438 were never
touched.
