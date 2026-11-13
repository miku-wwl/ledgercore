# LedgerCore 教材源稿与重建

本目录保存《企业级 Java 全栈与金融交易一致性工程》的源稿、可编辑图、真实界面截图、代码来源映射、构建脚本和已出版 PDF。

## 固定基线

`book.json` 固定版次、章节清单和应用源码提交 `484b9ad1c70ba052849ee1a40e15e35b6d888c12`。代码摘录工具只用 `git show <commit>:<path>` 读取该 Git 对象，并为每段生成行号与永久链接。后续教材提交不会改变本书解释的应用版本。

## 重建 PDF

Python 3.11+ 环境先执行 `python -m pip install -r manual/tools/requirements-pdf.txt`；版本清单固定 ReportLab、pypdf、pdfplumber 与 Pillow。Node 环境需能解析 `frontend/node_modules/playwright`，并安装 Chromium。PDF 使用 Microsoft YaHei 字体文件嵌入中文。在仓库根目录、Windows PowerShell 执行：

```powershell
python manual/tools/extract_source.py
python manual/tools/make_figures.py
node manual/tools/render_svgs.mjs
python manual/tools/build_book.py
````build_book.py` 从 `book.json` 顺序读取 20 份 Markdown 章节，载入代码节选、机制图和实际截图，生成目录、可点击书签、页码和来源链接。

界面截图重抓脚本见 `manual/tools/capture_screenshots.mjs`。它要求本地 LedgerCore 在 8080、Vite 在 5173，连接一次性 PostgreSQL 数据库；通过真实浏览器操作保存实际 UI。响应丢失场景会先确认服务端返回成功，再丢弃返回浏览器的响应。截图清单和运行环境记录在 `manual/assets/screenshots/capture-manifest.json`。

最终 PDF：`manual/dist/LedgerCore-Enterprise-Java-Fullstack-Engineering.pdf`。独立编辑验收和证据边界见 `reference/EDITORIAL-ACCEPTANCE.md`。
