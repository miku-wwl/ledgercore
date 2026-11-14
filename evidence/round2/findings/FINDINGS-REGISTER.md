# Round 2 findings register

Independent audit of E0–E3 by Claude Opus 5.5. Baseline: `b584b9570eee2d2b4c08505b19336b08dfc37a77`.
Severity scale P0–P3 as defined in the Round 2 brief. Dispositions: CONFIRMED_FIXED, CONFIRMED_OPEN, NOT_REPRODUCED,
FALSE_POSITIVE, DESIGN_DECISION, DEFERRED_WITH_RISK. Every reproduction used synthetic data in disposable databases on
the separate Round 2 cluster `127.0.0.1:55436`; the Round 1 cluster (55434) was read only, and the persistent service
(55432) was never contacted.

| ID | Title | Severity | Disposition |
|---|---|---|---|
| R2-01 | Payroll item can leave a non-draft batch | P1 | CONFIRMED_FIXED |
| R2-02 | Payroll results deletable or born terminal; missing results completed the batch | P1 | CONFIRMED_FIXED |
| R2-03 | `PAYROLL_MAKER` debits corporate accounts without approval | P1-level policy risk | DESIGN_DECISION + owner decision OD-1 |
| R2-04 | Stale V5 checksum in Round 1 `ledgercore_test` | none as a compatibility defect | FALSE_POSITIVE (root cause proven) |
| R2-05 | Creator authority loss recorded as `ACCOUNT_NOT_FOUND`; policy implicit | P2 | CONFIRMED_FIXED |
| R2-06 | Public transfer path can claim payroll operation identities | P2 | CONFIRMED_FIXED |
| R2-07 | Approve/reject disclose batch state before authorization | P3 | CONFIRMED_FIXED |
| R2-08 | Committed V5 cannot upgrade a populated V4 database | P1 | CONFIRMED_FIXED |
| R2-09 | Payroll progress scans the global result table (E3 H04) | P2 | CONFIRMED_FIXED |
| R2-10 | H01 inconclusiveness caused by uncontrolled statistics and buffer state | P2 | DEFERRED_WITH_RISK (cause confirmed; cost established) |
| R2-11 | H02/H03 latency and lock evidence over-attributed to PostgreSQL | P2 | CONFIRMED_FIXED (annotation) |
| R2-12 | Within-batch per-item payroll latency grows with completed items and payer history | P2 | DEFERRED_WITH_RISK |
| R2-13 | Test profile defaulted to the persistent PostgreSQL service | P3 | CONFIRMED_FIXED |
| R2-14 | The member who released payroll execution is not recorded | P2 | DEFERRED_WITH_RISK |
| R2-15 | Admin self-provisioning, membership re-activation, customer e-mail probing | P2/P3 | DESIGN_DECISION (part of OD-1) |
| R2-16 | Local Windows E2E depends on drive-letter casing (duplicate React) | P3 | CONFIRMED_FIXED (procedure) |
| R2-17 | Unbounded batch size; unpaginated item listing | P3 | DEFERRED_WITH_RISK |
| R2-18 | Corporate accounts have no history or reconciliation endpoint | P3 | DEFERRED_WITH_RISK |

---

## R2-01 — Payroll item can leave a non-draft batch

- **Severity:** P1 (database-level payroll integrity bypass).
- **Source:** `V6__add_durable_payroll_batches_and_items.sql`, `enforce_payroll_item_intent_mutability()`.
- **Hypothesis:** on UPDATE the trigger locks and checks only `NEW.batch_id`, so moving an item into a DRAFT batch
  bypasses the check on the item's real (original) batch.
- **Reproduction:** `reproductions/R2-01-cross-batch-item-reassignment.sql` on a fresh V1–V6 database
  (`R2-01-baseline-v6-output.txt`); Java tests `PayrollIntegrityPostgresIntegrationTests` (R2-01 group) on the
  unmodified baseline (`regression/red-baseline-integrity-suite.txt`).
