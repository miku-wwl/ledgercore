# 第 4 章　模块化单体里的边界与协作

把应用部署成一个进程，不代表所有代码都应该互相调用。LedgerCore 将 identity、accounts、ledger、transfers、transactionhistory 和 reconciliation 分成显式业务模块。它们共享 JVM 和关系数据库，却通过包边界与服务职责避免形成无序的依赖网。

![模块边界与转账协作](manual/figures/04-modules.svg)

转账模块依赖账户所有权和账本写入能力，但并不把 HTTP 控制器直接塞进账本模块。transactionhistory 提供读模型查询；reconciliation 比较权威账本和展示投影。Spring Modulith 的架构测试检查模块关系，运行时服务仍由 Spring 容器组装。

模块化单体对这个项目有现实好处：本地启动简单，事务边界清晰，代码按业务能力组织。它也有成本：共享数据库意味着表结构和事务仍高度耦合；模块内部的可见性约定需要团队维护；将来若拆服务，还要重新设计网络失败、事件顺序与数据所有权。

“模块化”不是跨包调用被禁止。转账确实需要锁定账本账户并写入凭证。关键在于依赖方向、接口表达的业务操作，以及是否让一个模块绕过另一模块直接改其内部数据。账本写入被集中到 LedgerWriter，账户生命周期规则由 AccountService 管理。

[Spring Modulith 的模块验证文档](https://docs.spring.io/spring-modulith/docs/2.0.x/reference/html/)描述了依赖环与模块内部访问边界检查；LedgerCore 的架构测试是项目自身的证据。通过测试表示当前包图符合检测规则，不表示系统天然适合微服务或模块边界永远正确。

**讨论：** 如果把 ledger 拆成独立微服务，原本同一个 PostgreSQL 事务还能原样覆盖账户扣款、收款、回执吗？如果不能，你会用什么协议表达中间状态？
