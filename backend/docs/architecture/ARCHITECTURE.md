# LedgerCore 应用架构

## 决策边界

LedgerCore 采用 Java 21、Spring Boot 4.0.9 与 Spring Modulith 2.0.8 的单体应用。所有业务能力运行于一个进程、使用同一 PostgreSQL 数据库；模块是代码与业务边界，不是部署单元。Maven Wrapper 固定为 3.10.0，springdoc-openapi 固定为 3.0.3。该组合已在本机编译、启动并完成 PostgreSQL 18.7 集成验证。

领域对象表达金额、正常余额方向和凭证规则，不依赖 Spring MVC、JPA EntityManager、HTTP 或 React。Accounts 使用 Spring Data JPA 持久化客户账户并以 `@Version` 管理状态更新；Ledger 通过 `JdbcTemplate` 执行账本写入和显式行锁，使复合外键、延迟约束触发器及追加顺序在 SQL 层可见。`AccountService` 与 `LedgerWriter` 参与同一个 Spring 数据库事务。跨模块入口保持为小型模块公开 API。

## 模块边界

| 模块 | 拥有的行为与数据 | 允许的协作 |
|---|---|---|
| Identity | Customer、密码哈希、登录和服务端会话 | 不依赖其他业务模块 |
| Accounts | 客户账户、账户状态、所有权查询；开户需要时调用 Ledger 的开户分录 API | 可调用 Ledger；不调用 Transfers |
| Ledger | LedgerAccount、JournalEntry、Posting、AccountBalance；平衡、不可变和账本查询规则 | 为 Accounts、Transfers、History、Reconciliation 暴露明确应用 API |
| Transfers | `TransferService`、转账记录、事务编排和持久幂等回执 | 调用 Accounts 与 Ledger；不直接处理 HTTP 或前端状态 |
| TransactionHistory | 授权分页历史查询与稳定排序 | 调用 Accounts 的所有权查询和 Ledger/Transfers 的查询接口 |
| Reconciliation | 账本与余额投影比较、差异结果 | 调用 Accounts 与 Ledger 只读接口 |

代码以 `package-info.java` 将 `nz.ac.ledgercore.identity`、`accounts`、`ledger`、`transfers`、`transactionhistory`、`reconciliation` 声明为 Spring Modulith 模块。Accounts 显式允许依赖 Ledger；Transfers 显式允许依赖 Accounts 与 Ledger。`ApplicationModules.verify()` 在 P3 完整回归中通过。TransactionHistory、Reconciliation 和 Identity 仍没有业务实现。

P1 没有启用 Modulith JPA 事件持久化：当前用例没有异步模块事件，也没有对应的事务交付需求。未来若引入跨事务事件，必须先定义持久交付与失败恢复语义，并新增迁移及 PostgreSQL 验证。

## 运行时资金路径

开户由 `AccountService.openAccount` 插入 Account → `LedgerWriter` 创建客户存款账本账户 → 在同一事务写入 OPENING_FUNDING 凭证及两条 Posting → 从 Posting 重算 LedgerAccount 余额并更新 projection → 提交。P3 的 `TransferService.transfer` 首先校验服务端传入的客户 ID 是否拥有付款账户，并保留未提交的唯一幂等键；再按 UUID 顺序锁付款与收款 LedgerAccount、检查账户状态并读取最新账本余额。`LedgerWriter` 写入 TRANSFER JournalEntry 和两条 Posting、重算两个 projection，TransferService 保存 Transfer 与 JSONB 成功回执，最后将幂等记录转为 SUCCEEDED 并提交。所有步骤共享同一 PostgreSQL READ COMMITTED 事务。模拟入金和冲正也遵守账本事务。

TransferService 不是 HTTP Controller，尚无公开业务路由。未来 Controller 只能从服务端 SecurityContext 提供调用者 ID，不得接受请求体里的 owner ID。P3 服务层对源账户所有权失败统一返回 ACCOUNT_NOT_FOUND，实际 HTTP Problem Details 映射留给 P4/P5。

## 并发与一致性

资金余额的权威来源是 Posting。Dashboard 未来可读取事务内更新的 AccountBalance 投影。开户、模拟入金、冲正和转账都按 UUID 升序锁定相关 LedgerAccount；写入后从 Posting 重新聚合 normal-side balance 更新 projection。P3 的两笔事务竞争、余额不足并发和交叉转账测试使用 PostgreSQL READ COMMITTED 验证锁顺序。

PostgreSQL V2 对跨多行借贷平衡实施 deferred constraint trigger；行级 CHECK 表达单行正金额、币种、外键和状态约束。账本 UPDATE/DELETE 由数据库触发器拒绝。应用冲正通过新的 JournalEntry 和反向 Posting 完成，并以唯一外键保留原凭证。

## 幂等和网络结果未知

键由服务端认证客户 ID、操作名和客户端 Idempotency-Key 共同限定。指纹基于源账户、目标账户、NZD 和分币金额的规范形式。预留和成功回执与 Transfer 同事务提交；PostgreSQL 唯一键等待竞争请求，V4 deferred trigger 禁止未完成预留单独提交。同键同指纹返回数据库中保存的原回执，同键异指纹冲突。重放之前仍检查调用者对付款账户的所有权。HTTP 请求中的 owner 校验、认证和 CSRF 还未实现。

P3 已验证服务端提交后丢弃首次返回值并以原意图和原键读取相同回执；这模拟了提交后网络响应丢失，不等同真实 HTTP 断连。浏览器保存原操作意图、刷新恢复和结果未知界面属于 P4，尚未实现或验证。

## Identity 与 Web 安全边界

目标采用服务端会话 Cookie：HttpOnly、SameSite=Lax，生产 HTTPS 时启用 Secure；写请求使用 CSRF token。开发环境用 Vite 同源代理。P3 尚未实现身份、密码哈希或登录；当前 Spring Security 默认拒绝请求。后续认证用户 ID 必须来自服务端 SecurityContext，不来自请求 JSON、query string 或页面缓存。Spring Session 是否引入持久化待安全设计确定。

## 故障域

HTTP 层只在本地 PostgreSQL 事务提交后发出成功响应。若服务端已提交而浏览器未收到响应，服务端回执供原键查询/重放；浏览器不生成新键来“补偿”。不在数据库提交之外发送通知、消息或外部付款。没有 Kafka、Redis、Service Mesh 或 Kubernetes。