- **Expected:** every change to an item of a SUBMITTED/APPROVED/PROCESSING/terminal batch is rejected.
- **Observed (baseline):** in-place amount changes were rejected (control), but `UPDATE payroll_items SET batch_id =
  <draft>` succeeded for APPROVED items, including a simultaneous amount/payee/reference rewrite, and for an item of a
  terminal FAILED batch, which was then deleted together (cascade) with its terminal result. The APPROVED batch kept its
  approved digest with zero items. Concurrently, an item move does not wait for an in-progress approval of its batch.
- **Root cause:** the trigger chooses the batch to check from `NEW` for UPDATE; `batch_id` itself is mutable.
- **Impact:** a database client can strip approved items out of a batch (the application digest check then blocks
  approval/processing, or, during PROCESSING, the batch silently completes with fewer payments than approved), rewrite
  history of completed batches, and erase the payroll record of a paid item. Combined with R2-02, a paid item moved to a
  new batch and re-approved would receive a second payment under a new operation key.
- **Remediation:** V7 makes `payroll_items.id`/`batch_id` immutable, locks and checks the item's own batch for UPDATE
  and DELETE, and adds `fk_payroll_item_results_item_batch` (a second, independent barrier).
- **Regression tests:** `approvedItemsCannotBeMovedToAnotherBatchOrRewrittenThere`,
  `completedPayrollHistoryCannotBeMovedOrErased`, `itemChangesRacingApprovalCannotAlterTheApprovedContent`,
  `draftItemsStayInTheirBatchButCanBeDeletedWithTheirPendingResult`; upgraded-schema check in
  `MigrationCompatibilityPostgresIntegrationTests.populatedV6PayrollSchemaUpgradesToV7WithoutRewritingResults`.
- **Verification:** red on baseline; green after V7 (`regression/green-remediation-suite.txt`);
  `R2-01-after-v7-output.txt` shows every move rejected; GitHub Actions run 43560923022 passed.
- **Disposition:** CONFIRMED_FIXED.

## R2-02 — Payroll results deletable or born terminal; missing results completed the batch

- **Severity:** P1 (incorrect batch completion due to missing results).
- **Source:** V6 `enforce_payroll_item_result_transition()` (BEFORE UPDATE only), `require_payroll_item_result()`
  (fires on item INSERT only), `PayrollService.countsFor()` (INNER JOIN), `finishIfReady()`.
- **Hypothesis:** result rows can be deleted; counts then omit their items; `finishIfReady` treats zero pending as done.
- **Reproduction:** `reproductions/R2-02-payroll-result-deletion.sql` with a real balanced transfer, receipt, and
  SUCCEEDED result (`R2-02-baseline-v6-output.txt`); Java tests (R2-02 group) on the baseline.
- **Expected:** terminal and pending results are durable; a batch with an item lacking a result cannot complete.
- **Observed (baseline):** SUCCEEDED, FAILED, and PENDING result rows were all deleted; the exact `countsFor` SQL then
  returned 0/0/0 and the `finishIfReady` rule produced `COMPLETED` for a batch with two unpaid items, which PostgreSQL
  accepted; the ledger transfer of the paid item was orphaned from payroll records; a replacement result could be
  inserted already FAILED; a PENDING result could be advanced while the batch was only APPROVED; a SUCCEEDED result
  could point at an unrelated transfer. At service level, `execute()` completed such a batch.
- **Root cause:** result lifecycle enforcement covered UPDATE only, and the completion query could not see absent rows.
- **Remediation:** V7 result creation (PENDING, zero attempts, DRAFT batch only), retention (no DELETE while the item
  exists; DRAFT item deletion still cascades), transition (only while PROCESSING; identity immutable; SUCCEEDED must
  reference the transfer that pays the item and its `payroll-{batch}-{item}` receipt under the creator), and terminal
  batch checks (one result per item, none pending, status consistent with counts). `finishIfReady` compares result and
  item counts and fails closed with `PAYROLL_RESULTS_INCOMPLETE` (409). V7's preflight refuses to migrate a database
  whose history already violates these rules.
