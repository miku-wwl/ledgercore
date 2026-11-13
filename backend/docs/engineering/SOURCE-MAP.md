# LedgerCore P0–P4 来源与主张地图

本文是维护材料，不进入未来教材正文。SOURCE-ANALYSED 表示已检查来源，不表示 LedgerCore 行为已运行。ILLUSTRATIVE 表示本阶段设计方案，不是代码摘录。VERIFIED 仅用于本轮实际观察到的仓库状态或文件摘要。

| 目标章节/文件 | 重要主张 | 来源锚点 | 分类 | 限制 |
|---|---|---|---|---|
| README / PRD | 产品只处理 NZD 模拟资金；不接真实账户或外部结算 | 用户提供的 Master Prompt P0.2 与第 7 节 | ILLUSTRATIVE（本项目需求决策） | 需求文件尚未由运行应用验证 |
| ledger/Money.java、JournalEntry.java | NZD 分币精确解析；凭证借贷相等、冲正及转账生成新凭证 | LedgerCore P2–P4 Java 领域代码与 MoneyTests/JournalEntryTests | VERIFIED（单元测试和 PG 集成） | 性能与生产负载未测 |
| ledger/LedgerWriter.java / V2 migration | 开户资金、模拟入金、转账、冲正、投影从 Posting 重算 | P2/P3 Spring JDBC 代码；真实 PostgreSQL 18.7 ledger/transfer 集成测试；PostgreSQL 18 Transaction Isolation、Explicit Locking、CREATE TRIGGER 官方文档 | VERIFIED（9 项 P2 + 15 项 P3 PG 测试） | Reconciliation 报告待 P5 |
| identity/IdentityService.java、AuthenticationController.java | 邮箱规范化、BCrypt strength 12、注册登录会话及 CSRF | P4 实现；真实 HTTP/PostgreSQL `FullStackApiPostgresIntegrationTests` | VERIFIED（41 项 Maven 总回归中的 API 集成） | 未进行 P5 独立威胁建模/安全扫描 |
| accounts/AccountService.java、AccountController.java | JPA 乐观版本账户；开户和模拟资金同事务；认证主体驱动的所有权过滤 | P2/P4 代码；真实 PostgreSQL 与跨客户 HTTP 测试 | VERIFIED | 性能尚待 P5 查询计划实验 |
| transfers/TransferService.java、TransferController.java、V3/V4 migration | UUID 排序锁、READ COMMITTED、源余额校验、持久 Idempotency-Key、HTTP 错误映射及失败回滚 | P3/P4 代码；真实 PostgreSQL `TransferPostgresIntegrationTests` 和 Playwright 响应丢失流程 | VERIFIED（15 项 PG 转账测试；Chromium 真实网络恢复） | CI 尚未运行 |
| transactionhistory / reconciliation | 本人交易游标分页、安全分录教学视图、Posting 与投影只读对账 | P4 service/controller；PostgreSQL HTTP 集成与真实浏览器 | VERIFIED | 没有生产规模数据/查询延迟结论 |
| frontend/src | React 页面、OpenAPI 类型客户端、分币 BigInt、CSRF 会话和未知转账恢复 | P4 源码；`npm run build`、`npm run lint`、Chromium Playwright 流程与截图 | VERIFIED（构建/lint 通过，1 E2E passed） | 仅覆盖 MVP 的单一 Chromium 流程 |
| ARCHITECTURE / ADR | Java 21、Spring Boot 4.0.9、Spring Modulith 2.0.8 模块边界 | [Spring Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)；[Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html)；P4 `ModuleArchitectureTests` | VERIFIED（完整回归边界测试通过） | 不代表生产部署容量或认证安全审计 |
| API-CONTRACT | springdoc 3.0.3 OpenAPI 与已发布业务路由/状态码 | [springdoc compatibility and setup](https://springdoc.org/)；运行应用的 `/v3/api-docs`；真实 API 集成测试 | VERIFIED（P4） | P5 独立安全审查尚未完成 |
| BANK-OF-ANTHOS-REVIEW | 五模块责任、流水模型、缓存/轮询取舍及单体版本 | 上游提交 db35fea9fd090150e2398106aadb475576f80d94 的 README、Java/SQL 源码、LICENSE | SOURCE-ANALYSED | 只检查固定源码快照；没有运行上游系统；未复制代码 |
| ECHOCASE-REFERENCE-REVIEW | EchoCase 工程、测试隔离和教材来源经验 | 本机 EchoCase HEAD 9f68721e18f15ae603e6502fa46b8fa78f9df55c；当时有脏工作树；历史和模板修订清单分别固定不同来源 | SOURCE-ANALYSED | EchoCase 是只读对照；其历史技术栈、运行输出和未提交出版工作不证明 LedgerCore 行为 |
| IMPLEMENTATION-STATUS | 起始 main 提交、分支和缺少工具 | 本轮 git status/fetch/branch/log；本机工具发现；JDK 压缩包 SHA-256 校验 | VERIFIED（只限观察时点） | 后续状态随环境和提交变化；不能外推到未来会话 |
| LedgerCore P4 全栈行为 | 注册会话、账户所有权、转账幂等、分页历史、对账与刷新后网络结果恢复 | 完整 Maven `clean verify` 41 tests；真实 PostgreSQL 18.7；`npm run build`；Chromium E2E 1 passed | VERIFIED（P4 验收） | P5 CI、独立安全审查、性能实验及 P6 教材尚未完成 |

## 外部版本参考

- Spring Boot 4.0 文档目前列出 4.0.9，要求 Java 17 或以上以及 Maven 3.6.3 或以上；项目选择 Java 21 满足该文档的 Java 版本下限。
- Spring Modulith 的验证 API 能检查模块依赖无循环及模块间访问限制；这说明为什么选 Modulith，不证明任何 LedgerCore 模块已经通过验证。
- springdoc 项目说明其 3.x 支持 Spring Boot 4，Boot 4.0.x 使用 3.0.x 兼容版本线。
- PostgreSQL 18 文档用于解释 READ COMMITTED 每条命令快照、行级锁等待语义和可延迟 constraint trigger。应用行为仍须本地数据库实验确认。
