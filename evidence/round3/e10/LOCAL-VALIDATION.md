# E10 local validation — physical backup, PITR, and clearing recovery

## Verdict

**Local E10 acceptance: PASS; exact-main CI is pending for the evidence commit.** No production code or schema changed for this experiment. The experiment used only synthetic people, synthetic funds, and isolated local PostgreSQL 18.7 clusters on loopback. The bank and simulator databases were separate.

## Frozen procedure and source

- Protocol: [`PRE-REGISTRATION.md`](PRE-REGISTRATION.md), amended before the successful backup by [`PRE-REGISTRATION-AMENDMENT-1.md`](PRE-REGISTRATION-AMENDMENT-1.md).
- Local source revision: `79170ab8fe05320f62c120ff232ff298ecff4c3a` on `main`.
- PostgreSQL: 18.7; source bank `127.0.0.1:55439/ledgercore_e10_bank`; simulator cluster `127.0.0.1:55440` with a separate database; restore ports 55441 (base restore) and 55442 (PITR); apps were loopback-only.
- The first named restore point and personal transfer were created before the backup and were explicitly excluded from PITR acceptance. The amended post-backup restore point below is the accepted target.

## Physical backup and base restore

`pg_basebackup` streamed a plain-format physical backup with a SHA-256 manifest from 2026-11-15 05:09:33.7263991 to 05:09:37.7025404 NZDT: **3.958 seconds**, 1,379 files, 35,161,072 file bytes. Manifest SHA-256: `1d7511e41cbb55ebe4b0be3e50c0db9fdb69818dda453f6531fe34a6f43883a`. Its recorded WAL range is timeline 1, `0/27000028`–`0/28000050`.

The first `pg_verifybackup` invocation used the unsupported `--verbose` option and exited 1. That command-line error is preserved as negative evidence in [`raw/pg-verifybackup-01.txt`](raw/pg-verifybackup-01.txt); it did not alter the backup. The supported `--exit-on-error --progress` verification in [`raw/pg-verifybackup-02.txt`](raw/pg-verifybackup-02.txt) then completed successfully with checksums enabled in **0.797 seconds**. The backup restored into the separate base-restore directory, the real LedgerCore application became ready, and independent read-only SQL found 5 journals, 0 unbalanced journals, 0 projection mismatches, the prior transfer receipt, and the expected pending corporate clearing obligation. The restored schema reported the forward-only V1–V11 migration history.

## PITR and cross-system recovery

After the backup and base-restore grade, the source database created `ledgercore_e10_before_late_payment_after_backup` at **2026-11-15 05:16:31.925826 NZDT**, LSN **`0/2A0000C8`**. A synthetic NZ$3.11 personal transfer (`eb7c5bcd-58b8-48d7-ab23-a78f2a9a53ac`) committed after that point. The source bank database/application were stopped cleanly at the recorded failure boundary, **05:18:30.8479669 NZDT**.

The independent simulator remained up while the bank was unavailable. A signed inquiry changed the pre-existing corporate payment's simulator state to `SETTLED` and persisted its second callback. PITR restored only the validated base backup, replayed to the named target, and promoted the recovered cluster. The post-target NZ$3.11 transfer was absent; the earlier transfer receipt and the original payment obligation were present. The restored bank app passed its first health probe at **05:29:40.8563484 NZDT**.

Using the frozen definitions in the pre-registration:

- **Observed RPO: 118.922 seconds**, from the named recovery point to the simulated failure boundary.
- **Observed RTO: 670.008 seconds (11m10s)**, from the failure boundary to first successful application readiness.

These are one-run lab measurements, not service objectives. After app recovery, simulator callback dispatch delivered sequence 1 `PENDING` and sequence 2 `SETTLED`. The same operation ID, amount, and intent digest were retained. The payment finished `COMPLETED` / external `SETTLED` with one booking and one settlement journal, no reversal, 0 unbalanced journals, 0 projection mismatches, and no surviving clearing payable. Customer-deposit and simulation-reserve ledger totals both reconciled to NZ$2,995.00. The pre-target transfer receipt count remained 1; the post-target transfer row count was 0. Bank outbox events were 10/10 delivered and simulator callbacks were 2/2 delivered.

## Evidence files

Sixteen exact, non-secret command results, manifest, API responses, and SQL grades are committed in [`raw/E10-raw-artifacts.zip`](raw/E10-raw-artifacts.zip). [`raw/ORIGINAL-SHA256SUMS.txt`](raw/ORIGINAL-SHA256SUMS.txt) lists each original file's SHA-256; each archive entry was copied byte-for-byte from the ignored run directory before packaging. It includes both `pg_verifybackup` attempts (the unsupported-option failure and the successful retry), base-restore and PITR grades, the post-target transfer and source grade, recovery readiness, the simulator inquiry while the bank was down, and final bank/simulator SQL grades and queries.

Windows-safe WAL restore helper: [`restore-wal.ps1`](restore-wal.ps1); archive helper and its isolated tests: [`archive-wal.ps1`](archive-wal.ps1), [`ARCHIVE-HELPER-TEST.md`](ARCHIVE-HELPER-TEST.md).

The full local run directory remains at `work/e10-lab/runs/20261115-recovery/` (ignored by Git) with the original PostgreSQL data directories, archived WAL, application/server logs, and complete execution transcript. The selected, non-secret grades and their SQL are copied into this repository. The run was isolated; no persistent/default database was used.

## Limits

This is one Windows/PostgreSQL 18.7 local recovery run. It does not establish production RPO/RTO, multi-host backup durability, repeated recovery variance, or a broader matrix of database corruption and storage failures. The work validates the registered named-target and one cross-system settlement-replay path only.