- **Regression tests:** `payrollResultsCannotBeDeletedInAnyState`, `batchCompletionFailsClosedWhenAResultRowIsMissing`
  (bypasses all user triggers to prove the Java layer also fails closed), `resultsCannotBeForgedOrAdvancedOutsideProcessing`,
  `succeededResultMustReferenceTheTransferThatPaysItsItem`, `v7RefusesToMigratePayrollHistoryWithAMissingResult`.
- **Verification:** red on baseline; green after remediation; `R2-02-after-v7-output.txt`; CI run 43560923022.
- **Disposition:** CONFIRMED_FIXED. Committed history was not repaired; damaged databases follow ADR 0004's
  owner-controlled procedure.

## R2-03 — `PAYROLL_MAKER` debits corporate accounts without approval

- **Severity:** P1-level control risk under any dual-control expectation; not a defect against the established policy.
- **Source:** `OrganizationAuthorizationService.canDebitAccount`, `TransferService.executeTransfer`,
  `TransferController`; policy in `docs/v2/ADR/0002-*.md` decision 5; E1 browser test
  `frontend/e2e/organization-flow.spec.ts`.
- **Investigation:** corporate debit entry points are exactly (1) `POST /api/transfers` → `TransferService.transfer`
  and (2) payroll execution → `TransferService.transferAndRecord` under the batch creator. No other HTTP path debits an
  account. Both apply the same check: personal owner, or active `PAYROLL_MAKER` in an active organization.
  `ORG_ADMIN`-only, `FINANCE_VIEWER`, non-members, suspended members, and suspended organizations are refused
  (existing E1 tests plus Round 2 payroll tests). The E1 acceptance test shows a `PAYROLL_MAKER` moving corporate funds
  to their own personal account with no approval. An `ORG_ADMIN` can grant themselves `PAYROLL_MAKER` and
  `PAYROLL_APPROVER` in one call (`OrganizationService.addMember`), and the E1 integration test does exactly that.
- **Policy determination:** Policy A (Maker/Checker applies to payroll batches; ordinary corporate transfers need
  `PAYROLL_MAKER`) is the established, documented, and tested policy. Round 2 did not find a bypass of Policy A.
  Under Policy A, payroll Maker/Checker protects payroll-batch integrity but is **not** a control against a single
  malicious maker or a sole administrator.
- **Remediation:** none in code (adopting Policy B would remove an accepted E1 feature and materially expand scope).
  Recorded as owner decision **OD-1** in `docs/v2/ROUND-2-AUDIT.md` with options and the required E8 work.
- **Disposition:** DESIGN_DECISION. OD-1 must be decided before E8 acceptance and before any claim that corporate
  payments are dual-controlled.

## R2-04 — Stale V5 checksum in Round 1 `ledgercore_test`

- **Severity:** none as a migration defect; process lesson only.
- **Investigation (read-only on the Round 1 cluster):** `ledgercore_test` recorded V5 checksum `2096504864` at
  13:21:16 (+13:00); `ledgercore_e1_test`, created at 13:28:40, recorded `-1572619169`; the E1 commit `eab8175` was made
  at 13:49:58 and its V5 blob `ac117a3e…` is identical at `5000895` and `b584b95`. An independent implementation of
  Flyway 11.14.1's checksum (verified against the bytecode: CRC32 of each line's UTF-8 bytes, terminators excluded)
  reproduces all six recorded checksums; line endings cannot change them (the V5 working copy is mixed CRLF/LF and still
  yields `-1572619169`). A read-only `pg_dump --schema-only` shows the stale database contains an **earlier draft** of
  V5: organizations and memberships exist, but `transfers.source_owner_customer_id`, `source_organization_id`, their XOR
  check and composite foreign keys, and `uq_accounts_id_owner_organization` are absent, and V3's
  `fk_transfers_owned_source` is still present.
