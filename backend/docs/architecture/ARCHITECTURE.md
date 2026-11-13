# LedgerCore 应用架构

## 决策边界

LedgerCore 采用 Java 21、Spring Boot 4.0.x 与 Spring Modulith 的单体应用。所有业务能力运行于一个进程、使用同一 PostgreSQL 数据库；模块是代码与业务边界，不是部署单元。P1 建立真实 Maven Wrapper 后再锁定精确依赖版本。

领域对象表达金额、正常余额方向和转账规则，不依赖 Spring MVC、JPA EntityManager、HTTP 或 React。Spring 的事务和持久化实现位于相应模块的应用/适配代码中；只有跨模块确有调用时才定义小型模块公开 API，不为目录齐全而制造空接口、DTO 或 Mapper。

## 模块边界

| 模块 | 拥有的行为与数据 | 允许的协作 |
|---|---|---|
| Identity | Customer、密码哈希、登录和服务端会话 | 不依赖其他业务模块 |
| Accounts | 客户账户、账户状态、所有权查询；开户需要时调用 Ledger 的开户分录 API | 可调用 Ledger；不调用 Transfers |
| Ledger | LedgerAccount、JournalEntry、Posting、AccountBalance；平衡、不可变和账本查询规则 | 为 Accounts、Transfers、History、Reconciliation 暴露明确应用 API |
| Transfers | 转账、初始资金、事务编排和幂等回执 | 调用 Accounts 与 Ledger；不直接处理 HTTP 或前端状态 |
| TransactionHistory | 授权分页历史查询与稳定排序 | 调用 Accounts 的所有权查询和 Ledger/Transfers 的查询接口 |
| Reconciliation | 账本与余额投影比较、差异结果 | 调用 Accounts 与 Ledger 只读接口 |

建议包路径为 nz.ac.ledgercore.identity、accounts、ledger、transfers、transactionhistory、reconciliation。P1/P2 中的真实依赖模型必须通过 Spring Modulith 验证；若实现需要的依赖与此表冲突，先修正边界并更新 ADR，而不是忽略验证。

## 运行时资金路径

浏览器同源请求 → Spring Security 会话身份和 CSRF 校验 → REST 适配器校验请求格式 → Transfers 应用用例 → Accounts 所有权与状态查询 → Ledger 领域规则和 PostgreSQL 事务 → Transfer、JournalEntry、Postings、余额投影、幂等回执一同提交 → HTTP 成功回执 → TanStack Query 更新已确认状态。

Controller 不判断会计方向，不自行读写 JPA EntityManager，也不将客户端提交的 owner ID 当作身份。所有权失败与资源不存在统一处理，避免用错误内容探测账户。

## 并发与一致性

资金余额的权威来源是 Posting。Dashboard 可读取事务内更新的 AccountBalance 投影，但付款校验读取锁定后的源 LedgerAccount 净账本余额。每个会影响同一 LedgerAccount 的资金操作先获取行锁，且多个账户始终按 UUID 升序锁定。事务隔离固定为 PostgreSQL READ COMMITTED；锁后用新的 SQL 语句读账本，避免只读旧快照来批准扣款。投影和 Transfer 与分录同事务更新。

PostgreSQL 对跨多行借贷平衡实施 deferred constraint trigger；行级 CHECK 只表达单行正金额、币种、外键和状态约束。账本 UPDATE/DELETE 由数据库触发器拒绝。业务冲正通过新的 Posting 完成。

## 幂等和网络结果未知

键由已认证客户、操作名和客户端 Idempotency-Key 共同限定。指纹基于规范化的源账户、目标账户、币种及最小单位金额。成功回执同 Transfer 一起提交。同键同指纹返回原回执，同键异指纹冲突。重放仍重新检查调用者对关联账户的权限。

浏览器在请求开始时创建一次意图和键，存入当前标签页 sessionStorage 以支持页面刷新恢复；请求断连时显示“结果待确认”，并用原意图和原键再次确认。更改金额或收款账户意味着新意图，不能沿用成功键。此设计保证同一标签页会话内的刷新恢复；不承诺浏览器关闭后、另一设备或服务器重启后的客户端意图恢复。

## Identity 与 Web 安全边界

采用服务端会话 Cookie：HttpOnly、SameSite=Lax，生产 HTTPS 时启用 Secure；写请求使用 CSRF token。开发环境用 Vite 同源代理。密码只保存单向强哈希。认证用户 ID 来自服务端 SecurityContext，不来自请求 JSON、query string 或页面缓存。Spring Session 是否引入持久化不在 P1 自动扩张；进程重启要求重新登录。

## 故障域

HTTP 层只在本地 PostgreSQL 事务提交后发出成功响应。若服务端已提交而浏览器未收到响应，服务端回执供原键查询/重放；浏览器不生成新键来“补偿”。不在数据库提交之外发送通知、消息或外部付款。没有 Kafka、Redis、Service Mesh 或 Kubernetes。
