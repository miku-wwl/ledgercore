# PostgreSQL 数据模型与约束

本文定义 P1/P2 的逻辑模型；实际 SQL 以 Flyway 迁移为准。当前仓库尚无数据库实现。数据库结构不得被误读为已运行的迁移。

## 表与所有权

| 表 | 核心列 | 键与约束 |
|---|---|---|
| customers | id UUID、email_normalized、password_hash、created_at | email_normalized 唯一；邮箱不保存明文密码 |
| accounts | id UUID、owner_customer_id、display_name、status、created_at、closed_at | owner 外键；状态受约束；关闭不用 DELETE |
| ledger_accounts | id UUID、account_id 可空、kind、normal_side、is_system | account_id 对客户子账本唯一；唯一的模拟储备系统账户 |
| journal_entries | id UUID、entry_type、currency、created_at、reference、reverses_entry_id | reverses_entry_id 外键并唯一；不允许自我冲正 |
| postings | id UUID、journal_entry_id、ledger_account_id、side、amount_minor BIGINT、currency | amount_minor > 0；side 为 DEBIT/CREDIT；父凭证与币种外键；按账本/凭证建立索引 |
| account_balance_projection | ledger_account_id、balance_minor BIGINT、updated_at | 账本账户外键与主键；客户存款余额不可为负；不单独被业务接口任意修改 |
| transfers | id UUID、owner_customer_id、source_account_id、target_account_id、amount_minor、journal_entry_id、created_at | 所有权与账本外键；源/目标不同；成功转账对应完整凭证 |
| idempotency_records | owner_customer_id、operation、idempotency_key、request_fingerprint、transfer_id、response_payload、created_at | 三元业务键唯一；成功回执与 Transfer 同事务 |

金额列统一为 BIGINT 的最小货币单位；唯一支持币种 NZD。Postings 仍保留 currency 并用父凭证复合外键保证一致，为数据库约束和清晰查询服务，不表示 MVP 支持多币种。

## 账本完整性

PostgreSQL CHECK 可限制金额为正、side 枚举、币种 NZD、源和目标账户不同、外键存在及唯一幂等键；它不能安全地用普通 CHECK 汇总 Posting。Flyway 将增加可延迟的约束触发器，在事务提交时检查每张新凭证至少两条 Posting、借贷总额相等。JournalEntry 和 Posting 上的 UPDATE/DELETE 由触发器拒绝。应用领域校验先尽早给出业务错误，数据库约束负责守住提交边界。

账户余额投影只用于读取和快速展示；资金扣款仍由锁定的 LedgerAccount 行及其 Posting 净余额决定。投影在账务事务内更新，由 Reconciliation 再从账本独立重算。投影不一致时记录明确差异，不得改写 Posting 来追平。

## 并发读写协议

转账以稳定的 UUID 顺序逐个执行 SELECT ... FOR UPDATE 锁定全部相关 LedgerAccount。持锁后，按 PostgreSQL READ COMMITTED 执行后续账本聚合读取；余额足够且账户可用时，在同一事务中追加分录、投影、Transfer 和回执。所有修改 Posting 的业务入口必须使用相同锁协议。历史查询按账户和稳定时间/ID 游标分页，不以时间戳单列保证唯一顺序。

## 需要数据库验证的迁移结果

- Flyway 全新数据库可建表、索引、触发器。
- 迁移重复检查不会重建或删除账务事实。
- 不平衡凭证在提交点失败。
- 已过账行的 UPDATE/DELETE 失败。
- 相同所有者、操作和幂等键只出现一条成功回执。
- 冲正保留原凭证。
- 真实 PostgreSQL 并发测试验证唯一键、行锁和回滚。

### PostgreSQL 行为参考

P0 选择 READ COMMITTED 与显式账户行锁。PostgreSQL 18 每条 SQL 命令开始时取得新快照；锁语句等待后，后续余额汇总语句能够读取此前已提交的 Posting。DEFERRED constraint trigger 可将跨行汇总检查延后至事务结束。以上是数据库产品文档说明；本地迁移和并发行为仍须在 P1/P3 用 LedgerCore 实际测试验证。

- [PostgreSQL 18 transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
- [PostgreSQL 18 explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html)
- [PostgreSQL 18 CREATE TRIGGER](https://www.postgresql.org/docs/18/sql-createtrigger.html)