- **Root cause:** a pre-commit V5 draft was applied to a development database and the file was then extended before
  commit. No committed migration was modified after acceptance.
- **Risk if mishandled:** `flyway repair` on that database would stamp the final checksum over a draft schema that lacks
  the source-owner integrity constraints.
- **Remediation:** `MigrationCompatibilityPostgresIntegrationTests.publishedMigrationsKeepTheirRecordedFlywayChecksums`
  pins V1–V6. The stale database was not modified; it must be discarded, never repaired (`docs/v2/REPRODUCE.md`).
- **Disposition:** FALSE_POSITIVE as a compatibility defect, with the root cause positively established. The
  investigation exposed the real compatibility defect R2-08.

## R2-05 — Creator authority loss recorded as `ACCOUNT_NOT_FOUND`; policy implicit

- **Severity:** P2 (misleading durable audit record; implicit financial policy).
- **Source:** `PayrollItemExecutor.executeLocked` / `isPermanentBusinessFailure`; `TransferService` maps an
  unauthorized source to `ACCOUNT_NOT_FOUND` by design (no account-existence disclosure).
- **Observed (baseline):** after the creator's `PAYROLL_MAKER` role was revoked or their membership suspended, every
  item became a terminal FAILED result with `ACCOUNT_NOT_FOUND: The source account was not found.` No money moved.
  A suspended organization prevents execution from starting (404). An approver who loses their role does not
  invalidate the approval; another `OPERATIONS` member can execute.
- **Remediation:** explicit policy (ADR 0004): creator debit authority is evaluated at item execution; loss fails the
  item closed and terminal as `DEBIT_AUTHORITY_REVOKED`; recovery is a new batch with a new approval. Idempotency
  identity is unchanged. The alternative (approval-time authority) is an owner option within OD-1.
- **Regression tests:** `revokedCreatorRoleFailsItemsClosedWithAnExplicitReason`,
  `suspendedCreatorMembershipFailsItemsClosedWithAnExplicitReason`, `suspendedOrganizationCannotStartPayrollExecution`,
  `approvalRemainsValidAfterTheApproverLosesTheirRole`.
- **Disposition:** CONFIRMED_FIXED.

## R2-06 — Public transfer path can claim payroll operation identities

- **Severity:** P2 (operation-identity collision inside one idempotency scope).
- **Source:** payroll key `payroll-{batchId}-{itemId}` under the batch creator, `operation = 'TRANSFER'`; the public
  `POST /api/transfers` accepts any visible-ASCII `Idempotency-Key` in the same scope.
- **Observed (baseline):** the creator could call `TransferService.transfer` with an item's payroll key before
  execution. With the item's exact intent the payroll item later replays and links to that unapproved direct transfer;
  with a different intent the item fails as `IDEMPOTENCY_KEY_REUSED`, which is unclassified, so the item stays PENDING
  and execution of the approved batch is blocked (`unclassifiedTransferFailureLeavesTheItemPendingAndStopsExecution`).
- **Remediation:** `TransferService.transfer` (customer-supplied keys) rejects keys starting with
  `TransferService.PAYROLL_IDEMPOTENCY_KEY_PREFIX` with `INVALID_REQUEST`; the payroll path and its identities are
  unchanged. The frontend generates `crypto.randomUUID()` keys and is unaffected.
- **Regression test:** `publicTransfersCannotClaimReservedPayrollIdempotencyKeys`.
- **Disposition:** CONFIRMED_FIXED. Residual: historical receipts created through the public path with such keys
  (none exist in Round 1 evidence) would no longer be replayable through it.

## R2-07 — Approve/reject disclose batch state before authorization

- **Severity:** P3. **Source:** `PayrollService.approve`/`reject` checked `submitted_by IS NULL` before membership.
- **Observed (baseline):** a non-member approving a DRAFT batch received `409 BATCH_NOT_SUBMITTED` instead of 404.
- **Remediation:** `PAYROLL_APPROVER` membership is checked first. **Test:** `nonMembersCannotProbeBatchStateThroughApprovalOrRejection`.
- **Disposition:** CONFIRMED_FIXED.

