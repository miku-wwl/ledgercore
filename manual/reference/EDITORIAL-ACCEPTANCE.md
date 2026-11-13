# 编辑验收记录

- 验收状态：通过
- 验收日期：2026-11-13
- 版次：第 1 版 · 2026
- 应用源码基线：484b9ad1c70ba052849ee1a40e15e35b6d888c12
- 交付 PDF：manual/dist/LedgerCore-Enterprise-Java-Fullstack-Engineering.pdf
- PDF SHA-256：baf7ff63b8e736139d5962d599e2a9a6e75594da693e32240e57cb29a2e97989

## 验收结论

本书围绕“一笔转账如何从用户意图变成可核对的账本事实”展开，从金额和复式记账逐步进入领域不变量、Spring 请求边界、PostgreSQL 事务与并发、幂等恢复，再回到 React 状态与用户体验。正文按 manual/book.json 的显式清单生成，共 20 章；源码、图、截图和运行证据都限定在固定应用提交及其记录的环境中。

| 检查项 | 结果与证据 |
|---|---|
| 源码版本与课程清单 | 通过。固定应用基线为 484b9ad1c70ba052849ee1a40e15e35b6d888c12；PDF 书签中有 20 个章节标题，正文清单未扫描其他文档目录。 |
| 教学叙事 | 通过。章节沿同一转账旅程递进，并以实际反例、适用边界和复习问题帮助读者推理；正文不是运行日志或命令清单。 |
| 机制图 | 通过。22 幅原创、可编辑 SVG，由 manual/tools/make_figures.py 生成并进入 PDF；图有编号、标题和工程语义。 |
| 真实 UI | 通过。12 张截图来自固定版本的本地打包 JAR、Vite 页面、一次性 PostgreSQL 18.7 数据库和 Playwright Chromium 1.65。capture-manifest.json 记录了场景和环境；未知结果截图在服务端 HTTP 200 后由代理丢弃浏览器响应。 |
| 源码摘录 | 通过。9 段代码由 manual/tools/extract_source.py 通过 git show 从固定提交提取，带精确路径、行号与 GitHub 永久链接；摘录覆盖金额、凭证、HTTP 入口、数据库约束、转账事务、排序锁和历史查询。 |
| PDF 结构 | 通过。目录、章节书签、页码和可点击参考链接存在；中文 Microsoft YaHei 与 Consolas 代码字体嵌入。完整机器检查值见下节。 |
| 页面与版面 | 通过。全部 PDF 页面均渲染为图片并查看缩略接触图；另外放大复核封面、目录、数据库触发器与转账代码、幂等恢复、登录及 Dashboard 截图、锁顺序和查询章节。未发现裁切、重叠或空白页；针对稀疏页面补过解释文字后重新构建。 |
| 构建来源 | 通过。PDF 可由 manual/book.json、20 章 Markdown、SVG、截图、固定源码摘录与 manual/tools/build_book.py 重建。重建环境需提供 ReportLab、pypdf、pdfplumber、Pillow、Node/Playwright、Poppler，以及 Windows Microsoft YaHei 和 Consolas 字体。 |

## PDF 自动检查

最终构建为 46 页 A4。自动检查确认：

- 20 个章节书签、27 个总书签、97 个 PDF 注释，其中 43 个外部链接。
- 12,389 个可搜索中文汉字，提取文本 35,150 字符。
- 46 页全部渲染；没有空文本页，没有超出页面边界的字符。
- PDF 文件大小 2,895,064 字节；最终 SHA-256 见本记录顶部。

完整机器结果保存在本地忽略目录 work/pdf-qa-results.json；PDF 全页渲染和四张接触图保存在 work/，不将临时证据打包进教材。

## 重建与复核

Python 3.11+ 的构建环境先安装 manual/tools/requirements-pdf.txt 中固定的依赖。然后在仓库根目录依次执行：

1. python manual/tools/extract_source.py
2. python manual/tools/make_figures.py
3. node manual/tools/render_svgs.mjs
4. python manual/tools/build_book.py
5. 使用 Poppler 将最终 PDF 每页渲染到 work/book-pdf-render-final6/，例如 pdftoppm -png -r 120 manual/dist/LedgerCore-Enterprise-Java-Fullstack-Engineering.pdf work/book-pdf-render-final6/page
6. python manual/tools/qa_pdf.py
7. python manual/tools/make_final_pdf_contacts.py

PDF 构建器从显式章节清单生成正文。第 5 步的渲染目录由 qa_pdf.py 检查页数；接触图生成器按 12 页一张汇总页面，供逐页人工复核。

## 证据边界与已知限制

- 应用只处理教学用途的模拟 NZD，不连接真实账户、银行或支付网络；本书不代表监管认证或生产部署指南。
- P5 全量验证在 PostgreSQL 18.7 与 Chromium 上通过，42 项 Maven 测试、前端 lint/build、OpenAPI 类型无漂移与 E2E 均通过；固定源码提交的 GitHub Actions verify 和 dependency-scan 运行 43377985041 均通过。
- 合成查询计划实验不代表生产容量、吞吐量或服务时限；未做外部渗透测试或真实负载测试。
- 前端依赖审计为 0 high、0 critical、4 moderate、2 low；这些低于既定 high/critical CI 阈值，但仍应在后续依赖升级中复查。
- UI 截图只证明清单中的本地浏览器路径；网络丢包实验不证明所有设备、网络和浏览器存储策略。

## 出版决定

满足本书的出版验收条件。此记录与 PDF 一同保留，说明书稿来源、视觉检查、可搜索性、导航结构、源码可追溯性和证据边界。
