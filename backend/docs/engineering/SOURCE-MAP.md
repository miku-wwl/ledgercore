# LedgerCore P0–P5 来源与主张地图

本文是维护材料，不进入未来教材正文。SOURCE-ANALYSED 表示已检查来源，不表示 LedgerCore 行为已运行。ILLUSTRATIVE 表示本阶段设计方案，不是代码摘录。VERIFIED 仅用于本轮实际观察到的仓库状态或文件摘要。

| 目标章节/文件 | 重要主张 | 来源锚点 | 分类 | 限制 |
|---|---|---|---|---|
| README / PRD | 产品只处理 NZD 模拟资金；不接真实账户或外部结算 | 用户提供的 Master Prompt P0.2 与第 7 节 | ILLUSTRATIVE（本项目需求决策） | 需求文件尚未由运行应用验证 |
| ledger/Money.java、JournalEntry.java | NZD 分币精确解析；凭证借贷相等、冲正及转账生成新凭证 | LedgerCore P2–P4 Java 领域代码与 MoneyTests/JournalEntryTests | VERIFIED（单元测试和 PG 集成） | 性能与生产负载未测 |
| ledger/LedgerWriter.java / V2 migration | 开户资金、模拟入金、转账、冲正、投影从 Posting 重算 | P2/P3 Spring JDBC 代码；真实 PostgreSQL 18.7 ledger/transfer 集成测试；PostgreSQL 18 Transaction Isolation、Explicit Locking、CREATE TRIGGER 官方文档 | VERIFIED（9 项 P2 + 15 项 P3 PG 测试） | Reconciliation API 已实现；性能证据仅限合成数据实验 |
| identity/IdentityService.java、AuthenticationController.java、CustomerPrincipal.java | 邮箱规范化、BCrypt strength 12、注册登录会话及 CSRF；principal 的 `toString()` 不暴露邮箱/密码哈希 | P4 实现；P5 真实 HTTP/PostgreSQL `FullStackApiPostgresIntegrationTests` 与 `CustomerPrincipalTests` | VERIFIED（42 项 Maven 回归） | 无外部渗透测试或生产安全审计结论 |
| accounts/AccountService.java、AccountController.java | JPA 乐观版本账户；开户和模拟资金同事务；认证主体驱动的所有权过滤；生命周期转换目前没有客户 HTTP 路由 | P2/P4 代码；真实 PostgreSQL 与跨客户 HTTP 测试 | VERIFIED | P5 合成查询计划已测；无生产负载结论 |
| transfers/TransferService.java、TransferController.java、V3/V4 migration | UUID 排序锁、READ COMMITTED、源余额校验、持久 Idempotency-Key、HTTP 错误映射及失败回滚 | P3/P4 代码；真实 PostgreSQL `TransferPostgresIntegrationTests` 和 Playwright 响应丢失流程 | VERIFIED（15 项 PG 转账测试；Chromium 真实网络恢复） | CI 尚未运行 |
| transactionhistory / reconciliation | 本人交易游标分页、安全分录教学视图、Posting 与投影只读对账 | P4 service/controller；P5 真实 PostgreSQL 18.7 `EXPLAIN (ANALYZE, BUFFERS)` 查询计划 | VERIFIED | 单机合成数据，不代表生产规模或响应时限 |
| frontend/src | React 页面、OpenAPI 类型客户端、分币 BigInt、CSRF 会话和未知转账恢复 | P4 源码；`npm run build`、`npm run lint`、Chromium Playwright 流程与截图；P5 `npm audit` | VERIFIED（构建/lint、1 E2E passed；审计 0 high/critical，另有 4 moderate、2 low） | 仅覆盖 MVP 的单一 Chromium 流程；npm 报告中的低/中危仍可见 |
| `.github/workflows/ci.yml` | main-only Java/PostgreSQL、前端、OpenAPI、Chromium E2E、npm audit 与 OSV Maven 扫描 | 固定提交 `484b9ad1c70ba052849ee1a40e15e35b6d888c12` 的 [GitHub Actions run](https://github.com/miku-wwl/ledgercore/actions/runs/43377985041) | VERIFIED（verify 与 dependency-scan 均成功） | 仅证明该提交及其运行环境；不构成生产部署认证 |
| `backend/docs/security/P5-SECURITY-REVIEW.md` | 会话、所有权、CSRF、输入/错误泄露、日志与凭据边界 | 对照 P5 安全清单检查实现和 42 项 PostgreSQL 回归 | VERIFIED（本地审查） | 不是外部渗透测试或生产安全认证 |
| `backend/docs/performance/P5-PERFORMANCE-EXPERIMENT.md` | 账户余额、交易历史、Posting 和对账 SQL 的执行计划与索引效果 | 本地 PostgreSQL 18.7；10,000 accounts、50,000 transfers、120,000 postings；原始计划在 Git 忽略的 `work/` | VERIFIED（数据约束通过；索引对比结束后恢复） | 单机、合成、单次 SQL 测量，不提供生产负载结论 |
| ARCHITECTURE / ADR | Java 21、Spring Boot 4.0.9、Spring Modulith 2.0.8 模块边界 | [Spring Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)；[Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html)；P4 `ModuleArchitectureTests` | VERIFIED（完整回归边界测试通过） | 不代表生产部署容量或认证安全审计 |
| API-CONTRACT | springdoc 3.0.3 OpenAPI 与已发布业务路由/状态码 | [springdoc compatibility and setup](https://springdoc.org/)；运行应用的 `/v3/api-docs`；P5 负向 API 集成；CI 将检查生成类型无漂移 | VERIFIED（本地） | 最终提交后的远程 OpenAPI 合同 job 待通过 |
| BANK-OF-ANTHOS-REVIEW | 五模块责任、流水模型、缓存/轮询取舍及单体版本 | 上游提交 db35fea9fd090150e2398106aadb475576f80d94 的 README、Java/SQL 源码、LICENSE | SOURCE-ANALYSED | 只检查固定源码快照；没有运行上游系统；未复制代码 |
| ECHOCASE-REFERENCE-REVIEW | EchoCase 工程、测试隔离和教材来源经验 | 本机 EchoCase HEAD 9f68721e18f15ae603e6502fa46b8fa78f9df55c；当时有脏工作树；历史和模板修订清单分别固定不同来源 | SOURCE-ANALYSED | EchoCase 是只读对照；其历史技术栈、运行输出和未提交出版工作不证明 LedgerCore 行为 |
| IMPLEMENTATION-STATUS | 起始 main 提交、分支和缺少工具 | 本轮 git status/fetch/branch/log；本机工具发现；JDK 压缩包 SHA-256 校验 | VERIFIED（只限观察时点） | 后续状态随环境和提交变化；不能外推到未来会话 |
| LedgerCore P4 全栈行为 | 注册会话、账户所有权、转账幂等、分页历史、对账与刷新后网络结果恢复 | P4 完整回归 41 tests；P5 更新后 42 tests；干净 PostgreSQL 18.7 数据库；`npm run build`；Chromium E2E 1 passed；P5 commit CI 双 job 通过 | VERIFIED（本地干净数据库与 GitHub CI） | 无外部渗透测试或生产安全认证结论 |

## 外部版本参考

- Spring Boot 4.0 文档目前列出 4.0.9，要求 Java 17 或以上以及 Maven 3.6.3 或以上；项目选择 Java 21 满足该文档的 Java 版本下限。
- Spring Modulith 的验证 API 能检查模块依赖无循环及模块间访问限制；这说明为什么选 Modulith，不证明任何 LedgerCore 模块已经通过验证。
- springdoc 项目说明其 3.x 支持 Spring Boot 4，Boot 4.0.x 使用 3.0.x 兼容版本线。
- PostgreSQL 18 文档用于解释 READ COMMITTED 每条命令快照、行级锁等待语义和可延迟 constraint trigger。应用行为仍须本地数据库实验确认。
