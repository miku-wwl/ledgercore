# LedgerCore

LedgerCore 是一款 NZD 单币种模拟银行应用，用来学习 Java 全栈工程、复式记账与 PostgreSQL 交易一致性。它只处理模拟资金，不连接真实账户、银行或支付网络。

本项目采用 main-only 工作流：P0–P6 的开发、测试、修复、提交和教材出版都直接在 main 完成；不创建其他分支、worktree 或 PR。

## 业务范围

MVP 目标包括注册登录、模拟账户管理、账户余额、明确来源的模拟初始资金、内部转账、不可变双重记账、交易历史、持久幂等、并发扣款保护、事务回滚、余额对账、账户所有权授权和 React Dashboard。所有页面持续显示模拟银行用途。

不包括真实资金或银行账户、支付网关、外部结算、外汇、贷款、信用卡、利息、金融监管认证、区块链、AI、Kubernetes、Terraform 或无业务需要的微服务。

## 工程方向

| 层 | 选择 |
|---|---|
| 后端 | Java 21、Spring Boot 4.0.9、Spring Modulith 2.0.8、Maven Wrapper 3.10.0 |
| 数据 | PostgreSQL、Spring Data JPA/Hibernate、Flyway |
| 安全与 API | Spring Security、Bean Validation、springdoc-openapi 3.0.3 |
| 前端 | React 19、TypeScript、Vite、shadcn/ui、Tailwind CSS、TanStack Query |
| 验证 | JUnit、Mockito、真实 PostgreSQL 集成测试、Spring Modulith、Playwright、GitHub Actions |

Spring Boot 4.0.9、Spring Modulith 2.0.8、业务 HTTP API 和 React 前端已通过本机及 main CI 验证。P5 使用真实 PostgreSQL 和 Chromium Playwright 完成最终验收；固定应用源码基线 `484b9ad1c70ba052849ee1a40e15e35b6d888c12` 的 `verify` 与 Maven OSV 扫描均通过。P6 中文教材已完成出版验收：20 章、22 幅原创 SVG、12 张真实 UI 截图和 46 页 PDF，见 [教材说明](manual/README.md) 与 [编辑验收记录](manual/reference/EDITORIAL-ACCEPTANCE.md)。

## 账务模型

金额以分表示：Java long 和 PostgreSQL BIGINT；JSON API 使用规范 NZD 十进制字符串；禁止浮点金额运算。客户存款账户是银行的负债，正常余额在贷方。开户模拟赠金来自显式 Simulation Reserve 资产账户。客户转账借记付款人存款负债、贷记收款人存款负债。JournalEntry 中 Posting 借贷必须平衡；成功后不修改或删除，错误通过新冲正凭证更正。账本是事实来源，展示余额是可重建投影。

## 文档

- 需求：[PRD](backend/docs/requirements/PRD.md)
- 领域：[DOMAIN-MODEL](backend/docs/domain/DOMAIN-MODEL.md)、[LEDGER-INVARIANTS](backend/docs/domain/LEDGER-INVARIANTS.md)
- 架构：[ARCHITECTURE](backend/docs/architecture/ARCHITECTURE.md)、[ADR 0001](backend/docs/architecture/adr/0001-modular-monolith-financial-ledger.md)
- 数据：[SCHEMA](backend/docs/database/SCHEMA.md)、[API 合同](backend/docs/api/API-CONTRACT.md)
- 工程：[TEST-STRATEGY](backend/docs/testing/TEST-STRATEGY.md)、[IMPLEMENTATION-STATUS](backend/docs/engineering/IMPLEMENTATION-STATUS.md)
- 来源地图：[SOURCE-MAP](backend/docs/engineering/SOURCE-MAP.md)
- 上游：[Bank of Anthos 审计](backend/docs/upstream/BANK-OF-ANTHOS-REVIEW.md)、[EchoCase 参考审查](backend/docs/upstream/ECHOCASE-REFERENCE-REVIEW.md)

## 当前状态

