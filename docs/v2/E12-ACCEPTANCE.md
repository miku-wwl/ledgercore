# E12 — Independent Engineering Acceptance (Round 4)

**Verdict: ACCEPTED_WITH_NONBLOCKING_LIMITATIONS**

All 36 E12 cases passed at the accepted source commit, including all 25 hard-gate cases, with every source of every
case executed on a clean tracked tree; the independent SQL grader reported zero hard-gate violations in every graded
database. The limitations in § 8 are real, documented and nonblocking under the master prompt's rules: none is an
unresolved P0/P1 financial or security defect. This is an engineering acceptance of a synthetic laboratory. It is not a
real-bank production certification, a regulatory compliance claim, or a penetration-test result.

## 1. Accepted source

| Item | Value |
|---|---|
| Accepted source SHA | `2c25ef459f2e49bcc1a8b237a1a652d7c205fbcd` (`main`) |
| Exact-SHA CI | [43786857336](https://github.com/miku-wwl/ledgercore/actions/runs/43786857336) — *Build, security, and full-stack acceptance* success; *Scan Maven dependency vulnerabilities / osv-scan* success |
| Application code last changed | `6119274` (CI [43784966787](https://github.com/miku-wwl/ledgercore/actions/runs/43784966787) success); `2c25ef4` changed only `assessment/e12/run-e12-lab.ps1`, `assessment/e12/README.md` and added attempt-1 evidence |
| Flyway | bank V1–V15 (V1–V11 unchanged, checksums pinned); simulator V1–V5 |
| Tracked tree during every E12 run | clean (`trackedTreeDirty=false` in each run record; `git status --porcelain --untracked-files=no` empty before and after) |
| Round 3 handoff (starting point) | `ba36f90957fc65f83cc83112a4ba35f6cfc1bf55` |

## 2. Environment

Windows 11 workstation; Temurin JDK 21.0.13+9; PostgreSQL 18.7 binaries; Node and Playwright Chromium from
`frontend/node_modules`; Prometheus 3.13.3 (alert lab only). Isolation: E12 verify/fullstack on the dedicated cluster
`work/e12-lab/pg` at `127.0.0.1:55460` with fresh `ledgercore_e12_<run>_*` databases; fault, restore and alert labs on
per-run disposable clusters at `127.0.0.1:55437` (bank 18095, simulator 18094; full stack 18196/18197/5173;
Prometheus 19090). The persistent service on 55432 was never contacted. All identities and money are synthetic.

## 3. Commands (exactly as run)

```powershell
$verify = '238be480-46bf-4b96-905b-7b80797a8263'
.\assessment\e12\run-e12.ps1 -Mode verify -RunId $verify -Label 'E12 verify 2c25ef4'
$fullstack = '2322a13e-45cd-4c73-b181-520a74de8502'
.\assessment\e12\run-e12.ps1 -Mode fullstack -RunId $fullstack -Label 'E12 full stack 2c25ef4'
.\assessment\e12\run-e12-lab.ps1 -Label 'E12 lab 2c25ef4'
.\assessment\e12\run-e12-restore.ps1 -Label 'E12-G02 restore 2c25ef4'
.\assessment\e9\alert-lab.ps1 -Label 'E9 alert lab 2c25ef4'
python -I assessment/e9/extract-traces.py work/e6-lab/runs/130ced54-be50-40e3-a489-6b25bad09e1c evidence/round4/e5-e9/E9-TRACES.json 1ee65052-0f6e-44a6-85f1-23dfb30cd2bf
.\assessment\e12\run-e12.ps1 -Mode collect -Include $verify, $fullstack -Label 'E12 results 2c25ef4'
```

Sequence: 2026-11-16 01:32:34Z → 02:19:35Z (UTC).

## 4. Results

<!-- generated from evidence/round4/e12/E12-RESULTS.json at 2c25ef459f2e49bcc1a8b237a1a652d7c205fbcd -->
| Total | PASS | PARTIAL | FAIL | NOT_RUN | Hard-gate cases not PASS |
|---:|---:|---:|---:|---:|---:|
| 36 | 36 | 0 | 0 | 0 | 0 |

Acceptance-matrix dimensions (each case scores exactly one dimension; reproducibility is scored in § 6):

| Dimension | Cases | PASS | Not PASS |
|---|---:|---:|---|
| async | 6 | 6 | — |
| financial | 9 | 9 | — |
| fullstack | 2 | 2 | — |
| observability | 2 | 2 | — |
| recovery | 9 | 9 | — |
| security | 8 | 8 | — |

### 4.1 Case inventory

Every case below states its objective, its sources and their expected results, the observed outcome per source and the
verdict. Common to all cases: tested SHA `2c25ef4`; setup and target as in § 2 (fresh disposable databases, never
reused); grader query `assessment/e12/graders/financial-hard-gates.sql` (read-only, zero required for every hard-gate
field) for every included verify/fullstack/restore database; cleanup: runners stop only the processes they started and
the lab runner checks that ports 55437/18095/18094 are free after each case (§ 4.2). A case passes only if every listed
source passed (`assessment/e12/run-e12.ps1`, collect mode).

| ID | Dim. | Hard gate | Objective | Sources and expected result | Verdict | Evidence (sources × outcome) |
|---|---|:---:|---|---|---|---|
| E12-A01 | financial | yes | Every committed journal balances; amounts are exact NZD minor units; posted history is append-only | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); SQL grader of every included run | **PASS** | parsesAndFormatsExactMinorUnits = PASS<br>postgresRejectsAnUnbalancedJournalAtCommitAndRollsBackItsRows = PASS<br>reversalIsANewEntryWithEveryPostingSideFlipped = PASS<br>openingFundingPersistsTwoBalancedPostingsAndARebuildableProjection = PASS<br>databaseRejectsMutationAndDeletionOfPostedHistory = PASS<br>accountSuspensionStopsFundingUntilTheAccountIsReactivated = PASS<br>… 13 more, all PASS |
| E12-A02 | financial | yes | Identical idempotency key returns the original receipt; a changed payload under the same key is rejected; a lost response recovers with the original key | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | concurrentSameKeyWithDifferentIntentHasOneWinnerAndOneConflict = PASS<br>concurrentSameKeyAndSameIntentReturnOnePersistedReceipt = PASS<br>originalKeyRecoversACommittedTransferWhenTheFirstResponseIsLost = PASS<br>successfulReplayRechecksSourceOwnershipBeforeReturningTheReceipt = PASS |
| E12-A03 | financial | yes | Concurrent debits of one hot payer never overdraw; opposing transfers use a deterministic lock order without deadlock | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | concurrentDifferentKeysCannotOverdrawOneSourceAccount = PASS<br>concurrentCrossTransfersUseDeterministicAccountLocksWithoutDeadlock = PASS |
| E12-A04 | financial | yes | Any failure inside the transfer transaction rolls back journal, postings, projection, receipt and outbox together; crash before commit leaves nothing | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); `evidence/round4/e12/lab/E12-LAB.json` → `transfer-precommit-crash.recovery` = PASS; `evidence/round4/e12/lab/E12-LAB.json` → `transfer-postcommit-response-crash.recovery` = PASS | **PASS** | secondPostingFailureRollsBackFirstPostingAndIdempotencyReservation = PASS<br>receiptPersistenceFailureRollsBackLedgerAndTransferWrites = PASS<br>journalInsertFailureRollsBackIdempotencyReservationAndTransferState = PASS<br>projectionUpdateFailureRollsBackPostingsAndIdempotencyReservation = PASS<br>transferAndItsOutboxFactRollBackTogether = PASS<br>evidence/round4/e12/lab/E12-LAB.json::transfer-precommit-crash.recover = PASS<br>… 1 more, all PASS |
| E12-B01 | security | yes | Executed role x route x object matrix: 12 principals x 44 routes, concealment 404, missing role 403, denied mutations without side effects | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | everyRoleRouteAndObjectCombinationIsEnforcedWithoutSideEffects = PASS |
| E12-B02 | security | yes | Maker cannot approve their own payment or payroll even when holding both roles; approval binds the immutable digest and version | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | aMemberHoldingBothMakerAndApproverRolesCannotApproveTheirOwnPaymentOrP = PASS<br>concurrentApprovalRetriesCreateOneFinancialBookingAndClearingInstructi = PASS<br>itemChangesRacingApprovalCannotAlterTheApprovedContent = PASS |
| E12-B03 | security | yes | Generic transfers cannot debit a corporate account; payroll identities cannot be claimed by public transfers | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | receiptRecorderCannotBypassApprovedCorporateOperationBoundary = PASS<br>publicTransfersCannotClaimReservedPayrollIdempotencyKeys = PASS |
| E12-B04 | security |  | Role revocation, member suspension and organization suspension remove authority; admin self-grant is refused; actions are audited | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | anApproverWhoseRoleIsRevokedAfterSubmissionCanNoLongerBookThePayment = PASS<br>corporateOwnershipMembershipRolesAndTransfersUsePostgres = PASS<br>concurrentApprovalRetriesCreateOneFinancialBookingAndClearingInstructi = PASS<br>suspendedPaymentApproverRequiresExplicitAuditedReactivation = PASS<br>suspendedOrganizationCannotStartPayrollExecution = PASS<br>suspendedCreatorMembershipFailsItemsClosedWithAnExplicitReason = PASS<br>… 1 more, all PASS |
| E12-B05 | security |  | State changes without or with a forged CSRF token are rejected; sessions rotate on authentication | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | stateChangingRequestsWithoutCsrfAreRejectedForEveryAuthenticatedPrinci = PASS<br>sessionsCsrfOwnershipTransfersReceiptsHistoryAndReconciliationUsePostg = PASS |
| E12-C01 | financial | yes | 500 synthetic employees are paid exactly once with deterministic per-item identity and reconciled balances | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); SQL grader of every included run | **PASS** | processesAndReconcilesFiveHundredSyntheticEmployeesExactlyOnce = PASS<br>grader:ledgercore_e12_238be480_verify = PASS<br>grader:ledgercore_e12_2322a13e_fsbank = PASS |
| E12-C02 | async | yes | Competing executors and batches sharing a payer pay each item once and never overdraw | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | overlappingExecutionsOfOneBatchPayEachItemExactlyOnce = PASS<br>concurrentBatchesSharingOnePayerNeverOverdrawIt = PASS |
| E12-C03 | recovery | yes | Worker crash after a failed item rolled back but before its failure was recorded: restart records it once, pays the rest once | `evidence/round4/e12/lab/E12-LAB.json` → `payroll-mark-failure-crash.recovery` = PASS | **PASS** | evidence/round4/e12/lab/E12-LAB.json::payroll-mark-failure-crash.recov = PASS |
| E12-C04 | async | yes | Transient and terminal item failures are durable; results cannot be forged, deleted or completed early | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | insufficientFundsAreDurableWithoutPostingOrOverdraft = PASS<br>transferAndPayrollResultRollbackTogetherOnDatabaseWriteFailure = PASS<br>payrollResultsCannotBeDeletedInAnyState = PASS<br>batchCompletionFailsClosedWhenAResultRowIsMissing = PASS<br>resultsCannotBeForgedOrAdvancedOutsideProcessing = PASS<br>unclassifiedTransferFailureLeavesTheItemPendingAndStopsExecution = PASS |
| E12-D01 | async | yes | Business fact and outbox event commit together; concurrent publishers and dispatchers deliver each event once | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | concurrentPublishersAndDispatchersDeliverEveryEventOnce = PASS<br>retriesExpiredLeasesIdempotentlyAndAllowsAuditedDeadLetterRecovery = PASS<br>transferAndItsOutboxFactRollBackTogether = PASS<br>carriesPersistedCorrelationIdIntoBackgroundHandlerAndRestoresWorkerCon = PASS |
| E12-D02 | recovery | yes | Interrupted dispatch: an event persisted before a crash is delivered after restart; expired leases are retried idempotently | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); `evidence/round4/e12/lab/E12-LAB.json` → `transfer-outbox-before-dispatch.recovery` = PASS | **PASS** | retriesExpiredLeasesIdempotentlyAndAllowsAuditedDeadLetterRecovery = PASS<br>evidence/round4/e12/lab/E12-LAB.json::transfer-outbox-before-dispatch. = PASS |
| E12-D03 | async |  | Retry exhaustion dead-letters visibly; a governed retry is role-gated, audited and idempotent | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | genuinelyFiniteReserveHoldsTheObligationUntilAuditedReplenishmentAndGo = PASS |
| E12-E01 | financial | yes | Settlement, definitive rejection and accepted/pending outcomes post exactly the journals their persisted outcome justifies | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); SQL grader of every included run | **PASS** | settledCallbackWhoseEffectNeverRanIsDetectedThenCompletedOnceFromItsDu = PASS<br>rejectedCallbackWhoseRefundNeverRanIsDetectedThenRefundedOnceWithoutSe = PASS<br>acceptedOperationMovesThroughPendingBeforeSettlement = PASS<br>grader:ledgercore_e12_238be480_verify = PASS<br>grader:ledgercore_e12_2322a13e_fsbank = PASS |
| E12-E02 | async | yes | UNKNOWN outcome is resolved by inquiry under the original operation identity and digest; no refund or new identity | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | unknownOutcomeCanBeResolvedByInquiryWithoutChangingOperationIdentity = PASS<br>operationIdentityCannotBeReusedWithDifferentFinancialFacts = PASS<br>Operations resolves an UNKNOWN clearing result and records a verified  = PASS |
| E12-E03 | security | yes | Signed callbacks: tampered, missing-header, replayed, reused-id, wrong-digest, colliding, gapped and contradictory callbacks never move money twice | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | signedCallbackLifecycleRejectsTamperingAndRecordsEveryConflictWithoutA = PASS<br>callbackEndpointRejectsBadAndExpiredMacsButAcceptsSignedUnknownOperati = PASS |
| E12-E04 | recovery | yes | Crash after the callback commit, inside the uncommitted settlement, or before the acknowledgement: the original identity settles exactly once after restart without simulator redelivery | `evidence/round4/e12/lab/E12-LAB.json` → `clearing-callback-commit-crash.recovery` = PASS; `evidence/round4/e12/lab/E12-LAB.json` → `clearing-effect-precommit-crash.recovery` = PASS; `evidence/round4/e12/lab/E12-LAB.json` → `clearing-effect-ack-crash.recovery` = PASS | **PASS** | evidence/round4/e12/lab/E12-LAB.json::clearing-callback-commit-crash.r = PASS<br>evidence/round4/e12/lab/E12-LAB.json::clearing-effect-precommit-crash. = PASS<br>evidence/round4/e12/lab/E12-LAB.json::clearing-effect-ack-crash.recove = PASS |
| E12-E05 | recovery | yes | A terminated database session during the settlement leaves durable work that completes it once | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); `evidence/round4/e12/lab/E12-LAB.json` → `clearing-effect-session-kill.recovery` = PASS | **PASS** | terminatedSettlementSessionStillAcknowledgesButLeavesDurableWorkThatCo = PASS<br>evidence/round4/e12/lab/E12-LAB.json::clearing-effect-session-kill.rec = PASS |
| E12-E06 | recovery | yes | Genuinely insufficient reserve: obligation kept, CRITICAL exception, restart, audited replenishment, one governed retry, exactly one settlement | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); `evidence/round4/e12/lab/E12-LAB.json` → `clearing-reserve-shortage.recovery` = PASS | **PASS** | genuinelyFiniteReserveHoldsTheObligationUntilAuditedReplenishmentAndGo = PASS<br>reserveLiquidityTransfersAreBalancedReplayableAndCannotOverdrawEitherA = PASS<br>evidence/round4/e12/lab/E12-LAB.json::clearing-reserve-shortage.recove = PASS |
| E12-E07 | recovery | yes | Callbacks lost beyond the simulator's delivery budget are detected and redelivered in order after an authenticated inquiry | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); `evidence/round4/e12/lab/E12-LAB.json` → `clearing-lost-callback.recovery` = PASS | **PASS** | authenticatedInquiryRearmsDeadCallbacksWithTheirOriginalIdentity = PASS<br>rearmingCannotRewriteCallbackFactsOrKeepTheSpentBudget = PASS<br>evidence/round4/e12/lab/E12-LAB.json::clearing-lost-callback.recovery = PASS |
| E12-E08 | async | yes | Competing workers, a duplicate callback and a direct retry produce exactly one settlement | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | competingWorkersDuplicateCallbackAndDirectRetryPostExactlyOneSettlemen = PASS |
| E12-E09 | recovery | yes | Simulator crash with an ACCEPTED operation: the bank's inquiry fails and is reported, nothing is assumed; after restart the same operation settles once | `evidence/round4/e12/lab/E12-LAB.json` → `clearing-simulator-restart.recovery` = PASS | **PASS** | evidence/round4/e12/lab/E12-LAB.json::clearing-simulator-restart.recov = PASS |
| E12-F01 | financial |  | Projection drift is detected exactly and repaired from immutable postings only | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | reconcilesHealthyOrganizationThenDetectsAndResolvesProjectionAnomaly = PASS |
| E12-F02 | financial | yes | A terminal external outcome without its local journal is detected while the payment is not COMPLETED, also after restart and without a new callback | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | settledCallbackWhoseEffectNeverRanIsDetectedThenCompletedOnceFromItsDu = PASS<br>terminatedSettlementSessionStillAcknowledgesButLeavesDurableWorkThatCo = PASS |
| E12-F03 | financial | yes | Negative fixtures: corrupt payment, journal, payroll and event states are refused by PostgreSQL with valid rows unchanged; recoverable dead work is reported | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | concurrentReconciliationRunsKeepOneOpenExceptionPerAnomalyAndNeverLeak = PASS<br>corruptPayrollHistoryIsRefusedAndDeadWorkIsDetected = PASS<br>corruptPaymentAndLedgerStatesAreRefusedByTheDatabaseWithoutTouchingVal = PASS<br>approvedItemsCannotBeMovedToAnotherBatchOrRewrittenThere = PASS<br>completedPayrollHistoryCannotBeMovedOrErased = PASS |
| E12-F04 | security | yes | CRITICAL exceptions close only after an independent recheck; the actor and the recheck are audited; undispatched instructions are reported | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | settledCallbackWhoseEffectNeverRanIsDetectedThenCompletedOnceFromItsDu = PASS<br>bookedInstructionAcknowledgedWithoutDispatchIsReportedInsteadOfStaying = PASS |
| E12-F05 | security |  | Exceptions and Operations views stay inside the tenant | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | showsScopedMetricsAuditAndCorrelationSearchOnlyToOperationsMembers = PASS<br>Operations endpoints conceal a different organization = PASS |
| E12-G01 | recovery |  | Migrations: fresh install, populated V4/V5/V6/V8/V11 upgrades without rewriting history, V7 and V15 refuse inconsistent history; checksums pinned | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | populatedV5CorporateSchemaUpgradesThroughPayrollMigrations = PASS<br>applicationFlywayRegistersTheV5LegacyBackfillCallback = PASS<br>v7RefusesToMigratePayrollHistoryWithAMissingResult = PASS<br>v15RefusesToMigrateACompletedPaymentThatHasNoSettlementJournal = PASS<br>populatedV8ClearingIntentUpgradesAndEnforcesForwardOnlyOutcomeTransiti = PASS<br>populatedV4PersonalBankingSchemaUpgradesWithoutChangingItsHistory = PASS<br>… 4 more, all PASS |
| E12-G02 | recovery |  | Logical backup (pg_dump -Fc) of the bank while a payment is only ACCEPTED, restore into a new database after the live bank settled it, and recovery of the simulator's later callbacks under the original operation identity with no second booking; measured RPO/RTO of this run | `evidence/round4/e12/lab/E12-RESTORE.json` → `verdict` = PASS | **PASS** | evidence/round4/e12/lab/E12-RESTORE.json::verdict = PASS |
| E12-H01 | fullstack |  | Real-API Chromium journeys: personal banking, corporate maker/checker settlement, payroll with lost response, UNKNOWN inquiry, exception, concealment, role denial and revocation | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); SQL grader of every included run | **PASS** | registers, transfers, recovers a lost response, pages history, and enf = PASS<br>organization admin grants a payroll maker access without exposing corp = PASS<br>maker imports and submits payroll; independent approver executes once  = PASS<br>Operations resolves an UNKNOWN clearing result and records a verified  = PASS<br>maker/checker payment reaches a real settlement and self-approval is r = PASS<br>revoked Operations role cannot inquire during a pending business opera = PASS<br>… 4 more, all PASS |
| E12-H02 | fullstack |  | Accessibility subset on the real application: on nine screens every interactive control and image has an accessible name in the computed accessibility tree, one main landmark, a level-1 heading, a document language and title, and no duplicate element ids | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode) | **PASS** | main screens expose named controls, landmarks, headings, language and  = PASS |
| E12-H03 | observability |  | Alerting: a real Prometheus 3.13.3 scrape fires the owed-effect and reserve-shortfall alerts on a genuinely created shortage (shortfall 30.00 NZD measured) and both resolve after the audited replenishment and one governed retry | `evidence/round4/e5-e9/E9-ALERT-LAB.json` → `verdict` = PASS | **PASS** | evidence/round4/e5-e9/E9-ALERT-LAB.json::verdict = PASS |
| E12-H04 | observability |  | W3C trace continuity: approval -> instruction dispatch -> simulator request -> callback delivery -> bank callback -> settlement, stitched only through recorded parent ids and links; client trace ids are never authority | JUnit or Playwright results (`run-e12.ps1` verify or fullstack mode); `evidence/round4/e5-e9/E9-TRACES.json` → `chainComplete` = True | **PASS** | inboundTraceIsContinuedPersistedWithOutboxWorkAndLinkedFromItsDispatch = PASS<br>acceptsVersionZeroTraceparentAndContinuesTheSameTrace = PASS<br>rejectsMalformedUnknownVersionUppercaseAndAllZeroIdentifiers = PASS<br>newRootsAreRandomSampledAndScopesRestoreThePreviousContext = PASS<br>evidence/round4/e5-e9/E9-TRACES.json::chainComplete = PASS |

