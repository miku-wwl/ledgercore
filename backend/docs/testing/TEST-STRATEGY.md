# 测试策略

测试按要证明的边界分层。Mockito 或内存替身只能证明调用和领域行为，不能证明 PostgreSQL 锁、约束、事务提交或回滚。

| 类型 | 证明目标 | 典型场景 |
|---|---|---|
| Domain unit | 纯规则和精确金额，不启动 Spring/数据库 | 借贷平衡、贷方正常余额、分币解析、溢出、负数拒绝、冲正规则 |
| Application unit | 用例编排、授权决策、规范化指纹和错误分类 | 同键重放/换意图、owner 过滤、失败不返回伪成功 |
| PostgreSQL integration | 真实迁移、JPA、约束、事务、锁和 SQL | deferred 账平检查、只追加、回滚、行锁、唯一键竞争、投影与账本对账 |
| Architecture | Spring Modulith 依赖方向、循环和内部包访问 | ApplicationModules.verify()；领域不直接依赖 Controller/JPA |
| API integration | 实际 Spring Security、Problem Details、Cookie/CSRF 和资源授权 | 注册、登录、跨账户 404、错误字段、correlation ID |
| Browser E2E | 真实前端和后端 HTTP 流程、可见状态 | 登录、开户、转账、历史、余额不足、同键重放、刷新恢复、窄屏 |
| Performance experiment | 受控数据集中的真实查询计划，不形成生产 SLA | 余额、历史、Posting、对账 SQL 的 EXPLAIN (ANALYZE, BUFFERS) |

## 关键数据库竞争设计

并发用例通过两个并行服务调用形成两条独立 PostgreSQL 事务，并在调用前用 CountDownLatch 屏障同时释放，而不是 Thread.sleep。每个用例检查输入意图与最终 SQL 状态。断言 Transfer、JournalEntry、Posting、projection 和 IdempotencyRecord 的数量及金额守恒。P3 覆盖不同键同时争用同一余额、两个账户交叉转账、同键同指纹、同键异指纹，以及响应丢失后以原键恢复服务层回执。

## 测试数据与副作用

集成测试使用专用本地 PostgreSQL 数据库和随机测试 ID/邮箱；明确检查连接目标，绝不清理或重建用户业务库。当前本地测试以 EDB PostgreSQL 18.7 隔离库运行；P5 main-only GitHub Actions 已配置 PostgreSQL 18 服务，但远程结果须在 P5 提交后确认。P4 API 集成启动真实 Spring Boot HTTP 服务器，Playwright 通过 Vite 代理使用真实 HTTP，不导入生产模块内部类型。性能只使用公开的合成数据并保留版本、行数、索引、查询计划和限制。

## 阶段验收规则

每一条结果都关联准确 Git SHA、命令、退出码与测试报告。缺失数据库/浏览器时标为 NOT_RUN 或 BLOCKED，不静默跳过，不将单元测试说成事务证明。P1–P4 使用本地 PostgreSQL 18.7 而非 Testcontainers：本机 Docker Engine 能返回版本，但启动独立数据库容器及后续控制命令超时；独立本地 PostgreSQL 实例可重复启动。测试固定连接到隔离数据库 `ledgercore_test`，不使用 H2 或纯 Mock。

P4 后端完整 `clean verify` 退出码为 0，共 41 tests、0 failures、0 errors、0 skipped；其中 `FullStackApiPostgresIntegrationTests` 验证真实注册、CSRF/会话、账户、转账、幂等重放、历史、对账、所有权和登出，`TransferPostgresIntegrationTests` 15 项与 `ModuleArchitectureTests` 均通过。前端 `npm run build` 与 `npm run lint` 通过；`npm run test:e2e` 在 Chromium 为 1 passed。E2E 真正提交并丢弃首个转账 HTTP 响应，刷新后用原幂等键恢复同一回执。完整测试报告和 Playwright trace 保留在 Git 忽略的 `work/`。

P5 的本地安全审查（见 [P5 Security Review](../security/P5-SECURITY-REVIEW.md)）、负向请求回归与 PostgreSQL 查询计划/索引对比已完成。临时空 PostgreSQL 18.7 集群上的 42 项 Maven 回归、打包应用健康检查、Flyway 验证、OpenAPI 类型无漂移和 Chromium E2E 1 passed 均通过。`npm audit` 报告 0 high/critical、4 moderate、2 low；OSV-Scanner 2.7.1 的 Maven 本地扫描解析 187 个依赖包且无已知问题。最终提交对应的 OSV/GitHub Actions 远程 job 仍待执行。性能没有生产容量结论。
