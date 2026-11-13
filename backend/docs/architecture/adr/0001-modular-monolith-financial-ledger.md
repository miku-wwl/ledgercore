# ADR 0001：以单体模块和不可变双重记账为基础

- 状态：Accepted for P0; implementation pending
- 日期：2026-11-13
- 范围：LedgerCore MVP

## 背景

仓库当前只有 README 与许可证，没有现存业务代码。README 把余额、转账、模块边界和实验列为项目构想，却未定义客户存款负债的借贷方向、模拟资金来源、请求重放持久性或并发扣款协议。Bank of Anthos 展示的是教学用支付流水和 Google Cloud/Kubernetes 场景；LedgerCore 的目标是解释并验证应用内事务正确性。

## 决策

1. 使用 Java 21、Spring Boot 4.0.9、Spring Modulith 2.0.8 与 Maven Wrapper 3.10.0，部署形态为单一模块化单体，使用 PostgreSQL 与 Flyway。该版本组合已在 P1 构建、启动及 PostgreSQL 18.7 测试中验证。springdoc-openapi 使用 3.0.3。
2. 会计事实使用不可变 JournalEntry 与 Posting；单币种 NZD，amountMinor 以 long 表示分并由 PostgreSQL BIGINT 存储。API 使用精确十进制金额字符串，界面格式化不使用浮点算术。
3. 客户存款是贷方正常余额负债；初始资金由明确的 Simulation Reserve 资产账户对手方入账。
4. Posting 账本是资金事实权威来源；余额投影可重建。扣款在账户行锁下读取账本余额，不以缓存或客户端余额授权。
5. 转账、过账行、投影、Transfer 与成功幂等回执置于一个 PostgreSQL 事务。持久幂等按认证客户、操作、键唯一，并把请求指纹与稳定成功回执保存到同一提交。
6. 会话 Cookie 加 CSRF；账户所有权始终由服务端身份和 owner 关系验证。浏览器在当前标签页保存原键和原意图，以处理响应未知。
7. 先建立 Unit、真实 PostgreSQL Integration、Architecture 和浏览器 E2E 的职责，再依 P0–P6 顺序验收；P5 未完成前不出版正式教材。

## 后果

单体事务让内部 Posting、Transfer 和回执具有一个清楚的原子提交边界，不需要先引入消息代理、Redis 或分布式补偿。数据库约束要覆盖普通 CHECK 不能表达的跨行账平规则。投影错误会被对账发现，且资金校验仍读取账本。方案仍不能把模拟银行说成生产银行、真实结算或监管认证。

## 决策证据

参考审计见 backend/docs/upstream/BANK-OF-ANTHOS-REVIEW.md；金融事实见 backend/docs/domain/DOMAIN-MODEL.md；测试责任见 backend/docs/testing/TEST-STRATEGY.md。P1 只启用 Modulith 核心和模块验证，不启用事件持久化或事件可观测模块，因为当前没有异步事务事件需求；若后续增加，必须以持久化交付语义、迁移和真实数据库测试为前提。
