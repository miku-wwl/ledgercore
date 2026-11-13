# LedgerCore P0 来源与主张地图

本文是维护材料，不进入未来教材正文。SOURCE-ANALYSED 表示已检查来源，不表示 LedgerCore 行为已运行。ILLUSTRATIVE 表示本阶段设计方案，不是代码摘录。VERIFIED 仅用于本轮实际观察到的仓库状态或文件摘要。

| 目标章节/文件 | 重要主张 | 来源锚点 | 分类 | 限制 |
|---|---|---|---|---|
| README / PRD | 产品只处理 NZD 模拟资金；不接真实账户或外部结算 | 用户提供的 Master Prompt P0.2 与第 7 节 | ILLUSTRATIVE（本项目需求决策） | 需求文件尚未由运行应用验证 |
| DOMAIN-MODEL | 客户存款负债为贷方正常余额；开户和转账凭证均借贷平衡 | 本项目的会计设计及 INV-01；Bank of Anthos 仅作对照 | ILLUSTRATIVE | 金额演算说明模型，不代表已存在 LedgerCore 数据或 API |
| LEDGER-INVARIANTS / SCHEMA | 事务锁、跨行延迟账平检查和回滚 | PostgreSQL 18 Transaction Isolation、Explicit Locking、CREATE TRIGGER 官方文档 | SOURCE-ANALYSED | 文档说明 PostgreSQL 机制；LedgerCore 尚无实际 SQL/竞争验证 |
| ARCHITECTURE / ADR | Java 21 可用于 Spring Boot 4；按模块验证 DAG 和模块 API 边界 | [Spring Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)；[Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html) | SOURCE-ANALYSED | 当前稳定依赖的精确组合、编译、运行及测试须在 P1 验证 |
| API-CONTRACT | springdoc 可生成 OpenAPI；Boot 4.0.x 使用 springdoc 3.0.x 兼容线 | [springdoc compatibility and setup](https://springdoc.org/) | SOURCE-ANALYSED | 计划在 P1 从真实 Spring API 生成合同；目前是草案 |
| BANK-OF-ANTHOS-REVIEW | 五模块责任、流水模型、缓存/轮询取舍及单体版本 | 上游提交 db35fea9fd090150e2398106aadb475576f80d94 的 README、Java/SQL 源码、LICENSE | SOURCE-ANALYSED | 只检查固定源码快照；没有运行上游系统；未复制代码 |
| ECHOCASE-REFERENCE-REVIEW | EchoCase 工程、测试隔离和教材来源经验 | 本机 EchoCase HEAD 9f68721e18f15ae603e6502fa46b8fa78f9df55c；当时有脏工作树；历史和模板修订清单分别固定不同来源 | SOURCE-ANALYSED | EchoCase 是只读对照；其历史技术栈、运行输出和未提交出版工作不证明 LedgerCore 行为 |
| IMPLEMENTATION-STATUS | 起始 main 提交、分支和缺少工具 | 本轮 git status/fetch/branch/log；本机工具发现；JDK 压缩包 SHA-256 校验 | VERIFIED（只限观察时点） | 后续状态随环境和提交变化；不能外推到未来会话 |
| 全部 LedgerCore 实现 | 真实应用行为、数据库结果、API、浏览器 UI 或性能 | 当前仓库无后端、前端、迁移、测试或 CI 源码 | NOT_RUN | P1–P5 必须建立可复现证据后才可升级状态 |

## 外部版本参考

- Spring Boot 4.0 文档目前列出 4.0.9，要求 Java 17 或以上以及 Maven 3.6.3 或以上；项目选择 Java 21 满足该文档的 Java 版本下限。
- Spring Modulith 的验证 API 能检查模块依赖无循环及模块间访问限制；这说明为什么选 Modulith，不证明任何 LedgerCore 模块已经通过验证。
- springdoc 项目说明其 3.x 支持 Spring Boot 4，Boot 4.0.x 使用 3.0.x 兼容版本线。
- PostgreSQL 18 文档用于解释 READ COMMITTED 每条命令快照、行级锁等待语义和可延迟 constraint trigger。应用行为仍须本地数据库实验确认。
