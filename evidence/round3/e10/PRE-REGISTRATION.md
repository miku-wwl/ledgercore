# E10 PostgreSQL recovery experiment — pre-registration

## Registration

- Registered: 2026-11-15 NZDT, on `main`, before initializing or writing to either E10 cluster.
- E9 closeout source: `7aad85fb966b0a51079891ef2759ad703ac063df`; exact GitHub Actions run [43606784685](https://github.com/miku-wwl/ledgercore/actions/runs/43606784685) passed full-stack acceptance and OSV. E9 is closed **PARTIAL** because W3C trace propagation and retained spans are not implemented.
- Application source under recovery test: `cb2601f103d765e1c43221d7d5d34e59bb4551cf` (V1–V11 schema and application behavior). This pre-registration commit changes evidence/status only.
- Scope: base backup validation and restore, WAL archiving, PITR to a named restore point, real LedgerCore restart, independent financial grading, and cross-system clearing reconciliation after the bank database is restored to an earlier logical time.
- All funds, identities, accounts, payments, organizations and payroll data are synthetic. No real bank or money is involved.

## Isolation and safety gates

- Create two new disposable PostgreSQL 18.7 clusters under ignored `work/e10-lab/runs/20261115-recovery/`: bank cluster on `127.0.0.1:55439`, clearing-simulator cluster on `127.0.0.1:55440`. They own separate data directories, WAL archives, databases and roles. The simulator cluster is intentionally outside the bank base backup and recovery chain.
- Bind both clusters and application processes to loopback only. Planned app ports are 18096 (bank) and 18097 (clearing simulator); restored bank instances use separate planned ports 55441 and 55442.
- Before each `initdb`, backup, restore, process start/stop, or database creation, record and verify the resolved run directory, port, database identity and owning process. Refuse to run if any port is already bound or a target resolves outside this exact run directory. Never connect to or modify PostgreSQL 55432 or any prior E-stage database.
- Use explicit fresh databases `ledgercore_e10_bank` and `ledgercore_e10_sim`; use a dedicated bank database role `ledgercore_e10`. Credentials remain local and are not committed.
- Preserve all command output, server logs, process IDs, timestamps, checksums, backup manifests, archived WAL, recovery logs, SQL grades, and HTTP responses under the run directory. No cleanup occurs before evidence inspection.

## Frozen recovery cases

### E10-BACKUP: physical backup and base restore

1. Initialize the isolated bank cluster with `wal_level=replica`, `archive_mode=on`, checksums enabled, and a local archive command that copies completed WAL segments into this run's dedicated archive directory. Capture effective settings and `pg_controldata`.
2. Apply all current Flyway migrations by starting the bank application against the fresh synthetic database. Create synthetic customer and payment activity through the real APIs, then stop the app cleanly and checkpoint.
3. Take a plain-format `pg_basebackup` with a generated backup manifest. Capture start/end timestamps and exit status; verify it with `pg_verifybackup` before restore.
4. Restore that base backup into a new bank-owned restore directory and start it on port 55441. Start the actual LedgerCore application against the restored database on port 18096. Confirm readiness and compare the restored state to the pre-backup independent SQL grade.

### E10-PITR: WAL archive and point-in-time recovery

1. On the source bank cluster, commit synthetic transfer/payment records, call `pg_create_restore_point('ledgercore_e10_before_late_payment')`, and persist its exact timestamp and stable record identities.
2. Commit one clearly identified synthetic payment after that named point. Force a WAL switch and wait until the required segments are present in the archive. Verify archive inventory and checksums; stop the source bank cluster to simulate loss.
3. Restore from the validated physical base backup into a separate PITR directory. Configure `restore_command`, `recovery_target_name='ledgercore_e10_before_late_payment'`, and `recovery_target_action='promote'`; start the recovered cluster on port 55442.
4. Verify the named recovery point is reported in server logs/control state. Confirm records committed before the point are present and the post-point payment is absent. Start the actual LedgerCore application against this recovered database and wait for authenticated application readiness.
5. Independently grade financial and database invariants. Record failure, restore-start, PostgreSQL-ready, app-ready, grade-start, and grade-complete timestamps. Define observed RPO as the wall-clock interval from the chosen named recovery point to the simulated failure boundary; define observed RTO as the interval from the recorded failure boundary to the first successful application readiness probe. Also report base-backup creation, restore/replay, and independent-grade durations separately. These are one-run laboratory measurements, not service objectives.

### E10-CROSS-SYSTEM: clearing state beyond the bank recovery point

1. Keep the simulator database/process on its independent port 55440 with no shared backup or restore operation. Through real banking and simulator APIs, prepare one approved synthetic corporate payment and progress its stable operation identity to the external simulator.
2. Capture a bank recovery point while the bank retains a nonterminal clearing obligation, then let the separate simulator progress that same operation to a terminal `SETTLED` state while the bank cannot persist the terminal callback. Record both systems independently before recovery.
3. Restore only the bank database to the registered recovery point and restart LedgerCore. Use the existing authorized, stable-operation inquiry/reconciliation path to discover the simulator's terminal state. Do not create a new operation identity, repost the payment, refund an UNKNOWN outcome, or directly update projections.
4. Independently verify that one obligation resolves to one settlement journal, there is no duplicate financial effect, both journals balance, the customer liability and clearing payable are correct, the projection equals authoritative postings, and bank/simulator operation identities and digests still match.

If the existing simulator/API cannot deterministically hold or replay the terminal callback without bypassing financial rules, record the attempt and leave this acceptance criterion PARTIAL. Do not add unsafe force-settle controls merely to make the experiment pass.

## Independent grade and migration checks

After each restored database starts, capture separate SQL results for:

- `flyway_schema_history` versions and checksums (V1–V11), plus required V7 integrity triggers, composite foreign keys and V10/V11 index state.
- Total debit = total credit for every journal entry; no duplicate or missing transfer receipts; no negative customer ledger balances.
- Transfer, payroll item, durable result and linked transfer identity/lifecycle constraints.
- Corporate payment intent version/digest, maker/checker actors and approval state.
- Outbox event identity/status, clearing obligation and callback/inbox state.
- Projection balance vs authoritative posting aggregation for every account.
- Independent simulator operation status/digest and callback sequence, queried separately from the bank database.
- Application health and a read-only authenticated account/payment lookup after restart.

Record any recovered state that is expected to differ at the PITR target. A database restore cannot rewind or atomically coordinate the independent simulator; a later external terminal outcome must be re-observed and reconciled through the existing stable idempotency identity.

## Verdict rules

- **PASS for base restore** requires verified manifest, successful startup, real application readiness and zero independent integrity mismatches.
- **PASS for PITR** requires restored state at the named point, expected post-target record absence, all pre-target financial records intact, application restart, and zero financial/schema mismatches.
- **PASS for cross-system recovery** requires simulator state to remain independent, bank restart to avoid re-debit/refund, and a single correct settlement resolution with zero reconciliation mismatch.
- E10 overall remains **PARTIAL** if any named experiment, actual app restart, independent financial check, measured RPO/RTO, or cross-system case is missing or fails. Do not substitute planned steps for observed results.
- This registration changes no application or schema code. V1–V7 files remain immutable; all restored databases use the committed forward-only V8–V11 schema state.
