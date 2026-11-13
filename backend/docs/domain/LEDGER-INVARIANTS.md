# 账本不变量及验证责任

“借方”或“贷方”指 Posting 的会计方向。界面转入/转出必须由交易角色解释，不能替代账务语义。PostgreSQL 普通行级 CHECK 无法汇总多行；V2 使用可延迟约束触发器在提交边界复核凭证借贷相等。V3 校验 Transfer 与凭证及 Posting 匹配；V4 阻止未完成的幂等预留单独提交。应用写入在同一事务内重算并更新投影。

| ID | 业务含义 | 应用层约束 | 数据库约束 | 主要测试 | 失败时的外部结果 |
|---|---|---|---|---|---|
| INV-01 | 每张已提交 JournalEntry 按 NZD 借贷平衡，至少两条 Posting。 | Ledger 领域对象检查行数、单币种及用溢出安全加总比较借贷总额。 | Posting 金额正数；外键与币种一致；提交时的 deferred constraint trigger 检查至少两行和借贷相等。 | 领域单测拒绝不平衡；PostgreSQL 集成测试尝试提交不平衡凭证并断言拒绝。 | 请求失败且不存在已提交的半张凭证。 |
| INV-02 | 一次业务资金移动要么全部发生，要么都不发生。 | Transfer 用例的账户检查、Posting、投影、Transfer、幂等回执在单个事务。 | 外键、唯一键和事务回滚保护全部相关行。 | 在每个写入阶段触发故障并比较所有表的提交前后状态。 | 标准错误响应；无部分资金移动或成功回执。 |
| INV-03 | 正常客户存款账户不得透支。 | 在 READ COMMITTED 事务中先按 LedgerAccount UUID 升序加行锁，再读取账本净余额并验证余额足够；所有写入入口遵守同一协议。 | Posting 外键；余额投影对客户存款不允许负值；可延迟触发器检查客户存款子账本净贷方不为负；数据库事务确保账务写入同进退。 | 真实 PostgreSQL 双事务同步竞争，合计支出高于初始余额；仅允许余额范围内的支出提交。 | 余额不足业务错误；余额和账本保持有效。 |
| INV-04 | 相同意图重放不再次转钱；相同键不可代表不同意图。 | 先按当前已认证客户限定操作；规范化账户 ID、目标 ID、分币金额生成指纹；重放前重新检查账户所有权。 | (customer_id, operation, idempotency_key) 唯一；成功回执与 Transfer 同事务；fingerprint 不可被成功记录覆盖。 | 同键并发同意图只生成一个 Transfer；同键异意图冲突；重放在无权账户上仍不可读回执。 | 同意图返回原成功结果；异意图返回冲突；无权访问返回不泄漏资源存在性的 404。 |
| INV-05 | 已过账会计事实只追加，不被原地改写或删除。 | 账本 API 仅创建新凭证和 Posting，不暴露修改接口。 | PostgreSQL 拒绝对已过账 JournalEntry 和 Posting 执行 UPDATE/DELETE。 | 领域 API 无变更方法；集成测试用 SQL 尝试更新和删除。 | 数据库拒绝写入并保留原凭证。 |
| INV-06 | 更正交易应保留原记录和更正链。 | REVERSAL 用例生成方向完全相反的新 Posting，引用原凭证；MVP 仅整笔冲正且同一凭证最多冲正一次；若违反 INV-03 则拒绝自动冲正。 | 自引用检查；reverses_entry_id 唯一；外键保留原记录。 | 原交易与冲正逐行比较方向和金额；重复冲正或导致客户负余额的冲正被拒绝。 | 原交易不变；第二次冲正或需要追回资金的情形需明确恢复步骤。 |
| INV-07 | 投影可从账本重建；账本是会计事实的权威来源。 | 所有资金写入同事务更新投影；Reconciliation 独立从 Posting 汇总并与投影比较；差异时不得把投影差额当作 ledger 事实。 | 投影外键与非负检查；唯一账户键；Posting 索引支持重算。 | PostgreSQL 查询计算分录和投影；构造可检测差异并核对 ReconciliationResult。 | 状态标为不一致，返回可定位差额；不得修改 Posting。 |
| INV-08 | 客户不能读取或操作他人的资源。 | 每个账户查询、写入和回执重放都基于服务端身份检查 ownerCustomerId；不得信任请求体中的 owner。 | Customer 外键、Account owner 外键；用户数据关联不能绕过服务端过滤。 | API 集成测试跨用户读、转账及重放；检查响应不泄漏资源是否存在。 | 返回统一 404 或认证错误，不返回账户数据。 |
| INV-09 | 并发不会违反金额守恒、余额或历史唯一性。 | 资金账户按稳定升序锁；锁后读取最新账本；冲突和死锁不得重用旧事务状态盲目重试。 | 唯一约束、正金额检查、外键和原子事务。 | 独立连接与同步屏障测试同账户、交叉账户、不同键和同键竞争；验证余额、凭证、Posting、Transfer 与回执数。 | 一个合法结果提交，其余返回冲突/余额不足；账本仍平衡。 |
| INV-10 | 回滚后无 Posting、投影变化、成功 Transfer 或回执残留。 | 将所有本地写入置于同一数据库事务；成功响应仅在提交后生成。 | PostgreSQL 事务原子性及约束。 | 在 Posting、投影、Transfer、回执写入边界分别抛错，然后使用新连接读取全部表。 | 明确失败或结果未知；不会存在部分成功状态。 |