P0–P5 已完成；P6 教材 PDF 已完成出版与视觉验收。阶段状态及 main CI 证据见 [IMPLEMENTATION-STATUS](backend/docs/engineering/IMPLEMENTATION-STATUS.md)。应用包含精确分币金额、明确资金来源的复式账本、原子内部转账、稳定顺序账户行锁、持久幂等回执、安全会话、账户/历史/对账 API，以及刷新后恢复未知结果的 React 流程。P5 的 42 项 Maven 回归、全新 PostgreSQL 18.7 数据库验收、前端构建/lint、OpenAPI 类型无漂移和 Chromium E2E 均通过；GitHub Actions `verify` 与 OSV Maven 扫描通过，见 [run 43377985041](https://github.com/miku-wwl/ledgercore/actions/runs/43377985041)。

阶段状态、P5 安全审查与合成查询计划实验见 [IMPLEMENTATION-STATUS](backend/docs/engineering/IMPLEMENTATION-STATUS.md)、[P5 Security Review](backend/docs/security/P5-SECURITY-REVIEW.md) 和 [P5 性能实验](backend/docs/performance/P5-PERFORMANCE-EXPERIMENT.md)。

### 本地运行与验证

需要 Java 21 和 PostgreSQL。先创建本地角色与数据库，再设置连接环境变量；Flyway 会在启动时执行当前版本的 V1–V4 迁移。

```powershell
psql -U postgres -c "CREATE ROLE ledgercore LOGIN PASSWORD 'choose-a-local-password';"
psql -U postgres -c "CREATE DATABASE ledgercore OWNER ledgercore;"
$env:LEDGERCORE_DB_URL = 'jdbc:postgresql://localhost:5432/ledgercore'
$env:LEDGERCORE_DB_USERNAME = 'ledgercore'
$env:LEDGERCORE_DB_PASSWORD = 'choose-a-local-password'
$env:LEDGERCORE_COOKIE_SECURE = 'false' # 仅供本地 HTTP 开发；默认值 true 适用于 HTTPS 部署
.\backend\mvnw.cmd -f .\backend\pom.xml spring-boot:run
```

健康检查：`GET /actuator/health`。OpenAPI 文档：`GET /v3/api-docs`。

运行测试需要一个独立的 PostgreSQL 测试数据库；测试不会清理或重建数据库：

```powershell
psql -U postgres -c "CREATE ROLE ledgercore_test LOGIN PASSWORD 'choose-a-test-password';"
psql -U postgres -c "CREATE DATABASE ledgercore_test OWNER ledgercore_test;"
$env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/ledgercore_test'
$env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_test'
$env:LEDGERCORE_TEST_DB_PASSWORD = 'choose-a-test-password'
.\backend\mvnw.cmd -f .\backend\pom.xml clean verify
```

测试数据库需由开发者单独创建并授权给测试角色。不要把真实凭据提交到仓库。

启动界面前，先按上述步骤运行后端，再于另一终端执行：

```powershell
cd .\frontend
npm ci
npm run dev
```

Vite 将 `/api` 代理到本机 Spring Boot 服务。浏览器验收需要已安装 Chromium，并且后端与隔离测试数据库可用：

```powershell
npm run build
npm run test:e2e
```

Playwright 会检查注册、开户、CSRF、转账失败、提交后响应丢失与原键恢复、历史分页、账本对账、跨客户 404、登出/登录及窄屏布局。

正式教材只能在 P5 工程最终验收后开始。教材必须固定源码提交、引用真实代码、标注未运行的场景，并以完整 PDF 渲染和逐页视觉检查作为出版验收。

## Bank of Anthos 来源

LedgerCore 受 [Google Cloud Bank of Anthos](https://github.com/GoogleCloudPlatform/bank-of-anthos) 启发，但独立实现，未从上游复制代码；项目与 Google 无关联或背书。只在未来实际复用代码时按 Apache License 2.0 保留来源和通知。审计细节与固定上游提交见 [Bank of Anthos 审计](backend/docs/upstream/BANK-OF-ANTHOS-REVIEW.md)。
