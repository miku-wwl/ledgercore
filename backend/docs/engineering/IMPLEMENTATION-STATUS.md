# LedgerCore 阶段状态

更新时间：2026-11-13（Pacific/Auckland）

## 当前基线

- 工作流：main-only；P0–P2 均已提交并推送到 main；未创建分支、Git worktree 或 PR。
- 当前提交：P2 `205f2f9`；`origin/main` 与本地一致。
- 当前评估：P0 PASS；P1 PASS；P2 PASS。接下来实施 P3 转账、并发与持久幂等。
- 已验证工具：Temurin 21.0.13+9（ZIP SHA-256 `a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9`）；Spring Boot 4.0.9；Spring Modulith 2.0.8；Springdoc 3.0.3；Maven Wrapper 3.10.0。
- 本地验证数据库：官方 EDB PostgreSQL 18.7 Windows 二进制，解压于 Git 忽略的 `work/postgresql-binaries`；集群位于 `work/postgresql-data`，仅监听 `127.0.0.1:55432`。`ledgercore_test` 是隔离测试库。P1/P2 使用真实本地 PostgreSQL，未使用 H2 或纯 Mock；Testcontainers 未使用。
- 本地工具链、数据库、依赖缓存和日志均在 Git 忽略的 `work/` 下；不是 Git worktree。
- 参考：EchoCase 是只读对照，读取时本地 HEAD 为 `9f68721e18f15ae603e6502fa46b8fa78f9df55c`，工作区存在未提交改动和未跟踪教材文件；Bank of Anthos 固定审计提交 `db35fea9fd090150e2398106aadb475576f80d94`。两个参考仓库均未修改。

## 阶段进度

| 阶段 | 状态 | 已知完成项 | 未完成验收 | 下一步 |
|---|---|---|---|---|
| P0 Requirements/Domain/Architecture | PASS | PRD、领域模型、十项不变量、架构/数据库/API/测试策略、ADR、状态和来源地图已提交；Bank of Anthos 与 EchoCase 只读审计完成 | 后续实现可随工程证据修订 | 进入 P1 |
| P1 Java 基础 | PASS | Initializr 后端；V1 customers/accounts；PostgreSQL 18.7 迁移；安全默认拒绝、Problem Details、correlation ID、健康、OpenAPI；8 项 Maven 测试、真实打包应用启动与 HTTP 检查 | 注册/登录与账户 HTTP API 尚未实现（后续阶段） | 完成 |
| P2 Ledger | PASS | 精确分币金额；客户账户生命周期；模拟储备开户资金与模拟入金；复式凭证、整笔冲正、PostgreSQL 账平/只追加约束、余额投影重算；17 项 P2 测试通过 | 转账、身份/API 与并发扣款不属于 P2 验收 | 完成 |
| P3 Transfers/Concurrency | IN_PROGRESS | 事务协议与锁顺序见 ADR；P2 账本服务可复用 | 持久幂等、故障注入、确定性 PostgreSQL 竞争测试、真实转账用例 | 实现 Transfer 及同事务幂等回执 |
| P4 React/Integration | NOT_STARTED | UI 状态边界见 ARCHITECTURE | 真实 API 前端、真实浏览器及未知结果恢复 | P3 PASS 后开始 |
| P5 Security/Testing/Acceptance | NOT_STARTED | 分层策略见 TEST-STRATEGY | 安全、查询计划、CI、最终提交检查 | P4 PASS 后开始 |
| P6 Textbook | NOT_STARTED | 暂无教材章节或 PDF | 稳定源码基线、真实截图/实验、完整排版与逐页 QA | 仅在 P5 PASS 后启动正式出版 |

## 重要决定

- 金额：Java long 分、PostgreSQL BIGINT、API 两位小数十进制字符串、浏览器 BigInt；禁止浮点金额运算。
- 存款会计：客户存款是贷方正常余额负债；开户模拟资金有 Simulation Reserve 资产对手方。
- 权威数据：Posting 是资金事实；余额投影可重建。扣款在锁后读取账本净余额。
- 架构：Spring Boot Modular Monolith；PostgreSQL + Flyway；无微服务、Kafka、Redis 或集群。
- 转账：统一数据库事务；稳定排序的账户行锁；持久 Idempotency-Key 与成功回执。
- API：服务端会话 Cookie + CSRF；所有权按服务端 Customer ID 验证。
- 冲正：MVP 只支持整笔冲正；若恢复资金会令客户存款负债为负，则拒绝自动冲正并报告恢复需求；尚无应收/追回业务。

