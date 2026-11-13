# 第 6 章　JPA 管什么，SQL 又管什么

一个账户对象适合表达生命周期状态、昵称和乐观版本；一条账本分录适合以明确 SQL 写入并由 PostgreSQL 约束。LedgerCore 有意同时使用 Spring Data JPA 与 JdbcTemplate：账户实体映射在 ORM 中，金额的关键写路径集中在 LedgerWriter 和 TransferService 的 SQL 中。

![账户实体从加载到更新的 JPA 生命周期](manual/figures/06-jpa-lifecycle.svg)

JPA 的 EntityManager 管理实体身份与持久化上下文。AccountEntity 的 @Version 支持乐观并发检测，关闭 open-in-view 避免请求序列化时悄悄延长数据库会话。账本采用不可变 Posting 记录和显式查询；对资金路径而言，清楚看见 SQL 与锁顺序比自动脏检查更容易审阅。

{{code:account_entity}}

这不是“ORM 不安全”或“SQL 更快”的普遍结论。ORM 能降低常规 CRUD 的映射负担；显式 SQL 能把行锁、余额查询和写入顺序摆在眼前。两种工具同时使用会增加维护成本：要确保二者参与同一 Spring 管理的数据源事务，避免一边提交而另一边回滚。此项目用完整 PostgreSQL 集成测试覆盖账户和账本写路径。

另一个重要边界是 JPA 版本列不等于账本余额锁。账户状态的乐观版本解决对象更新冲突；余额竞态由锁定 ledger_accounts 行和账本余额查询解决。不要把 @Version 当作覆盖所有并发保护的通用保险。

**思考：** 如果在 AccountEntity 加入 currentBalance 字段并且仍由 LedgerWriter 写 Posting，需要增加什么测试？你必须证明两种写路径共享事务，并且任何投影更新失败都会回滚凭证与账户状态。
