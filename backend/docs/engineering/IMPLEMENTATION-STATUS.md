# LedgerCore 阶段状态

更新时间：2026-11-13（Pacific/Auckland）

## 当前基线

- 工作流：main-only；P0–P3 已提交并推送到 main；P4 在 main 上完成实现与验收，正在准备阶段提交；未创建分支、Git worktree 或 PR。
- 当前基线：P4 验收基于 `53960bd` 加本地 P4 改动；提交后补录精确 SHA。
- 当前评估：P0 PASS；P1 PASS；P2 PASS；P3 PASS；P4 PASS；开始 P5 安全、CI、查询性能与最终验收。
- 已验证工具：Temurin 21.0.13+9（ZIP SHA-256 `a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9`）；Spring Boot 4.0.9；Spring Modulith 2.0.8；Springdoc 3.0.3；Maven Wrapper 3.10.0。
- 本地验证数据库：官方 EDB PostgreSQL 18.7 Windows 二进制，解压于 Git 忽略的 `work/postgresql-binaries`；集群位于 `work/postgresql-data`，仅监听 `127.0.0.1:55432`。`ledgercore_test` 是隔离测试库。P1–P3 使用真实本地 PostgreSQL，未使用 H2 或纯 Mock；Testcontainers 未使用。
- 本地工具链、数据库、依赖缓存和日志均在 Git 忽略的 `work/` 下；不是 Git worktree。
- 参考：EchoCase 是只读对照，读取时本地 HEAD 为 `9f68721e18f15ae603e6502fa46b8fa78f9df55c`，工作区存在未提交改动和未跟踪教材文件；Bank of Anthos 固定审计提交 `db35fea9fd090150e2398106aadb475576f80d94`。两个参考仓库均未修改。

## 阶段进度

