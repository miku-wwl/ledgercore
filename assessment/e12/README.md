# E12 — independent engineering assessment (Round 4)

E12 is an executable, independently graded acceptance suite for LedgerCore V2. It is not a report generator: every
verdict comes from a JUnit or Playwright result written by the test runner itself, from a lab verdict file produced by
an SQL grade of a fault-injection run, or from the read-only SQL grader in `graders/financial-hard-gates.sql`. A CLI
exit code or a UI toast is never accepted as proof of financial correctness.

## Contents

| File | Purpose |
|---|---|
| `cases.json` | 36 cases in categories A–H (ledger, Policy B, payroll, durable events, clearing, reconciliation, backup/migration, full stack and operations), each with its sources, its acceptance-matrix dimension and whether it is a hard gate |
| `graders/financial-hard-gates.sql` | read-only grader: balanced journals, projection agreement, balance-sheet identity, no duplicate booking/settlement/refund, terminal states bound to journals, independent approval, transfer/receipt and payroll linkage, no delivered work item masking an owed effect |
| `run-e12.ps1` | `verify`, `fullstack` and `collect` modes (below) |
| `run-e12-lab.ps1` | runs every E6 fault case (transfer, payroll and clearing crashes, terminated session, genuine reserve shortage, lost callbacks) once at the current commit |
| `run-e12-restore.ps1` | representative logical backup/restore with a simulator that moved on while the bank was restored; records measured RPO/RTO |
| `../e9/alert-lab.ps1` | real Prometheus 3.13.3 scrape and alert evaluation on a genuinely created reserve shortage (E12-H03) |
| `../e9/extract-traces.py` | rebuilds the settlement trace chain from a lab run's retained span records (E12-H04) |

## Isolation

- Runs only on `main`; each summary records the exact SHA and whether the tracked tree was clean.
- `verify` and `fullstack` use a dedicated cluster `work/e12-lab/pg` on `127.0.0.1:55460` and only databases named
  `ledgercore_e12_<run>_*`, created fresh per run and never reused. The lab uses the E6 per-run clusters on
  `127.0.0.1:55437`. Nothing connects to the persistent service on `55432`.
- Test-only fault hooks exist only under the `e6-lab` profile with its disposable-database guards; the reserve liquidity
  command exists only under the `reserve-lab` profile with its own guards. An ordinary start registers neither
  (`LedgerCoreApplicationTests#faultInjectionHookIsAbsentWithoutTheDedicatedE6Profile`).
- All identities and money are synthetic (`example.test`).

## Commands

Prerequisites: the local toolchain from `docs/v2/REPRODUCE.md` (Temurin 21, PostgreSQL 18.7 binaries under
`work/postgresql-binaries`, Node with Playwright Chromium), built JARs (`backend/target`, `clearing-simulator/target`).

```powershell
# 1. Quick, non-destructive: both Maven suites on fresh databases, the E8 matrix, and the SQL grader.
#    Pass your own run ids: the console summary is abbreviated; the full summary is evidence/round4/e12/runs/<id>.json.
$verify = [guid]::NewGuid().ToString(); & .\assessment\e12\run-e12.ps1 -Mode verify -RunId $verify -Label 'E12 verify'
# 2. Real processes + real-API Chromium + SQL grader (frees ports 18196, 18197 and 5173 first).
$fullstack = [guid]::NewGuid().ToString(); & .\assessment\e12\run-e12.ps1 -Mode fullstack -RunId $fullstack -Label 'E12 full stack'
# 3. Destructive, about 45 minutes: every E6 fault case, sequentially.
& .\assessment\e12\run-e12-lab.ps1 -Label 'E12 lab'
# 4. Representative restore (bank restored, simulator not).
& .\assessment\e12\run-e12-restore.ps1
# 4b. Alerts on a real failure state (Prometheus on 127.0.0.1:19090) and the trace chain of one clearing lab run.
& .\assessment\e9\alert-lab.ps1
python assessment/e9/extract-traces.py work/e6-lab/runs/<clearing-run-id> evidence/round4/e5-e9/E9-TRACES.json <payment-id>
# 5. Combine into evidence/round4/e12/E12-RESULTS.json.
& .\assessment\e12\run-e12.ps1 -Mode collect -Include $verify, $fullstack
```

## Verdict rules

- A case is `PASS` only if every listed source passed; `FAIL` if any failed; `PARTIAL` if a source is missing or
  skipped; `NOT_RUN` if nothing ran or the case is declared not run.
- Hard gates (`hardGate: true`) and the SQL grader of every included run are absolute: one failure makes the
  assessment `BLOCKED` regardless of any score. The grader's zero-fields are listed in `run-e12.ps1`.
- A lab case counts only if its checkpoint was observed (`NOT_INJECTED` is never a pass) and its recovery verdict is
  `PASS` from that run's independent SQL grade.

## Cleanup

Stopping is part of each runner (exact-PID checks; only processes the run started). The E12 cluster can be stopped with
`work\postgresql-binaries\pgsql\bin\pg_ctl.exe -D work\e12-lab\pg -m fast -w stop`. Run directories under
`work/e12-lab/runs/` and `work/e6-lab/runs/` are ignored evidence; delete only an exact run directory after its summary
is preserved.