### 4.2 Fault-lab cases (`evidence/round4/e12/lab/E12-LAB.json`)

Each case: guarded checkpoint reached by the bank JVM under test, exact-PID kill (or `pg_terminate_backend`, real
reserve sweep, bank outage longer than the 254 s callback budget, simulator kill), independent SQL grade after
injection, restart without the fault profile, recovery action, independent SQL grade after recovery. `NOT_INJECTED`
can never pass.

| Lab case | Run id | Injection observed | After-injection grade | Recovery grade | Cleanup |
|---|---|:---:|---|---|---|
| transfer-precommit-crash | `93d6929b-43dd-45cd-8045-7549aabab42b` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| transfer-postcommit-response-crash | `06b6f26e-cc0b-42b7-a972-32edb8f46b8b` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| transfer-outbox-before-dispatch | `c77482b6-a52b-48ed-b65c-4119c8bb3378` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| payroll-mark-failure-crash | `c18f40fa-d3c0-4960-ab90-1eb9b7b328a2` | None | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-callback-commit-crash | `76552736-6e73-419e-970b-8afa8896c716` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-effect-precommit-crash | `c1710d60-d29f-4f72-b830-ece08fd3f054` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-effect-ack-crash | `e4827aec-d680-4354-b128-4b841f86cc0e` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-effect-session-kill | `7620a29d-4784-4be6-92c0-4d36d0cde327` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-reserve-shortage | `5087c9d6-4e93-4c99-bf8b-440c3f35371c` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-lost-callback | `fddfd4bf-d4d7-4af4-b3cf-346e62720234` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |
| clearing-simulator-restart | `4f787181-c64a-48a1-b480-dd18527803da` | True | PASS | **PASS** | PROCESSES_STOPPED_PORTS_FREE |