## 共同的事务顺序

转账先确认服务端传入的调用者 ID 有权操作源账户，校验请求并为认证客户、TRANSFER 操作和 Idempotency-Key 保留事务内记录；同键竞争由唯一键串行化，已完成的同意图直接返回原回执。新转账再按 LedgerAccount UUID 排序加锁并核对双方账户状态，在 READ COMMITTED 下读取源账本余额，追加 JournalEntry/Postings、重算投影、保存 Transfer 与成功回执，最后一次提交。失败时未提交的幂等预留也回滚。

任何实现、重试策略或新模块若改变该顺序，都必须重审 INV-02、03、04、08、09、10 的测试。

## P2–P4 实现状态

- INV-01：领域对象拒绝不平衡凭证；PostgreSQL deferred constraint trigger 在提交点拒绝不足两行或借贷不等的凭证。单测和真实 PG 集成测试通过。
- INV-02：开户、模拟入金和转账均将业务记录、Posting、projection 同事务写入。P3 在 JournalEntry、第二条 Posting、projection 和成功回执四处注入失败；每次账户余额、Transfer、幂等记录、JournalEntry 与 Posting 均没有残留。
- INV-03：客户余额采用贷方正常余额；Projection 有非负检查，冲正若使任何正常余额为负则拒绝。P3 转账按稳定 UUID 顺序锁定源/目标账本，在锁后读取权威余额；双事务竞争 NZ$600 + NZ$600 对 NZ$1,000 仅一笔提交，余额不透支。
- INV-04：幂等键按客户、操作与键唯一；规范化的账户 ID、NZD 和分币金额形成 SHA-256 请求指纹。相同意图重放返回 PostgreSQL JSONB 中保存的同一成功回执；同键异意图冲突。唯一键竞争、失败回滚和权限重放检查均通过真实 PG 测试。失败事务不保留幂等记录；V4 deferred trigger 拒绝只提交 IN_PROGRESS 的预留。
- INV-05：PostgreSQL 触发器拒绝 JournalEntry/Posting 的 UPDATE 与 DELETE，P2 集成测试通过。
- INV-06：领域冲正工厂建立新的 REVERSAL 凭证并反转每条 Posting；数据库保留原凭证并以唯一 `reverses_entry_id` 限制重复冲正，PG 集成测试通过。
- INV-07：每次账本写入都按 Posting/normal-side 聚合重算 projection；LedgerBalanceReader 独立读账并比较投影，P2 集成测试通过。
- INV-08：Controller 从服务端认证 principal 取得 owner ID；账户、历史、对账和转账服务在读取数据时校验归属。真实 HTTP 测试覆盖未认证、缺失 CSRF、跨客户 404 和本人数据访问。
- INV-09：15 项 P3 真实 PostgreSQL 集成测试使用两条并发服务调用、独立事务与 CountDownLatch 屏障（不使用 Thread.sleep）；覆盖不同键超额支出、交叉转账、同键同意图及同键异意图。所有竞争均检查余额、转账记录、凭证及回执。
- INV-10：转账在日志凭证写入前、Posting 写入中、Projection 更新和回执持久化时失败，整笔事务均回滚。P4 浏览器通过真实 HTTP 提交转账后丢弃首次响应，刷新恢复原意图并用原请求键重放；资金只移动一次。

P4 完整 41 项 Maven 回归、其中 15 项真实 PostgreSQL 转账集成测试，以及 Chromium 浏览器流程均通过。P5 安全审查、CI 与受控查询计划实验仍需独立验收。

## 主要机制来源

PostgreSQL 18 文档说明 READ COMMITTED 下每条命令获取自己的快照，行级 FOR UPDATE 会等待同一行的并发写入者并锁定其提交后的行版本；LedgerCore 因而在取得账户锁后用另一条 SQL 语句读账本。PostgreSQL 也支持可延迟到事务末尾执行的 constraint trigger。应用层锁顺序仍必须由真实 PostgreSQL 竞争测试验证。

- [PostgreSQL 18 transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
- [PostgreSQL 18 explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html)
- [PostgreSQL 18 CREATE TRIGGER](https://www.postgresql.org/docs/18/sql-createtrigger.html)
