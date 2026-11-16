# 附录 E　关键源码路径 {#app-sources}

路径相对于仓库根目录；书中的代码摘录都链接到冻结提交 `{{source-sha7}}` 上的同一文件。

| 主题 | 文件 | 关键方法或对象 | 章 |
|---|---|---|---|
| 模块边界 | `backend/src/main/java/nz/ac/ledgercore/*/package-info.java`、`backend/src/test/java/nz/ac/ledgercore/ModuleArchitectureTests.java` | `@ApplicationModule`、`ApplicationModules.verify()` | 2 |
| 分录模型 | `backend/src/main/java/nz/ac/ledgercore/ledger/JournalEntry.java` | 紧凑构造函数、工厂方法 | 5 |
| 唯一写入口 | `backend/src/main/java/nz/ac/ledgercore/ledger/LedgerWriter.java` | `writeEntryAndRefreshProjections`、`lockLedgerAccounts`、`settleClearingPayment`、`transferReserveLiquidity` | 2, 7, 14, 17 |
| 平衡与追加式 | `backend/src/main/resources/db/migration/V2__create_ledger_tables.sql` | `ledgercore_assert_journal_entry_balanced`、`ledgercore_reject_ledger_history_change` | 6 |
| 幂等 | `backend/src/main/java/nz/ac/ledgercore/transfers/TransferService.java`、`V4__enforce_idempotency_receipt_lifecycle.sql` | 键保留、`replayReceipt`、`ct_idempotency_must_succeed` | 8 |
| 授权 | `backend/src/main/java/nz/ac/ledgercore/organizations/OrganizationAuthorizationService.java` | `rolesFor`、`requireRole`、`requireCanApprovePayment` | 9 |
| 付款与摘要 | `backend/src/main/java/nz/ac/ledgercore/payments/CorporatePaymentService.java`、`CorporatePaymentDigest.java`、`V8__…sql` | `approve`、`calculate`、`enforce_corporate_payment_intent` | 9, 10 |
| 工资执行 | `backend/src/main/java/nz/ac/ledgercore/payroll/PayrollItemExecutor.java`、`PayrollExecutionService.java` | `executeNext`、`executeLocked`、`executeBatch` | 11 |
| 持久事件 | `backend/src/main/java/nz/ac/ledgercore/events/DurableEventStore.java`、`OutboxDispatcher.java` | `enqueue`、`claimNext`、`markDeliveryFailure`、`retryDeadLetter` | 12 |
| 清算指令 | `backend/src/main/java/nz/ac/ledgercore/clearing/ClearingInstructionOutboxHandler.java` | `handle` | 14 |
| 回调 | `backend/src/main/java/nz/ac/ledgercore/clearing/ClearingSignature.java`、`ClearingCallbackService.java` | `verify`、`receive`、`legalTransition` | 15 |
| 资金效果（R4-01） | `backend/src/main/java/nz/ac/ledgercore/clearing/ClearingFinancialEffectProcessor.java`、`ClearingFinancialEffects.java`、`ClearingCallbackEffectOutboxHandler.java`、`ClearingOutcomeListener.java` | `apply`、`applyOwedEffect`、`tryApplyNow` | 16 |
| 询价与重投（R4-04/07） | `backend/src/main/java/nz/ac/ledgercore/clearing/ClearingOperationsService.java`；`clearing-simulator/src/main/java/nz/ac/ledgercore/clearing/simulator/SimulatorOperationService.java`；模拟器 `V3`、`V5` | `inquire` | 18 |
| 对账 | `backend/src/main/java/nz/ac/ledgercore/reconciliation/ReconciliationQueryService.java`、`ReconciliationService.java`、`ReconciliationExceptionService.java` | `clearingEffectsPending`、`recheck`、`upsertFinding` | 20 |
| 指标与告警 | `backend/src/main/java/nz/ac/ledgercore/LedgerCoreObservability.java`、`assessment/e9/ledgercore-round4-alerts.yml` | `GAUGE_SQL`、`evaluateClearingEffectAlerts` | 21 |
| 追踪 | `backend/src/main/java/nz/ac/ledgercore/api/TraceContext.java`、`SpanRecorder.java`、`TraceContextFilter.java` | `parse`、`child`、`record` | 21 |
| 故障实验 | `backend/src/main/java/nz/ac/ledgercore/faultlab/E6FaultLabConfiguration.java`、`ReserveLiquidityLabRunner.java`；`assessment/e6/` | `e6FaultInjectionHook`、`checkpoint` | 17, 19 |
| 前端状态 | `frontend/src/pages/PaymentsPage.tsx`、`OperationsPage.tsx`、`frontend/src/api/schema.d.ts` | `statusLabel`、`MetricCard` | 24, 25 |
| 浏览器旅程 | `frontend/e2e/*.spec.ts` | 9 个旅程 | 25 |
| E12 | `assessment/e12/`（`cases.json`、`run-e12*.ps1`、`graders/financial-hard-gates.sql`） | `collect` 判定 | 26 |
| 迁移 | `backend/src/main/resources/db/migration/V1…V15`；`clearing-simulator/src/main/resources/db/migration/V1…V5` | — | 6 |

Table: 主题、文件与关键方法 {#tbl-sources}
