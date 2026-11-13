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
| Transfers | 转账、初始资金、事务编排和幂等回执 | 调用 Accounts 与 Ledger；不直接处理 HTTP 或前端状态 |
| TransactionHistory | 授权分页历史查询与稳定排序 | 调用 Accounts 的所有权查询和 Ledger/Transfers 的查询接口 |
| Reconciliation | 账本与余额投影比较、差异结果 | 调用 Accounts 与 Ledger 只读接口 |

代码以 `package-info.java` 将 `nz.ac.ledgercore.identity`、`accounts`、`ledger`、`transfers`、`transactionhistory`、`reconciliation` 声明为 Spring Modulith 模块。P2 的 Accounts 显式允许依赖 Ledger；`ApplicationModules.verify()` 在 P2 测试中通过。其它业务模块仍未实现。

P1 没有启用 Modulith JPA 事件持久化：当前用例没有异步模块事件，也没有对应的事务交付需求。未来若引入跨事务事件，必须先定义持久交付与失败恢复语义，并新增迁移及 PostgreSQL 验证。

## 运行时资金路径

P2 的内部开户路径为：`AccountService.openAccount` 插入 Account → `LedgerWriter` 创建客户存款账本账户 → 在同一事务写入 OPENING_FUNDING 凭证及两条 Posting → 从 Posting 重算 LedgerAccount 余额并更新 projection → 提交。模拟入金和冲正也遵守账本事务。该应用服务目前没有 HTTP 路由；浏览器到 Transfers 的路径仍待 P3/P4 完成。

Controller 不判断会计方向，不自行读写 JPA EntityManager，也不将客户端提交的 owner ID 当作身份。所有权失败与资源不存在统一处理，避免用错误内容探测账户。

## 并发与一致性

资金余额的权威来源是 Posting。Dashboard 未来可读取事务内更新的 AccountBalance 投影。P2 开户、模拟入金和冲正先按 UUID 升序锁定相关 LedgerAccount；写入后从 Posting 重新聚合 normal-side balance 更新 projection。实际扣款余额检查、同余额竞争和 Transfer 原子提交属于 P3，届时须使用 PostgreSQL READ COMMITTED 和同一锁协议。

PostgreSQL V2 对跨多行借贷平衡实施 deferred constraint trigger；行级 CHECK 表达单行正金额、币种、外键和状态约束。账本 UPDATE/DELETE 由数据库触发器拒绝。应用冲正通过新的 JournalEntry 和反向 Posting 完成，并以唯一外键保留原凭证。

## 幂等和网络结果未知

键由已认证客户、操作名和客户端 Idempotency-Key 共同限定。指纹基于规范化的源账户、目标账户、币种及最小单位金额。成功回执同 Transfer 一起提交。同键同指纹返回原回执，同键异指纹冲突。重放仍重新检查调用者对关联账户的权限。

浏览器在请求开始时创建一次意图和键，存入当前标签页 sessionStorage 以支持页面刷新恢复；请求断连时显示“结果待确认”，并用原意图和原键再次确认。更改金额或收款账户意味着新意图，不能沿用成功键。此设计保证同一标签页会话内的刷新恢复；不承诺浏览器关闭后、另一设备或服务器重启后的客户端意图恢复。

## Identity 与 Web 安全边界

采用服务端会话 Cookie：HttpOnly、SameSite=Lax，生产 HTTPS 时启用 Secure；写请求使用 CSRF token。开发环境用 Vite 同源代理。密码只保存单向强哈希。认证用户 ID 来自服务端 SecurityContext，不来自请求 JSON、query string 或页面缓存。Spring Session 是否引入持久化不在 P1 自动扩张；进程重启要求重新登录。

## 故障域

HTTP 层只在本地 PostgreSQL 事务提交后发出成功响应。若服务端已提交而浏览器未收到响应，服务端回执供原键查询/重放；浏览器不生成新键来“补偿”。不在数据库提交之外发送通知、消息或外部付款。没有 Kafka、Redis、Service Mesh 或 Kubernetes。
