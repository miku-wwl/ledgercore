# 附录 B　参考 SQL {#app-sql}

本附录收录两类 SQL。第一类摘自冻结提交中的独立评分器 `assessment/e12/graders/financial-hard-gates.sql`，是 E12 判定的依据，所有字段都必须为 0。第二类是日常排查用的只读查询，为本书编写，用于理解数据，**不**参与任何判定。所有查询都是只读的；修复数据的动作必须经过应用或新的迁移，并留下审计。

## 评分器的结构

评分器先用两个公共表表达式计算每个账本账户的“过账之和”与投影，以及每笔企业付款的分录计数（@code-b-1）。

@@excerpt assessment/e12/graders/financial-hard-gates.sql | WITH ledger AS \( | 15 | 评分器的两个公共表表达式：按正常方向计算的账本余额与投影；按付款与动作统计的分录数

## 付款与分录的一致性

@@excerpt assessment/e12/graders/financial-hard-gates.sql | 'duplicatePaymentJournals' | 17 | 同一付款的同一种分录不超过一张；完成必有结算；结算必对应 SETTLED 与 COMPLETED；退款不得发生在结算之后；记账必须有独立批准并扣自正确账户

## 转账、工资与回调

@@excerpt assessment/e12/graders/financial-hard-gates.sql | 'transfersWithoutExactJournal' | 11 | 每笔转账恰有两行金额相符的过账与一个成功回执；工资成功项必须链接转账且不重复支付；完成的批次没有未完成项；回调收件箱没有重复序号

## 欠着的效果不能被“已交付”掩盖

@@excerpt assessment/e12/graders/financial-hard-gates.sql | 'deliveredWorkMaskingOwedEffect' | 7 | 外部已终态、本地欠着效果的付款，必须仍有一个排队、处理中或死信的工作项为它负责

这个字段对应 R4-01 的核心不变量 INV-16：一笔欠着的结算或退款，不能处于“没有任何工作项负责”的状态。它与 `informationalOwedEffects`（欠着的效果总数，仅供参考）一起读：后者在准备金不足的实验中可以大于 0，前者必须始终为 0。

## 排查查询（为本书编写，只读）

**一笔付款的完整画像**：

```sql
SELECT p.id, p.operation_id, p.status, p.external_status, p.amount_minor,
       p.booking_journal_entry_id, p.settlement_journal_entry_id, p.reversal_journal_entry_id,
       (SELECT json_agg(json_build_object('seq', i.sequence_no, 'status', i.external_status, 'at', i.received_at)
                        ORDER BY i.sequence_no)
          FROM clearing_callback_inbox i WHERE i.operation_id = p.operation_id) AS callbacks,
       (SELECT json_agg(json_build_object('type', e.event_type, 'status', e.status, 'attempts', e.attempt_count,
                                          'error', e.last_error) ORDER BY e.created_at)
          FROM outbox_events e WHERE e.aggregate_id = p.operation_id) AS work
FROM corporate_payment_intents p
WHERE p.id = :payment_id;
```

**投影漂移**（与评分器的 `projectionMismatches` 同义，但列出账户）：

```sql
SELECT la.id, la.kind,
       COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0) AS ledger_minor,
       pr.balance_minor AS projection_minor
FROM ledger_accounts la
LEFT JOIN postings p ON p.ledger_account_id = la.id
JOIN account_balance_projection pr ON pr.ledger_account_id = la.id
GROUP BY la.id, la.kind, pr.balance_minor
HAVING pr.balance_minor IS DISTINCT FROM
       COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0);
```

**资产负债恒等式的分项**（R、T、D、P）：

```sql
SELECT la.kind, la.normal_side,
       sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END) AS balance_minor
FROM ledger_accounts la JOIN postings p ON p.ledger_account_id = la.id
GROUP BY la.kind, la.normal_side
ORDER BY la.normal_side, la.kind;
```

**死信与最近错误**：

```sql
SELECT id, event_type, aggregate_id, attempt_count, last_error, created_at
FROM outbox_events WHERE status = 'DEAD' ORDER BY created_at;
```

**锁等待会话**（与 E9 的锁等待信号同源）：

```sql
SELECT pid, wait_event_type, wait_event, state, now() - xact_start AS in_transaction, left(query, 80)
FROM pg_stat_activity
WHERE datname = current_database() AND wait_event_type = 'Lock';
```

在一个事务里反复查询 `pg_stat_activity` 时，记得先执行 `SELECT pg_stat_clear_snapshot();`，否则同一事务内看到的是缓存的快照（第 19 章）。
