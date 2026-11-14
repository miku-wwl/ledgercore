# E6 fault-injection lab (Windows PowerShell)

The E6 lab runs only on `main` and uses synthetic accounts in a per-run PostgreSQL 18 cluster, database and role. It never targets the persistent local PostgreSQL service. The dedicated cluster binds to `127.0.0.1:55437`; the LedgerCore lab process binds to `127.0.0.1:18095`.

The only executable cases currently provided are:

- `precommit-crash` — kill LedgerCore after transfer, idempotency receipt and outbox writes, while the transaction is still open.
- `postcommit-response-crash` — kill LedgerCore after commit and before the HTTP response.
- `outbox-before-dispatch` — keep the dispatcher disabled, receive HTTP 200 after durable commit, then kill LedgerCore while the outbox row is still pending.

Each case has `inject.ps1`, `grade.ps1` and `restore.ps1`. `inject` creates a unique run and prints its GUID. Use that GUID for the other two commands:

```powershell
$run = (& .\assessment\e6\precommit-crash\inject.ps1 | ConvertFrom-Json).runId
& .\assessment\e6\precommit-crash\grade.ps1 -RunId $run
& .\assessment\e6\precommit-crash\restore.ps1 -RunId $run
```

`grade` is read-only with respect to business rows and emits a JSON verdict derived from SQL, not console text. `restore` restarts LedgerCore without the E6 profile, replays the stable idempotency key, checks financial recovery, and stops only the exact run's application and PostgreSQL processes. It leaves the isolated database files and JSON/log evidence under ignored `work/e6-lab/runs/<RunId>/`; it does not recursively delete evidence.

Fault hooks live in the backend but are registered only under `e6-lab`. Activation also requires `ledgercore.e6.enabled=true`, the literal `LEDGERCORE_E6_CONFIRM=DISPOSABLE_ONLY`, a GUID run id, a run directory exactly beneath `work/e6-lab/runs`, and a database named `ledgercore_e6_*` on loopback port 55437. The default and `test` profiles do not register a hook. Checkpoints block for at most ten minutes; the harness must verify the exact Java PID and listener before stopping it.

If a checkpoint is not observed, the result is `NOT_INJECTED`; no PASS may be inferred. If setup or SQL grading fails, the failed run remains on disk. Do not reuse its run id or database for another experiment.