### 4.3 Runs, graders and measurements

- **verify** run `238be480-46bf-4b96-905b-7b80797a8263` at `2c25ef4`, trackedTreeDirty=False: tests {'PASS': 127}; grader on `ledgercore_e12_238be480_verify`: 1327 journals, 2654 postings, failed hard gates none.
- **fullstack** run `2322a13e-45cd-4c73-b181-520a74de8502` at `2c25ef4`, trackedTreeDirty=False: tests {'PASS': 9}; grader on `ledgercore_e12_2322a13e_fsbank`: 28 journals, 56 postings, failed hard gates none.
- **restore** run `8eb4e9a0-a511-42ea-9145-db9a8db4b066`: verdict PASS; RPO 5.274 s; service RTO 22.963 s; reconciled RTO 26.201 s; backup 150123 bytes.
- **alert lab** run `130ced54-be50-40e3-a489-6b25bad09e1c`: verdict PASS; failure 2026-11-16T02:17:57.7392414Z, firing 2026-11-16T02:19:04.2120381Z, resolved 2026-11-16T02:19:23.5132079Z; shortfall 3000 minor units; retry path: governed dead-letter retry.
- **trace chain** from run directory `130ced54-be50-40e3-a489-6b25bad09e1c`: 123 spans, 108 traces, chain complete = True (8 steps).