## 阶段验收证据

- P0 提交：95d529600eb50c1b871ebeef1be0906ab701cc37，main。
- P1 提交：5fe3dd9af6a17826d08ceac6a04235afbfa2c2f0，main / origin/main。
- P1 当前实现包含真实迁移 `backend/src/main/resources/db/migration/V1__create_customer_and_account_tables.sql`；Flyway 在 PostgreSQL 18.7 执行并重复验证后保持 schema version 1。
- P1 验证命令：`backend/mvnw.cmd -f backend/pom.xml -Dmaven.repo.local=D:\workshop\nov\ledgercore\work\m2 clean verify`；环境设置 `MAVEN_USER_HOME=D:\workshop\nov\ledgercore\work\maven-home`、`JAVA_HOME=Temurin 21.0.13+9` 和 `LEDGERCORE_TEST_DB_PASSWORD` 指向 `ledgercore_test`。最终退出码 0，8 tests、0 failures、0 errors。
- P1 8 项：2 个相关 ID 纯单元测试；5 个真实随机端口 HTTP/PostgreSQL 应用集成测试（健康、ID 生成、401 Problem Details、OpenAPI、Flyway 实际表）；1 个 Spring Modulith 边界测试。
- P1 独立应用：打包 JAR 以 Java 21 启动，Tomcat 在 8080 启动并连接 PostgreSQL 18.7；`GET /actuator/health` 200/UP 且回显相关 ID；`GET /v3/api-docs` 200 并包含 LedgerCore API；未认证请求返回 401 `application/problem+json`。应用已正常停止。
- P2 提交：`205f2f9`，main / origin/main。
- P2 验收：完整 Maven `clean verify` 退出码 0，共 25 tests、0 failures、0 errors、0 skipped；含 2 AccountDomain、2 CorrelationId、3 JournalEntry、9 LedgerPostgresIntegration、3 Money、5 应用 HTTP/PostgreSQL、1 Modulith 测试。P2 增量 17 项。
- P2 覆盖：真实 PostgreSQL 18.7 执行 V1/V2；开户资金和模拟入金双重记账；projection 与账本余额一致；拒绝不平衡凭证和 UPDATE/DELETE；重复开户资金与无效金额拒绝；整笔冲正；账户暂停/恢复/零余额关闭；写入故障回滚。
- P2 命令：`backend/mvnw.cmd -f backend/pom.xml -Dmaven.repo.local=D:\workshop\nov\ledgercore\work\m2 -Dstyle.color=never clean verify`；`JAVA_HOME` 为 `work/toolchain/jdk21/jdk-21.0.13+9`，`MAVEN_USER_HOME` 为 `work/maven-home`，测试连接隔离库 `ledgercore_test`。最终退出码 0。
- P2 Flyway：V2 `backend/src/main/resources/db/migration/V2__create_ledger_tables.sql` 在 PostgreSQL 18.7 测试库执行并由集成测试验证。
- P0 检查：13 个 Markdown 文件的本地链接均可解析；必需的 12 份 P0 文档存在；INV-01 至 INV-10 全部有记录；暂存 diff 无空白错误。这些是文档设计检查，不是应用测试。

## NOT_RUN 与阻塞项

- P3 的转账并发和持久幂等验收尚未完成；P4 React/Playwright、P5 最终 CI/安全验收、P6 教材出版尚未开始。P2 以外未声称通过的功能不得视为已验收。
- 当前身份模块尚无注册、登录或会话创建用例；Security 的默认用户服务明确拒绝认证，业务 API 尚不可用。
- GitHub Actions 结果 NOT_RUN；当前尚无前端真实界面、E2E 截图、性能数据或教材 PDF。

P6 正式教材写作与 PDF 出版只有在 P5 PASS 后才开始；不得用 P0/P1 设计文档替代真实实现内容。