| 阶段 | 状态 | 已知完成项 | 未完成验收 | 下一步 |
|---|---|---|---|---|
| P0 Requirements/Domain/Architecture | PASS | PRD、领域模型、十项不变量、架构/数据库/API/测试策略、ADR、状态和来源地图已提交；Bank of Anthos 与 EchoCase 只读审计完成 | 后续实现可随工程证据修订 | 进入 P1 |
| P1 Java 基础 | PASS | Initializr 后端；V1 customers/accounts；PostgreSQL 18.7 迁移；安全默认拒绝、Problem Details、correlation ID、健康、OpenAPI；8 项 Maven 测试、真实打包应用启动与 HTTP 检查 | 注册/登录与账户 HTTP API 尚未实现（后续阶段） | 完成 |
| P2 Ledger | PASS | 精确分币金额；客户账户生命周期；模拟储备开户资金与模拟入金；复式凭证、整笔冲正、PostgreSQL 账平/只追加约束、余额投影重算；17 项 P2 测试通过 | 转账、身份/API 与并发扣款不属于 P2 验收 | 完成 |
| P3 Transfers/Concurrency | PASS | 原子转账、服务层源所有权检查、账户状态/余额校验、UUID 排序行锁、持久幂等、稳定回执、V3/V4 数据约束；15 项 P3 PostgreSQL 测试与完整回归通过 | HTTP 身份映射、公开路由与浏览器网络恢复属于 P4/P5 | 完成 |
| P4 React/Integration | PASS | 会话注册/登录、账户与转账 API、历史分页、分录详情、只读对账、React 全栈 UI；刷新后以原键恢复真实网络响应丢失；41 项 Maven 测试、前端构建、Chromium E2E 均通过 | P5 的威胁审查、CI 与查询性能实验 | 完成 |
| P5 Security/Testing/Acceptance | IN_PROGRESS | P4 的全栈 HTTP、PostgreSQL 和浏览器验收通过 | 安全代码审查与负向测试、CI 数据库流水线、查询计划、发布前验收 | 完成并复核 main |
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
- P3 提交：`a91c5f0`，main / origin/main。
- P3 实现：`TransferService.transfer` 使用显式 READ COMMITTED；先检查服务层传入的客户 ID 对付款账户的所有权，再以客户/操作/Idempotency-Key 唯一键保留事务内意图，按 UUID 顺序锁付款和收款账本，检查账户开放状态与权威 Posting 余额，写入双边 TRANSFER 凭证、两个 projection、Transfer 及包含 POSTED 回执的 JSONB 幂等记录后提交。失败请求连同预留一并回滚；同键同意图返回原回执，同键异意图冲突。
- P3 Flyway：V3 创建 transfers/idempotency_records 并在提交点校验 Transfer 与账本凭证匹配；V4 约束幂等记录只能以 SUCCEEDED 状态提交且成功后不可修改/删除。V1–V4 在隔离 PostgreSQL 18.7 测试库运行通过。
- P3 验收：完整 Maven `clean verify` 退出码 0，共 40 tests、0 failures、0 errors、0 skipped；其中 `TransferPostgresIntegrationTests` 15 项真实 PostgreSQL 测试通过，`ModuleArchitectureTests` 通过。
- P3 覆盖：双事务超额支出仅允许余额范围内提交；双向交叉转账不死锁；同键同意图并发只产生一个转账，同键异意图返回冲突；检查源账户所有权和关闭账户；注入 JournalEntry、第二 Posting、projection、回执写入失败均无部分状态；丢弃首次成功结果后用原键恢复同一回执；数据库拒绝不匹配 Transfer/Posting 及未完成幂等记录。
- P3 验证命令：`backend/mvnw.cmd -f backend/pom.xml -Dmaven.repo.local=D:\workshop\nov\ledgercore\work\m2 -Dstyle.color=never clean verify`；Java 21.0.13+9；Maven 缓存 `work/m2`；数据库 `ledgercore_test`。最终退出码 0。
- P4 后端实现：`identity` 提供注册/登录/当前客户/退出与 CSRF 会话接口；`accounts` 提供本人账户查询、创建和详情；`transfers` 将服务端 principal、CSRF 与 `Idempotency-Key` 接到原子转账服务；`transactionhistory` 提供授权游标分页及不暴露对方账户 ID 的分录详情；`reconciliation` 独立比较 Posting 余额与投影。公共 API 错误单独成 Modulith 模块，避免异常映射形成依赖环。
- P4 安全实现：BCrypt strength 12；服务端 session、登录轮换 Session ID；Cookie HttpOnly/SameSite=Lax，Secure 由 `LEDGERCORE_COOKIE_SECURE` 控制；CSRF 写请求保护；跨客户账户和历史统一返回 404。所有权身份由服务端主体派生。
- P4 后端验收：使用 Temurin 21.0.13+9、真实 PostgreSQL 18.7 隔离库 `ledgercore_test`，`backend/mvnw.cmd clean verify` 退出码 0；41 tests、0 failures、0 errors、0 skipped。包含新全栈 HTTP/API 测试、15 项转账 PostgreSQL 测试、9 项账本 PostgreSQL 测试与 Modulith 架构检查。
- P4 前端：React 19、TypeScript 5、Vite 8、shadcn/ui、Tailwind 4、TanStack Query；API 类型从运行中的 OpenAPI 生成；金额使用 BigInt 分币。`npm run build` 和无警告的 `npm run lint` 均通过。
- P4 浏览器验收：`frontend/npm run test:e2e` 真实 Chromium 1 passed；流程包括注册/开户、CSRF、余额不足、真实转账、在服务端提交后丢弃网络响应、刷新并用原幂等键恢复、账户对账、历史分页与教学分录、跨客户 404、登出/登录、390px 无横向溢出。截图位于 Git 忽略的 `work/evidence/p4-dashboard-desktop.png` 与 `p4-dashboard-mobile.png`。
- P4 浏览器断言复核：重对账卡位于账户详情页；E2E 在正确路由验证 `Ledger and projection match` 后再访问历史列表。首次运行因断言放错路由失败，修正后完整流程通过。
- P0 检查：13 个 Markdown 文件的本地链接均可解析；必需的 12 份 P0 文档存在；INV-01 至 INV-10 全部有记录；暂存 diff 无空白错误。这些是文档设计检查，不是应用测试。

## NOT_RUN 与阻塞项

- P5 CI 结果、依赖/静态安全审查、负向安全验证、EXPLAIN (ANALYZE, BUFFERS) 查询计划与干净环境发布验收仍 NOT_RUN。
- P6 教材与 PDF 尚未开始；须在 P5 PASS、确定可复现源码基线后再出版。
- 当前完整应用只处理本地模拟 NZD 资金，没有真实银行、外部付款、生产容量或监管认证结论。

P6 正式教材写作与 PDF 出版只有在 P5 PASS 后才开始；不得用 P0/P1 设计文档替代真实实现内容。