Raw run directories: `work/e12-lab/runs/<run>/` and `work/e6-lab/runs/<run>/` (ignored by Git; summaries committed under
`evidence/round4/e12/` and `evidence/round4/e5-e9/`).

## 5. Hard gates (master prompt § 16)

| Hard gate | Evidence | Result |
|---|---|---|
| Zero unbalanced committed financial journals | grader `unbalancedJournals` = 0 for verify (1,327 journals), full stack (28), restore; E12-A01 | PASS |
| No duplicate effective payment/settlement | grader `duplicatePaymentJournals`, `refundAfterSettlementOrWithoutRejection`, `payrollItemsPaidMoreThanOnce` = 0; E12-E04/E05/E08, A04, C01–C03 | PASS |
| No unauthorized corporate debit or self-approval | E12-B01 (528-cell matrix, 243 denied mutations side-effect free), B02, B03; grader `bookedWithoutIndependentApproval` = 0 | PASS |
| Stable operation identity through retry/UNKNOWN | E12-E02, A02, E07, E09, G02 | PASS |
| Reliable recovery path for accepted terminal clearing | E12-E04, E05, E06, E07, E09 (process crash, session termination, genuine reserve shortage, lost callbacks, simulator restart) | PASS |
| Correct detection of pending financial effects | E12-F02; grader `deliveredWorkMaskingOwedEffect` = 0; E12-H03 alert fire/clear | PASS |
| Valid migrations | E12-G01 (fresh install, populated V4/V5/V6/V8/V11 upgrades, V7/V15 refuse contradicting history, checksums pinned) | PASS |
| No skipped required CI and tests | CI 43786857336 both jobs success; 127 JUnit cases PASS with 0 skipped; 9/9 Playwright | PASS |

