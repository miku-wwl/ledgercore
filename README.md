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

Spring Boot 4.0.9、Spring Modulith 2.0.8 和 OpenAPI 依赖已在 Java 21 上构建并运行验证。前端、Playwright 与 CI 仍是后续阶段工作。

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

P0 需求与金融设计、P1 Java 应用基础、P2 账本核心均已完成。P2 包含精确分币金额、客户账户生命周期、明确对手账户的模拟资金、复式凭证、余额投影重算、整笔冲正，以及 PostgreSQL 账平和只追加约束；25 项测试在 PostgreSQL 18.7 上通过。

当前仍未实现注册/登录、账户 HTTP API、内部转账、持久幂等、交易历史、对账或 React 界面。账户与账本操作目前是未公开给 HTTP 的应用服务；身份模块完成前，除健康与 OpenAPI 端点外的请求都会被拒绝。并发转账和请求重放将在 P3 实现。各阶段实际状态见 [IMPLEMENTATION-STATUS](backend/docs/engineering/IMPLEMENTATION-STATUS.md)。

### 本地运行与验证

需要 Java 21 和 PostgreSQL。先创建本地角色与数据库，再设置连接环境变量；Flyway 会在启动时创建客户与账户基础表。

```powershell
psql -U postgres -c "CREATE ROLE ledgercore LOGIN PASSWORD 'choose-a-local-password';"
psql -U postgres -c "CREATE DATABASE ledgercore OWNER ledgercore;"
$env:LEDGERCORE_DB_URL = 'jdbc:postgresql://localhost:5432/ledgercore'
$env:LEDGERCORE_DB_USERNAME = 'ledgercore'
$env:LEDGERCORE_DB_PASSWORD = 'choose-a-local-password'
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

正式教材只能在 P5 工程最终验收后开始。教材必须固定源码提交、引用真实代码、标注未运行的场景，并以完整 PDF 渲染和逐页视觉检查作为出版验收。

## Bank of Anthos 来源

LedgerCore 受 [Google Cloud Bank of Anthos](https://github.com/GoogleCloudPlatform/bank-of-anthos) 启发，但独立实现，未从上游复制代码；项目与 Google 无关联或背书。只在未来实际复用代码时按 Apache License 2.0 保留来源和通知。审计细节与固定上游提交见 [Bank of Anthos 审计](backend/docs/upstream/BANK-OF-ANTHOS-REVIEW.md)。
