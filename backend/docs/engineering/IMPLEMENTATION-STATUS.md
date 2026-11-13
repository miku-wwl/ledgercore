# LedgerCore 阶段状态

更新时间：2026-11-13（Pacific/Auckland）

## 当前基线

- 工作流：只在 main；本 session 开始时 origin/main 与本地一致，起始提交 b40caa25522e37e4171d19cc8d4e2374aa3771dd。
- 当前评估：P0 设计文档已于 95d529600eb50c1b871ebeef1be0906ab701cc37 提交并通过静态文档检查；P1 正在启动。
- 实现语言目标：Java 21；当前主机初始没有 Java，本 session 已在工作区外的 work/jdk21 下载并验证 Temurin 21.0.13+9 压缩包 SHA-256 为 a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9，已解压；java 与 javac 均报告 21.0.13。
- 数据库/容器：未发现全局 PostgreSQL 命令/服务。Docker CLI 位于 Docker Desktop 安装目录但不在 PATH；com.docker.service 当前停止。Docker daemon 可用性尚未确认。
- 远端分支：fetch 后只看到 origin/main；没有创建分支或 worktree。
- 参考：EchoCase 是只读对照，读取时本地 HEAD 为 9f68721e18f15ae603e6502fa46b8fa78f9df55c，工作区存在未提交改动和未跟踪教材文件；Bank of Anthos 固定审计提交 db35fea9fd090150e2398106aadb475576f80d94。

## 阶段进度

| 阶段 | 状态 | 已知完成项 | 未完成验收 | 下一步 |
|---|---|---|---|---|
| P0 Requirements/Domain/Architecture | PASS | PRD、领域模型、十项不变量、架构/数据库/API/测试策略、ADR、状态和来源地图已提交；Bank of Anthos 与 EchoCase 只读审计完成 | 后续实现可随工程证据修订 | 进入 P1 |
| P1 Java 基础 | IN_PROGRESS | Temurin 21.0.13 已下载、校验并解压；官方 Initializr 提供 Spring Boot 4.0.9 | Maven 工程、数据库迁移、启动/健康/API、Modulith；PostgreSQL 环境未确认 | 生成官方 Spring Initializr 后端并验证可构建与启动 |
| P2 Ledger | NOT_STARTED | 设计见 DOMAIN-MODEL | 实体、迁移、分录、真实 PostgreSQL 测试 | P1 PASS 后开始 |
| P3 Transfers/Concurrency | NOT_STARTED | 事务与锁协议见 ADR | 持久幂等、故障注入、确定性 PostgreSQL 竞争测试 | P2 PASS 后开始 |
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
- P0 检查：13 个 Markdown 文件的本地链接均可解析；必需的 12 份 P0 文档存在；INV-01 至 INV-10 全部有记录；暂存 diff 无空白错误。
- 这些是文档和设计检查，不是 LedgerCore 应用测试。

## NOT_RUN 与阻塞项

- Maven 编译、单元测试、Flyway、PostgreSQL 迁移、应用启动与健康检查：P1 尚未运行。
- PostgreSQL integration、Playwright E2E、性能实验、GitHub Actions 运行和教材渲染：NOT_RUN。
- P1 是否 BLOCKED 取决于能否启动已安装 Docker Desktop 或提供可运行的本地 PostgreSQL；目前未将未尝试的 daemon 状态写成 BLOCKED。

所有未通过阶段必须保留 NOT_STARTED、IN_PROGRESS、PARTIAL、BLOCKED 或 FAILED，不能提前写 PASS/COMPLETE。
