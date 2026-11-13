# PostgreSQL 数据模型与约束

本文同时记录当前已迁移结构和后续逻辑模型。实际 SQL 以 Flyway 迁移为准。V1–V4（customers/accounts、ledger、transfers/idempotency 与幂等生命周期约束）已在 PostgreSQL 18.7 上运行。

## 表与所有权

| 表 | 核心列 | 键与约束 | 实现状态 |
|---|---|---|---|
| customers | id UUID、email_normalized、password_hash、display_name、created_at、version | email_normalized 唯一；邮箱不保存明文密码 | P1 V1 已迁移；P4 注册/登录与会话身份已实现 |
| accounts | id UUID、owner_customer_id、display_name、status、currency、created_at、closed_at、version | owner 外键；状态与 NZD 受约束；关闭不用 DELETE | P1 V1 已迁移；P2 AccountService 实现开户、暂停/恢复、关闭 |
| ledger_accounts | id UUID、customer_account_id 可空、kind、normal_side、is_system、currency | 客户存款账户唯一；唯一模拟储备账户；NZD 与借/贷正常方向受约束 | P2 V2 已迁移 |
| journal_entries | id UUID、entry_type、currency、created_at、reference、reverses_entry_id | 冲正外键并唯一；不允许自我冲正；NZD | P2 V2 已迁移 |
| postings | id UUID、journal_entry_id、ledger_account_id、side、amount_minor BIGINT、currency | amount_minor > 0；side 为 DEBIT/CREDIT；凭证与账本复合外键校验币种；按账本/凭证索引 | P2 V2 已迁移 |
| account_balance_projection | ledger_account_id、balance_minor BIGINT、updated_at | 账本账户外键与主键；余额不可为负；按权威 Posting 汇总重建 | P2 V2 已迁移 |
| transfers | id UUID、owner_customer_id、source_account_id、target_account_id、amount_minor、journal_entry_id、created_at | 所有者/账户/凭证复合外键；源/目标不同；V3 延迟触发器核对 TRANSFER 凭证及恰好两条匹配 Posting；只追加 | P3 V3 已迁移，PG 测试通过 |
| idempotency_records | owner_customer_id、operation、idempotency_key、request_fingerprint、status、transfer_id、response_payload JSONB、created_at | 客户/操作/键主键；成功回执外键指向同所有者 Transfer；V4 要求只提交成功状态，且成功后不可更新/删除 | P3 V3/V4 已迁移，PG 测试通过 |

金额列统一为 BIGINT 的最小货币单位；唯一支持币种 NZD。Postings 仍保留 currency 并用父凭证复合外键保证一致，为数据库约束和清晰查询服务，不表示 MVP 支持多币种。

## 账本完整性

PostgreSQL CHECK 可限制金额为正、side 枚举和币种 NZD；外键及唯一键约束账本关联与冲正关系。它不能安全地用普通 CHECK 汇总 Posting。V2 已加入可延迟的约束触发器，在事务提交时检查新凭证至少有两条 Posting 且借贷总额相等。JournalEntry 和 Posting 上的 UPDATE/DELETE 由触发器拒绝。应用领域校验先尽早给出业务错误，数据库约束守住提交边界。

账户余额投影只用于读取和快速展示；账本余额通过 Posting 按 LedgerAccount 的正常方向聚合得出。每次账务写入都在持有相关 LedgerAccount 行锁后重算并更新投影，而不是从客户端金额或旧投影递增。P4 的只读 Reconciliation API 独立返回账本余额、投影余额和匹配结果；P5 查询计划实验记录了两条查询的本地 PostgreSQL 执行计划。投影不一致时不得改写 Posting 来追平。

## 并发读写协议

资金操作以稳定的 UUID 顺序逐个执行 SELECT ... FOR UPDATE 锁定相关 LedgerAccount。P2 的初始资金、模拟入金和冲正以及 P3 转账都按此协议运行。转账事务显式采用 READ COMMITTED；锁定付款和收款账本后，在后续 SQL 语句读取最新已提交账本余额，再原子提交 Transfer 与幂等回执。历史查询按账户和稳定时间/ID 游标分页，不以时间戳单列保证唯一顺序。

## 需要数据库验证的迁移结果

- Flyway V1–V4 在隔离 PostgreSQL 18.7 测试库建表、索引和触发器：P3 PASS。
- 不平衡凭证在提交点失败，相关行回滚：P2 PASS。
- 已过账行的 UPDATE/DELETE 失败：P2 PASS。
- Projection 与按 Posting 独立重算的余额一致：P2 PASS。
- 相同所有者、操作和幂等键只出现一条成功回执；V4 拒绝提交未带成功回执的 IN_PROGRESS 行：P3 PASS。
- Transfer 与 source owner、双边 Posting、TRANSFERS 类型凭证一致；不匹配的 Transfer 在提交点失败：P3 PASS。
- 冲正保留原凭证：P2 PASS。
- 同键竞争、错误意图冲突、双事务透支竞争、交叉转账锁顺序及各阶段回滚注入均在 PostgreSQL 18.7 验证：P3 PASS。

### PostgreSQL 行为参考

P0 选择 READ COMMITTED 与显式账户行锁。PostgreSQL 18 每条 SQL 命令开始时取得新快照；锁语句等待后，后续余额汇总语句能够读取此前已提交的 Posting。DEFERRED constraint trigger 可将跨行汇总检查延后至事务结束。V1–V4、账平、只追加、冲正、开账/转账回滚、幂等回执与竞争转账已在 PostgreSQL 18.7 验证。

- [PostgreSQL 18 transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
- [PostgreSQL 18 explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html)
- [PostgreSQL 18 CREATE TRIGGER](https://www.postgresql.org/docs/18/sql-createtrigger.html)
