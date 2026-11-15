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

## Round 4 two-process clearing cases

`clearing-lab.ps1` extends the same lab to the external-clearing boundary. One run cluster on `127.0.0.1:55437` holds a
bank database (`ledgercore_e6_*`) and a separate simulator database (`ledgercore_e6sim_*`); LedgerCore listens on
`127.0.0.1:18095` and the independent clearing simulator on `127.0.0.1:18094`. Each run generates its own HMAC key and
database passwords and keeps them only in the ignored `run.json`.

| Case | Fault | How it is injected |
|---|---|---|
| `clearing-callback-commit-crash` | `CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT` | kill the bank after the terminal callback commit, before the settlement transaction starts |
| `clearing-effect-precommit-crash` | `CLEARING_EFFECT_POSTED_BEFORE_COMMIT` | kill the bank after the settlement journal is written, before its transaction commits |
| `clearing-effect-ack-crash` | `CLEARING_EFFECT_COMMITTED_BEFORE_ACK` | kill the bank after the callback and settlement commit, before the HTTP acknowledgement |
| `clearing-effect-session-kill` | `DATABASE_SESSION_TERMINATED_DURING_EFFECT` | no process crash: a separate session holds the reserve ledger row lock and the settlement session is terminated with `pg_terminate_backend` while it waits |

```powershell
$run = (& .\assessment\e6\clearing-lab.ps1 -Action inject -Case clearing-callback-commit-crash -Label pre-fix | ConvertFrom-Json).runId
& .\assessment\e6\clearing-lab.ps1 -Action restore -Case clearing-callback-commit-crash -RunId $run -RestoreMode after-dead-letter
```

`-RestoreMode prompt` restarts the bank immediately, while the simulator is still redelivering the callback.
`after-dead-letter` first waits until the simulator has stopped redelivering (`DEAD`), so recovery cannot rely on the
external party. `restore` grades both databases independently, runs an organization reconciliation as an `OPERATIONS`
member, and records whether the payment's absent settlement was detected. A `FAIL` verdict on unremediated code is the
reproduced defect, not a harness error.