## R2-08 — Committed V5 cannot upgrade a populated V4 database

- **Severity:** P1 (V1 → V2 upgrade path blocked for every database that holds a transfer).
- **Source:** V5 line 61 `UPDATE transfers SET source_owner_customer_id = owner_customer_id;` versus V3
  `tr_transfers_append_only` (BEFORE UPDATE OR DELETE on `transfers`).
- **Discovery:** Round 2's new upgrade test seeded one valid personal transfer at V4 and failed in V5 with SQLSTATE
  55000 "Posted ledger history is append-only". Round 1 only ever applied V5 to empty databases.
- **Reproduction:** `reproductions/R2-08-v5-upgrade-of-populated-v4.sql` (`R2-08-output.txt`): V5 fails and rolls
  back; inside one transaction, disabling only that trigger, running the unmodified V5, and re-enabling it commits, the
  backfill is correct, the trigger is enabled (`O`), updates are rejected again, and debits equal credits.
- **Remediation:** `LegacyTransferOwnerBackfillCallback` (Flyway callback bean) does exactly that for version 5 only,
  only when transfers exist, only inside V5's transaction (refuses autocommit), and verifies re-enablement. V5 is
  byte-identical. Fresh installs never invoke it.
- **Regression tests:** `populatedV4PersonalBankingSchemaUpgradesWithoutChangingItsHistory` (ledger fingerprint
  identical before/after, trigger enabled, updates rejected), `applicationFlywayRegistersTheV5LegacyBackfillCallback`.
- **Verification:** failed before the callback (first green attempt), passed after; CI run 43560923022 ran the upgrade
  on Linux PostgreSQL 18.
- **Disposition:** CONFIRMED_FIXED. Owners holding V4 databases with transfers must upgrade with this application
  version or later; earlier V2 builds cannot upgrade them.

## R2-09 — Payroll progress scans the global result table (E3 H04)

- **Severity:** P2. **Source:** `PayrollService.countsFor()` and `viewFor()`.
- **Remediation:** V7 `payroll_item_results.batch_id` + `ix_payroll_item_results_batch_status`; Round 2 SQL reads
  results through `batch_id`.
- **Verification:** preregistered R2-P1 — see `../performance/verdicts/R2-P1-progress-query.md`; correctness and
  cross-organization isolation in `progressCountsStayExactAndIsolatedWhenOtherOrganizationsHavePayrollRows`.
- **Disposition:** CONFIRMED_FIXED.

## R2-10 — H01 inconclusiveness caused by uncontrolled statistics and buffer state

- **Severity:** P2 (experiment interpretation, and the performance property it hid).
- **Investigation:** the preserved H01 plans show repeats 2–3 at 100,000 postings planned with estimates of 1 and 44
  rows (index scan, ~100,750 buffers, 116 ms) versus 1,039 in repeat 1 (bitmap heap scan, 3,251 buffers, 55 ms);
  post-`ANALYZE` plans converge (3,245–3,266 buffers) and their residual spread follows buffer residency
  (`../performance/ROUND-1-ANNOTATIONS.md`).
- **Follow-up:** pre-registered R2-P2 with fresh statistics and a checkpoint after each fixture: repeat spread 1.29×
  (SQL) and 1.17× (service), 100 → 100,000 ratios 47.0× and 6.1×, buffers within 1.1% across repeats. INCONCLUSIVE by
  its own rule, because repeat 1 legitimately chose a sequential scan with the same work
  (`../performance/verdicts/R2-P2-h01-controlled-statistics.md`).
- **Conclusion:** H01's verdict stays INCONCLUSIVE; the history-dependent cost is real (~0.47 µs per posting per
  aggregate, two aggregates per transfer on the source).
- **Disposition:** DEFERRED_WITH_RISK — no ledger write-path change in Round 2; an incremental balance projection is a
  gated Round 3+ candidate (`docs/v2/ROUND-2-HANDOFF.md`).

