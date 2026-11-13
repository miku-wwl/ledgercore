# 教材来源与范围

- 应用源码唯一基线：`484b9ad1c70ba052849ee1a40e15e35b6d888c12`。
- 代码节选由 `manual/tools/extract_source.py` 从该提交读取；元数据清单在 `manual/reference/code-excerpts.json`。行号以固定 Git 对象为准，并生成 GitHub 永久链接。
- P5 CI：<https://github.com/miku-wwl/ledgercore/actions/runs/43377985041>，verify 与 dependency-scan 均通过。
- P5 性能实验、数据库配置、测试计数及安全边界，分别由 `backend/docs/performance/P5-PERFORMANCE-EXPERIMENT.md`、`backend/docs/testing/TEST-STRATEGY.md`、`backend/docs/security/P5-SECURITY-REVIEW.md` 和固定源码支持。
- 当前系统使用模拟 NZD；没有真实银行、外部支付或生产合规部署。
- 界面照片/截图来自本地 Playwright 浏览器和真实 LedgerCore 应用会话。未使用生成图伪装 UI。

## 证据级别

“源码可见”说明在固定提交中可读到实现；“测试通过”说明所列测试在记载环境执行；“截图观察”说明界面在本地运行中出现；“实验结果”只适用于所述合成数据与机器。它们不应互相替代。
