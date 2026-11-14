# E10 pre-registration amendment 1 — backup before PITR target

## Registration

- Registered: 2026-11-15 NZDT, on `main`, before the base backup and before any PITR restore.
- Source: main commit `5e76d6f52e3947d3586d28b9c2fc17e674ef5a84`; exact CI run `43610645656` passed.
- Original frozen protocol: `evidence/round3/e10/PRE-REGISTRATION.md`. It remains unchanged.
- Scope: correct the live experiment ordering so its recovery target is later than the physical backup from which PITR is performed.

## Reason and disposition of prior observation

The synthetic corporate payment `c28b7080-d808-471c-a2aa-d35cb66eadcc` reached the independent simulator as `PENDING`. A named restore point `ledgercore_e10_before_late_payment` was then created at LSN `0/170000C8` at approximately 04:51:07 NZDT, followed by a synthetic personal transfer `93473c32-1c09-4c4a-8808-7e4e95f736b6` at 04:51:38 NZDT. No backup had yet started. A later base backup therefore cannot prove recovery to that earlier restore point. Preserve both events as pre-backup observations; do not use the first restore point as PITR acceptance evidence.

## Amended frozen sequence

1. Keep the dedicated bank and simulator databases separate. Keep simulator callback dispatch disabled while establishing the cross-system case.
2. Verify the contiguous archived WAL chain through the latest committed bank activity; retain historic archive failures without resetting statistics.
3. Stop the bank application cleanly, checkpoint the bank database, take a new plain-format physical base backup with a SHA-256 manifest, and run `pg_verifybackup`.
4. Restore that backup to the registered base-restore directory/port 55441. Start the actual bank application loopback-only on port 18096, verify readiness, and independently compare the database to the pre-backup grade. Stop the app and restored cluster cleanly.
5. On the original bank source cluster, create a unique post-backup recovery point named `ledgercore_e10_before_late_payment_after_backup`; capture timestamp and LSN. Commit one clearly identified synthetic personal transfer after that point, then checkpoint, switch WAL, and verify the archived chain contains both records.
6. Stop the source bank application and cluster. While the bank cannot receive callbacks, use an HMAC-signed request to the independently running simulator to inquire about the existing corporate clearing operation. The simulator must persist `SETTLED` and its callback outbox while dispatch remains disabled.
7. Restore only the bank base backup into the separate PITR directory/port 55442. Replay the archive to `ledgercore_e10_before_late_payment_after_backup`, promote, and verify the pre-target transfer is present while the post-target transfer is absent.
8. Start the real bank application loopback-only against the PITR database. Independently verify the original payment obligation and balances, then restart the simulator with callback dispatch enabled so its persisted `PENDING` and `SETTLED` callbacks are delivered in sequence. Verify one settlement, no duplicate debit/refund, matching operation identity/digest, and zero ledger/projection mismatch.
9. Preserve all raw logs, hashes, manifests, SQL grades, API responses, timestamps, and process IDs under the existing ignored run directory. Do not delete the original observations.

## Verdict rules

- The first pre-backup restore point is not a PITR pass and is not used as the amended target.
- Base restore still requires manifest verification, real application readiness, and zero independent integrity mismatches.
- Amended PITR passes only when the post-backup named target is reached, the after-target transfer is absent, all prior financial facts are intact, the bank application restarts, and independent integrity checks pass.
- Cross-system recovery passes only when the simulator remains independent, the existing operation identity/digest is unchanged, and its terminal outcome resolves to one correct settlement after bank recovery.
- E10 remains PARTIAL if any required restore, replay, API restart, financial grade, RPO/RTO timing, or cross-system reconciliation is missing or fails.
- This amendment changes no application code, schema, credentials, or original pre-registration content. All funds and identities remain synthetic.
