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

集成测试使用专用本地 PostgreSQL 数据库和随机测试 ID/邮箱；明确检查连接目标，绝不清理或重建用户业务库。当前测试以 EDB PostgreSQL 18.7 本地隔离库运行；CI PostgreSQL 服务仍待 P5。E2E 经真实 HTTP 和浏览器访问应用，不导入生产模块内部类型。性能只使用公开的合成数据并保留版本、行数、索引、参数和计划。

## 阶段验收规则

每一条结果都关联准确 Git SHA、命令、退出码与测试报告。缺失数据库/浏览器时标为 NOT_RUN 或 BLOCKED，不静默跳过，不将单元测试说成事务证明。P1–P3 使用本地 PostgreSQL 18.7 而非 Testcontainers：本机 Docker Engine 能返回版本，但启动独立数据库容器及后续控制命令超时；独立本地 PostgreSQL 实例可重复启动。测试固定连接到隔离数据库 `ledgercore_test`，不使用 H2 或纯 Mock。P1–P3 当前完整 `clean verify` 为 40 tests、0 failures、0 errors、0 skipped，其中 P3 15 项真实 PostgreSQL 转账/幂等测试通过。P4 需要真实 Playwright 流程；P5 要求最终提交 CI 和干净环境验收。性能没有生产容量结论。