## R2-11 — H02/H03 latency and lock evidence over-attributed to PostgreSQL

- **Severity:** P2 (misleading interpretation; data itself is sound). See `../performance/ROUND-1-ANNOTATIONS.md`.
- **Disposition:** CONFIRMED_FIXED by a new annotation; the Round 1 files are unchanged.

## R2-12 — Within-batch per-item payroll latency grows with completed items and payer history

- **Severity:** P2 performance risk for the payroll scenario.
- **Discovery:** H03 raw samples, 5,000-item batches at one worker: median per-item latency 7.8 → 21.4 ms from the
  first to the last execution-order decile; 500-item batches flat.
- **Follow-up:** pre-registered R2-P3 on the baseline and Round 2 builds. Baseline reproduced 7.9 → 23.0 ms. About 89%
  of the growth is explained by the claim query walking completed items (result updates are never HOT, so dead index
  entries persist until vacuum) plus the payer's full-history balance aggregates. An early-phase slowdown right after
  bulk loading affected both builds alike and is unexplained. V7's trigger lookups cost 0.038 ms per item
  (exploratory) (`../performance/verdicts/R2-P3-h03-within-batch.md`).
- **Disposition:** DEFERRED_WITH_RISK — remedies (pending-only ordered claim index, `payroll_item_results` autovacuum
  tuning, gated incremental balance) are listed for Round 3; none is implemented in Round 2.

## R2-13 — Test profile defaulted to the persistent PostgreSQL service

- **Severity:** P3 (environment safety). **Source:** `application-test.properties` defaulted
  `spring.datasource.url` to `127.0.0.1:55432/ledgercore_test`, the service `REPRODUCE.md` forbids using.
- **Remediation:** no default; an unset `LEDGERCORE_TEST_DB_URL` now fails fast. CI and documented commands set it.
- **Disposition:** CONFIRMED_FIXED.

## R2-14 — The member who released payroll execution is not recorded

- **Severity:** P2 (audit gap). Transfers record the batch creator as actor (by design); `payroll_batches` records
  `started_at` but not who started or resumed execution; nothing records later resumptions.
- **Disposition:** DEFERRED_WITH_RISK to E4 (durable execution events) / E9 (audit observability). Not fixed in
  Round 2 to avoid changing service signatures used by Round 1 harnesses and to keep E4 scope intact.

## R2-15 — Admin self-provisioning, membership re-activation, customer e-mail probing

- **Severity:** P2/P3. An `ORG_ADMIN` can grant any role to anyone, including themselves; `addMember` silently
  re-activates a SUSPENDED membership; any customer can create an organization and use `addMember` responses
  (404 vs 201) to test whether an e-mail is registered (registration already reveals this in V1).
- **Disposition:** DESIGN_DECISION, folded into OD-1 (entitlement governance) for E8.

## R2-16 — Local Windows E2E depends on drive-letter casing

- **Severity:** P3 (reproducibility only; CI unaffected). Starting Vite from `d:\…` made the Vite 8 dependency
  optimizer inline a second React into `@base-ui` chunks ("Invalid hook call"); from `D:\…` the same `node_modules`
  produced one React and all three Chromium tests passed.
- **Disposition:** CONFIRMED_FIXED in the reproduction procedure (`docs/v2/REPRODUCE.md`).

## R2-17 — Unbounded batch size; unpaginated item listing

- **Severity:** P3. `addItems` caps each request at 1,000 items but not the batch; approval and processing load every
  item to compute the digest; `GET /api/payroll/batches/{id}/items` returns all items.
- **Disposition:** DEFERRED_WITH_RISK (E11 operations console / API hardening).

## R2-18 — Corporate accounts have no history or reconciliation endpoint

- **Severity:** P3. Transaction history and reconciliation endpoints authorize personal owners only.
- **Disposition:** DEFERRED_WITH_RISK (E7 reconciliation / E11).