## 6. Test counts and reproducibility

| Suite | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|
| Backend `clean verify` (E12 verify run) | 118 | 0 | 0 | 0 |
| Simulator `clean verify` (E12 verify run) | 9 | 0 | 0 | 0 |
| Playwright Chromium journeys against real bank + simulator (E12 full stack) | 9 | 0 | — | 0 |
| Fault-lab cases (injection / recovery) | 11 / 11 | 0 | — | — |

Reproducibility: the same SHA passed the full backend, simulator, contract and browser suites independently on GitHub
Actions (run 43786857336) and locally; every E12 record carries the SHA, `trackedTreeDirty=false` and JAR SHA-256 values;
the commands in § 3 are the complete procedure (`assessment/e12/README.md`).

## 7. History of this assessment (nothing below is counted above)

- **Shakedown** on a dirty tree before the E12 scripts were committed found two harness defects (payroll grade column;
  unquoted Playwright reporter list): `evidence/round4/e12/shakedown/README.md`.
- **Official attempt 1** at `6119274`: 28/36 PASS; all 8 failures were cases sourced from the lab runner, which ended
  every fault case in `HARNESS_ERROR` (array splatting bound the parameter array to `-Action`); no fault was injected.
  Superseded and kept unchanged: `evidence/round4/e12/attempt-1-6119274/README.md`.
