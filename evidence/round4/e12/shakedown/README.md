# E12 harness shakedown runs (not acceptance evidence)

These runs exercised the E12 scripts that had never executed, on a **dirty tracked tree** (uncommitted changes, recorded
as `trackedTreeDirty: true` where the runner records it) before the E12 source was committed. They are kept because
they found two real harness defects; they are **not** counted in `E12-RESULTS.json`, which uses only runs at the
committed, clean source.

| Run | Script | Result | What it found |
|---|---|---|---|
| `77970756-b87d-45c3-b4aa-ce2b95841a5c` | `assessment/e9/alert-lab.ps1` | PASS (both alerts fired ~70 s after the shortage, shortfall 3000 minor units, both resolved after replenishment + governed retry) | nothing; `E9-ALERT-LAB-shakedown.json` |
| same run | `assessment/e9/extract-traces.py` (control payment `5d7e59e6-6160-4344-864b-88416449640f`) | chain complete (7 steps + durable attempt) | the forward-only chain walk picked the wrong payment when a lab run holds two approvals; the extractor now walks backward from the named payment's settlement span |
| `85d26109-122e-436d-8db3-f98b0a8b4d36` | `assessment/e6/payroll-lab.ps1` | HARNESS_ERROR | **defect 1:** the grade SQL read `payroll_item_results.failure_code`; the column is `error_code` (fixed) |
| `5f015e89-42fe-46c6-ba5f-62b6588555f8` | `assessment/e6/payroll-lab.ps1` (after the fix) | inject PASS / recovery PASS | — |
| `9bc56164-2943-49d1-a8cd-da71bc83b141` | `clearing-recovery-lab.ps1 -Case clearing-simulator-restart` | inject PASS / recovery PASS | — |
| `2c686582-56c3-467d-9309-34f46c24aef9` | `assessment/e12/run-e12-restore.ps1` | PASS (RPO 5.398 s, RTO 24.521 s service / 27.704 s reconciled) | `E12-RESTORE-shakedown.json` |
| `dab17b75-9418-44cd-ab7d-e341f97af05f` | `run-e12.ps1 -Mode fullstack` | HARNESS_ERROR | **defect 2:** PowerShell split the unquoted `--reporter=list,json` at the comma, so Playwright never ran (fixed by quoting; a missing report is now a recorded failure) |
| `027ea7c2-cec3-46c3-bfbf-f1819847e965` | `run-e12.ps1 -Mode fullstack` | 8/9 journeys PASS; the new accessibility subset FAILED on its own assertion | the subset required a `main` landmark while a modal dialog was open; a modal correctly hides the page, so the dialog check now asserts one named dialog with the background hidden |
| `9d53f6e6-34d3-48e8-b6f0-3115bbdf8af2` | `run-e12.ps1 -Mode fullstack` | 9/9 journeys PASS, grader PASS | — |

Raw run directories remain under the ignored `work/e6-lab/runs/<run>/` and `work/e12-lab/runs/<run>/`.
