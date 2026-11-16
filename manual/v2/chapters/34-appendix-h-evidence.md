# 附录 H　证据与来源索引 {#app-evidence}

本书的每一个数字与结论都可以追溯到仓库中的一个文件。路径相对于仓库根目录；`work/` 下的原始运行目录被 Git 忽略，只保存在执行实验的工作站上，仓库中保存的是它们的摘要与评分。

| 章 | 主张 | 证据 |
|---|---|---|
| 1 | 四轮工程的范围与结论 | `docs/v2/STATUS.md`、`docs/v2/ROUND-1-HANDOFF.md`、`ROUND-2-HANDOFF.md`、`ROUND-3-HANDOFF.md`、`ROUND-4-HANDOFF.md` |
| 1, 16–20 | 第四轮七个发现与处置 | `docs/v2/ROUND-4-FINDINGS.md`、`docs/v2/ROUND-4-AUDIT.md` |
| 3 | 19 条不变量与验证 | `docs/v2/DOMAIN-INVARIANTS.md` |
| 4 | 第四轮基线（95 + 6 个测试） | `evidence/round4/baseline/BASELINE.md` |
| 4 | 预演运行与两个脚本缺陷 | `evidence/round4/e12/shakedown/README.md` |
| 4, 26 | 被作废的第一次官方评估 | `evidence/round4/e12/attempt-1-6119274/README.md` |
| 5 | 准备金实验的余额与恒等式 | `evidence/round4/remediation/clearing-reserve-shortage-postfix.json` |
| 7 | 并发转账（H02） | `evidence/e3/verdicts/H02-financial-concurrency.md`、`evidence/e3/measurements/` |
| 7, 22 | 投影读取（R2-P2） | `evidence/round3/e9/R2-P2-PROJECTION-RESULTS.md` |
| 8 | 转账崩溃窗口（第三轮） | `evidence/round3/e6/LOCAL-VALIDATION.md`、`CASE-INVENTORY.md` |
| 9 | E8 授权矩阵（528 格、243 个无副作用拒绝） | `backend/src/test/java/nz/ac/ledgercore/security/AuthorizationMatrixPostgresIntegrationTests.java`；E12 verify 运行目录中的 `e8-authorization-matrix.json` |
| 11 | 500 项工资进程崩溃（E2） | `evidence/e2/VALIDATION.md` |
| 11, 22 | 工资性能与计划（H03、H04） | `evidence/e3/verdicts/H03-payroll-and-query-plans.md`、`H04-cross-organization-payroll-progress.md` |
| 12 | 持久事件（E4） | `evidence/round3/e4/LOCAL-VALIDATION.md` |
| 13 | 两进程清算（E5） | `evidence/round3/e5/LOCAL-VALIDATION.md` |
| 16 | R4-01 修复前复现与红测试 | `evidence/round4/reproductions/`（`R4-01-prefix-matrix.json`、`R4-01-C-prefix-session-kill.json`、`red-green/`） |
| 16 | R4-01 修复后矩阵与 A* | `evidence/round4/remediation/R4-01-postfix-matrix.json`、`R4-01-A-star-postfix.json` |
| 17 | R4-02 不可达性证明与修复 | `docs/v2/ROUND-4-FINDINGS.md`；`evidence/round4/remediation/clearing-reserve-shortage-postfix.json` |
| 18 | R4-04 复现与修复 | `evidence/round4/reproductions/R4-04-prefix-lost-callback.json`、`evidence/round4/remediation/clearing-lost-callback-postfix.json` |
| 20 | 对账（第三轮 E7） | `evidence/round3/e7/LOCAL-VALIDATION.md` |
| 21 | 第三轮指标与告警规则 | `evidence/round3/e9/LOCAL-VALIDATION.md`、`prometheus-alerts.yml` |
| 21 | 第四轮告警实验与追踪链 | `evidence/round4/e5-e9/E9-ALERT-LAB.json`、`E9-TRACES.json` |
| 22 | 被否决的索引（R2-P3） | `evidence/round3/e9/R2-P3-CANDIDATE-RESULTS.md` |
| 23 | PITR（E10） | `evidence/round3/e10/LOCAL-VALIDATION.md`、`raw/` |
| 23 | 逻辑备份与跨系统恢复（E12-G02） | `evidence/round4/e12/lab/E12-RESTORE.json` |
| 24 | 真实截图的采集记录 | `manual/assets/screenshots/capture-manifest.json`（V1）、`evidence/round3/e11/LOCAL-VALIDATION.md`（E11）、`manual/v2/figures/screenshots/capture-manifest.json`（本书） |
| 25 | 精确提交的 CI 运行 | `docs/v2/E12-SOURCE-FREEZE.md` |
| 26 | E12 用例、运行与判定 | `assessment/e12/cases.json`；`evidence/round4/e12/E12-RESULTS.json`、`runs/`、`lab/E12-LAB.json`；`docs/v2/E12-ACCEPTANCE.md` |

Table: 各章主张与证据文件 {#tbl-evidence}

## 图的数据来源

标注为“实测数据”的图在生成时直接读取以下文件；文件缺失时生成脚本失败，而不是使用旧数据：

| 图 | 数据文件 |
|---|---|
| 崩溃窗口在修复前后与 E12 中的恢复判定（第 16 章） | `evidence/round4/reproductions/R4-01-prefix-matrix.json`、`R4-01-C-prefix-session-kill.json`、`evidence/round4/remediation/R4-01-postfix-matrix.json`、`evidence/round4/e12/lab/E12-LAB.json` |
| 告警实验时间线（第 21 章） | `evidence/round4/e5-e9/E9-ALERT-LAB.json` |
| 结算追踪链（第 21 章） | `evidence/round4/e5-e9/E9-TRACES.json` |
| E3 历史增长（第 22 章） | `evidence/e3/verdicts/H01-ledger-balance-history.md` 中的表格（数值写入生成脚本，并与该表逐一核对） |
| 恢复时间线（第 23 章） | `evidence/round4/e12/lab/E12-RESTORE.json` |
| E12 各维度结果（第 26 章） | `evidence/round4/e12/E12-RESULTS.json` |

Table: 实测数据图的来源 {#tbl-figure-data}