- **Official attempt 2** at `2c25ef4` (this acceptance) after fixing the runner only; the whole sequence was rerun.

## 8. Limitations and unexecuted classes (nonblocking)

| Limitation | Risk | Where |
|---|---|---|
| R4-05 re-send path for an instruction acknowledged while clearing was disabled | Detection exists (CRITICAL, recheck-gated); resending needs operator action; owner decision recommended (fail approval closed when clearing is disabled) | `ROUND-4-FINDINGS.md` R4-05 |
| One HMAC key signs both directions; no direction label | Cross-direction replay currently rejected by body validation only | `ROUND-4-FINDINGS.md` § Independent review (P3) |
| Message-derived reconciliation exceptions close by a single audited acknowledgement | No four-eyes rule for CRITICAL acknowledgements | `ROUND-4-AUDIT.md` § 5 |
| E12-G02 is a logical backup/restore of the bank database | Not PITR, not offsite, no storage failure; one run (RPO 5.274 s, RTO 22.963/26.201 s) | `evidence/round4/e12/lab/E12-RESTORE.json`; Round 3 E10 remains the only PITR run (RPO 118.922 s, RTO 670.008 s) |
| E12-H02 is an accessibility subset (accessibility tree, landmarks, headings, language, title, ids) | No axe-core/WCAG audit, colour contrast or real screen-reader review | `frontend/e2e/accessibility.spec.ts` |
| Fault classes not executed | Lock-timeout faults, disk/storage failure, network partition with both sides alive, clock skew beyond the signature window, PostgreSQL server crash, repeated-restore variance, penetration testing | `ROUND-4-HANDOFF.md` |
| Measured performance limits | Hot-account serialization (E3 H02), cross-organization payroll progress scan (H04), history-dependent balance cost (H01 INCONCLUSIVE) | `evidence/e3/verdicts/` |
| Toy clearing simulator | No inference about any real interbank system | Chapter 13 of the V2 textbook |

## 9. Disposition

E12: **ACCEPTED_WITH_NONBLOCKING_LIMITATIONS** at `2c25ef459f2e49bcc1a8b237a1a652d7c205fbcd`. The accepted source is
frozen in `E12-SOURCE-FREEZE.md`; any later change to application code, migrations or materially relevant tests
invalidates this acceptance.
