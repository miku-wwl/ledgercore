# LedgerCore

LedgerCore 是一款 NZD 单币种模拟银行应用，用来学习 Java 全栈工程、复式记账与 PostgreSQL 交易一致性。它只处理模拟资金，不连接真实账户、银行或支付网络。

本项目采用 main-only 工作流：P0–P6 的开发、测试、修复、提交和教材出版都直接在 main 完成；不创建其他分支、worktree 或 PR。

## 业务范围

MVP 目标包括注册登录、模拟账户管理、账户余额、明确来源的模拟初始资金、内部转账、不可变双重记账、交易历史、持久幂等、并发扣款保护、事务回滚、余额对账、账户所有权授权和 React Dashboard。所有页面持续显示模拟银行用途。

不包括真实资金或银行账户、支付网关、外部结算、外汇、贷款、信用卡、利息、金融监管认证、区块链、AI、Kubernetes、Terraform 或无业务需要的微服务。

## 工程方向

| 层 | 选择 |
|---|---|
| 后端 | Java 21、Spring Boot 4.x、Spring Modulith、Maven Wrapper |
| 数据 | PostgreSQL、Spring Data JPA/Hibernate、Flyway |
| 安全与 API | Spring Security、Bean Validation、OpenAPI |
| 前端 | React 19、TypeScript、Vite、shadcn/ui、Tailwind CSS、TanStack Query |
| 验证 | JUnit、Mockito、真实 PostgreSQL 集成测试、Spring Modulith、Playwright、GitHub Actions |

精确依赖版本将在 P1 工程创建并实际兼容验证后固定。README 中的技术清单是目标，不表示这些组件已经实现。

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

仓库起始时只有 README 与 Apache-2.0 LICENSE，没有后端、前端、数据库迁移、测试、容器或 CI。当前工作先建立 P0 需求与金融设计，再进入 P1 实现。每阶段的真实验收状态见 IMPLEMENTATION-STATUS；不以文档数量代替可运行代码。

正式教材只能在 P5 工程最终验收后开始。教材必须固定源码提交、引用真实代码、标注未运行的场景，并以完整 PDF 渲染和逐页视觉检查作为出版验收。

## Bank of Anthos 来源

LedgerCore 受 [Google Cloud Bank of Anthos](https://github.com/GoogleCloudPlatform/bank-of-anthos) 启发，但独立实现，未从上游复制代码；项目与 Google 无关联或背书。只在未来实际复用代码时按 Apache License 2.0 保留来源和通知。审计细节与固定上游提交见 [Bank of Anthos 审计](backend/docs/upstream/BANK-OF-ANTHOS-REVIEW.md)。
