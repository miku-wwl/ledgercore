# E12 official attempt 1 at `6119274` — superseded (harness defect)

The first official E12 sequence ran at `6119274cd0c91fb5a2183267bc2a626ebeaf02a8` on a clean tracked tree (CI run
[43784966787](https://github.com/miku-wwl/ledgercore/actions/runs/43784966787) passed both jobs for this SHA).

Result: 36 cases, 28 PASS, 8 FAIL, 8 hard-gate failures — **not acceptable**.
Every failure is a case whose evidence comes from `assessment/e12/run-e12-lab.ps1`, and every one of its eleven fault
cases ended in `HARNESS_ERROR` within a minute: the runner invoked each lab script as `& $script @($step.inject)`,
which passes the parameter array as one positional value, so PowerShell tried to bind the whole array to `-Action`.
No fault was injected and no case was graded, so none of these FAILs says anything about the application; equally,
nothing here may be counted as a PASS for them.

The other sources at this SHA did execute and are kept as observations (superseded by the attempt-2 runs):

| Source | Run | Result |
|---|---|---|
| verify (backend 118 + simulator 9 tests, SQL grader) | `10ae02b6-61e6-4c30-81d1-425006ed4bd8` | all tests passed; grader PASS |
| fullstack (9 Chromium journeys, SQL grader) | `91dcfc01-e144-42ea-8f35-c6e757bc6fb5` | 9/9 PASS; grader PASS |
| restore (E12-G02, R4-07) | see `E12-RESTORE.json` | PASS; RPO 5.519 s, RTO 25.783 s service / 29.142 s reconciled |
| alert lab (E12-H03) | `c458ef98-372a-4935-9059-e993afb70935` | PASS |
| trace chain (E12-H04) | control payment `c1682703-291d-4b2f-9fd1-26e5d6ea0dca` | complete (7 steps + durable attempt) |

Fix: the runner now passes parameters by hashtable splatting (`& $script @injectParams`), which binds named script
parameters (an array splat passes `-Action` as a value). The whole sequence was then rerun at the fixing commit
(attempt 2, `evidence/round4/e12/`).
