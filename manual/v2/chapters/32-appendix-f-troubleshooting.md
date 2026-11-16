# 附录 F　故障排查 {#app-troubleshooting}

本附录收录两类条目：在构建与验证 LedgerCore 时真实遇到过的问题，以及各类守卫会**有意**给出的拒绝（遇到它们说明守卫在工作）。每一条都写明现象、原因与处理方法。

| 现象 | 原因 | 处理 |
|---|---|---|
| 后端测试一启动就失败，提示数据源 URL 缺失 | `application-test.properties` 只从 `LEDGERCORE_TEST_DB_URL` 读取 URL，没有默认值 | 设置环境变量指向一次性库；这是有意的快速失败 |
| 启动时 Flyway 报告某个迁移的校验和不一致 | 已发布的迁移被就地修改过 | 撤销对该文件的修改；修正写成新的版本号。不要执行 `flyway repair` |
| V15 迁移失败：“V15 preflight refused to migrate” | 库里存在终态与分录矛盾的付款 | 调查并以审计过的方式处理这些行，再重试迁移；不要绕过预检 |
| PowerShell 中 `pg_ctl -w start` 一直不返回 | 子进程继承了输出句柄 | 用 `Start-Process` 启动并把日志写入文件 |
| 在一个事务中反复查询 `pg_stat_activity`，结果从不变化 | 统计视图在事务内被缓存 | 每次查询前执行 `SELECT pg_stat_clear_snapshot();` |
| 前端构建报类型错误：某个字段不存在 | `schema.d.ts` 落后于后端 OpenAPI | 启动后端，执行 `npm run generate:api`，提交生成的文件 |
| CI 在“Verify generated OpenAPI TypeScript contract”失败 | 后端改动未同步到类型，或类型被手工编辑 | 同上；不要手工编辑生成的文件 |
| E12 全栈运行没有 Playwright 报告 | PowerShell 把未加引号的 `--reporter=list,json` 拆成两个参数 | 参数加引号：`'--reporter=list,json'` |
| 故障实验的每个用例都在一分钟内 `HARNESS_ERROR` | 用数组展开向脚本传参，整个数组被绑定到第一个参数 | 使用哈希表展开：`& $script @params` |
| 故障实验判定 `NOT_INJECTED` | 检查点未到达，或被杀的进程不是被测进程 | 检查 `run.json` 中的配置、端口与 PID；修正后重新运行，绝不改判为 PASS |
| 实验银行启动失败：“E6 case and fault point are not an allowed pair” | 用例名与故障点不是白名单组合 | 只使用实验脚本支持的用例 |
| 准备金实验命令启动失败：缺少确认令牌或库名不符 | 守卫生效：命令只在一次性库上运行 | 设置 `LEDGERCORE_RESERVE_LAB_CONFIRM=DISPOSABLE_ONLY`，并使用实验集群上的 `ledgercore_{e6,e12,r4}_*` 库 |
| 准备金实验命令以 `--spring.main.web-application-type=none` 启动时报缺少 `AuthenticationConfiguration` | 安全配置依赖 Web 环境 | 以 `--server.address=127.0.0.1 --server.port=0` 启动（第四轮提交 `d185416`） |
| 修复后 A 用例“注入后”评分为 FAIL，但恢复 PASS | 快速路径冻结期间，持久工作项已经完成结算 | 使用 `-OutboxPollMs 60000` 运行 A*；资金结果正确，不要放宽评分 |
| 例外关闭返回 409 | 复核发现问题仍然存在 | 先修复原因（补足准备金、询价、重试），再关闭 |
| 告警实验中 Prometheus 抓取返回 401/403 | `/actuator/prometheus` 只对 OPERATIONS 开放 | 用 OPERATIONS 成员的会话 Cookie 抓取（脚本自动处理，Cookie 只写在被忽略的运行目录） |
| 书籍构建报“unresolved cross-references” | 正文引用了不存在的图、表或代码编号 | 检查 `@fig-…`、`@tbl-…`、`@code-…` 的拼写与定义位置 |
| 书籍构建报“start … not found” | 代码摘录的起始正则在冻结提交的文件中找不到 | 用 `git show <冻结提交>:<路径>` 核对源码，修正正则 |

Table: 真实遇到过的问题、原因与处理 {#tbl-troubleshooting}
