# EchoCase 工程与教材参考审查

## 读取基线与不确定性

2026-11-13 从本机只读检查 D:/workshop/nov/echocase。检出分支为 main，HEAD 为 9f68721e18f15ae603e6502fa46b8fa78f9df55c。检查当时 README、manual/README、出版工具和若干章源有本地未提交修改，manual/editions/template-first/ 与新版 PDF 还有未跟踪内容。因此本文既记录固定 HEAD，也注明哪些当前工作树材料尚非该提交的一部分；这些文件没有被修改。

## 版本必须分开

EchoCase 至少存在三个不能混称的教材/实现基线：

| 材料 | 记录的源码/素材提交 | 可支持的结论 |
|---|---|---|
| 历史正式 107 页 PDF | 源码 908811b95fb40cf14fa79b8eddf41796aa02cc04；真实 UI/数据库材料来自 63c587277541b4e700258aad82de9b2ad0ba32f8 | 解释当时的自行实现与记录的历史运行；不能描述为当前官方模板基线 |
| 当前 main 工程提交 | 9f68721e18f15ae603e6502fa46b8fa78f9df55c | README 与架构文档称其从官方 Clean.Architecture.Solution.Template 10.8.0 生成并迁移；须以模板来源清单和工程验收为凭 |
| Template-First 修订版教材 | 工作树的独立清单把源提交写为 9f68721e18f15ae603e6502fa46b8fa78f9df55c；本机 PDF 与章节在检查时未跟踪 | 是把教材叙事切换到模板迁移路径的独立编辑版本；不能将未提交 PDF/清单当作已由当前 HEAD 正式出版 |

manual/reference/TEMPLATE-MIGRATION-IMPACT.md 明确要求保留旧书历史、按新源重新核对摘录和图，不得把旧代码说明改写成“从一开始就用模板”。EDITORIAL-ACCEPTANCE.md 只证明其明确列出的旧书 PDF、历史 UI 和逐页审查范围；其中的旧来源与测量限制不可自动移植给新修订版。

## 值得迁移到 LedgerCore 的工程做法

- 工程实现和读者向教材分开：EchoCase 先固定应用提交，源码摘录通过 Git 对象和行锚点提取，出版不得悄悄读取漂移后的工作树。
- 依据已验收需求控制边界：当前 PLAN 将部署、云交付和广义 DevOps 移出 DoD；测试分别验证领域规则、真实 PostgreSQL/API 和真实浏览器。
- 集成测试使用独立 PostgreSQL 测试目标和随机隔离数据库；缺少连接配置即明确失败，不把 Mock 说成持久化证明。
- 前端区分服务端已确认快照、待提交意图、冲突和未知结果；取消请求不能抹掉已提交但响应丢失的写入意图。
- 教材工具保留章源、SVG、真实素材及固定提交；机器检查、完整 PDF 渲染、编号联系图和高分辨率人工复核分别承担不同检查责任。
- 教材只展示解释机制所需的代码和观察；原始日志、测试清单、脚本和历史实验报告留在书外。

## 不照搬的内容

EchoCase 的业务模型是案件、证据和关联修订，不是客户存款、复式账本或资金转移。它的后端是 .NET 10、Clean Architecture 模板、MediatR、EF Core 和 NSwag；这些是参考项目的真实来源，不是 LedgerCore 的技术路线。LedgerCore 使用 Java/Spring、Spring Modulith、JPA/Flyway 和自己的 OpenAPI 工具链，需按兼容版本重新验证。也不复制 EchoCase 的实体、迁移、测试 fixtures、截图、章节文字或图稿。

EchoCase 当前 PDF 中的 .NET 请求管线、EF 变更跟踪及运行结果都不能作为 LedgerCore 实现行为的证据。只有工程实际实现了同一类通用机制后，才可引用其概念并重新用 LedgerCore 源码和实验说明。

## 本次只读范围

已读取 EchoCase README、backend/docs/PLAN.md、architecture/ARCHITECTURE.md、engineering/DEVELOPMENT.md、engineering/TESTING.md、TEMPLATE-MIGRATION-ACCEPTANCE.md、manual/book.json、manual/README.md、EDITORIAL-ACCEPTANCE.md、TEMPLATE-MIGRATION-IMPACT.md，以及 React package/source 索引、测试结构、模板修订版若干章节与出版脚本。工程验收报告和出版报告是该仓库对各自范围的陈述；本次没有在 EchoCase 重跑应用测试、发布脚本或修改任何文件。
