# 附录 A　环境搭建、运行与清理 {#app-setup}

本附录给出在一台 Windows 工作站上重建本书全部验证所需的步骤。所有命令在仓库根目录的 PowerShell 中执行。**任何一步都不应连接本机上持久的 PostgreSQL 服务（55432 端口）或任何业务数据库。**

## 工具链

| 组件 | 版本 | 位置或获取方式 |
|---|---|---|
| JDK | Temurin 21.0.13+9 | `work/toolchain/jdk21/`（Git 忽略） |
| PostgreSQL | 18.7 二进制发行包 | `work/postgresql-binaries/pgsql/`（Git 忽略） |
| Maven | 项目自带 wrapper | `backend/mvnw.cmd`；依赖缓存 `work/m2` |
| Node.js / npm | Node 22（CI）；本地使用当时已安装的版本 | `frontend/`：`npm ci` |
| Playwright Chromium | 随 `@playwright/test` 安装 | `npx playwright install chromium` |
| Prometheus | 3.13.3（含 promtool） | `work/e9-lab/tools/`（仅告警实验） |
| 书籍构建 | pandoc 3.8、Microsoft Edge、Python + playwright + pypdf | 见本附录末尾 |

Table: 工具链（版本以 `docs/v2/REPRODUCE.md` 与 E12 运行记录为准） {#tbl-toolchain}

环境变量（每个 PowerShell 会话设置一次）：

```powershell
$repo = (Get-Location).Path
$env:JAVA_HOME = "$repo\work\toolchain\jdk21\jdk-21.0.13+9"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_USER_HOME = "$repo\work\maven-home"
$env:MAVEN_OPTS = "-Dmaven.repo.local=$repo\work\m2"
```

## 一次性测试集群

```powershell
$pg = "$repo\work\postgresql-binaries\pgsql\bin"
& "$pg\initdb.exe" -D work\pg-book -U postgres --auth-local=trust --auth-host=trust --no-locale -E UTF8
Start-Process "$pg\pg_ctl.exe" -ArgumentList '-D work\pg-book -l work\pg-book.log -o "-h 127.0.0.1 -p 55470" -w start' `
    -WindowStyle Hidden -Wait
& "$pg\psql.exe" -h 127.0.0.1 -p 55470 -U postgres -c "CREATE ROLE ledgercore_book LOGIN"
& "$pg\psql.exe" -h 127.0.0.1 -p 55470 -U postgres -c "CREATE DATABASE ledgercore_book_verify OWNER ledgercore_book"
```

在 PowerShell 里直接调用 `pg_ctl -w start` 可能因为子进程继承了输出句柄而一直不返回；用 `Start-Process` 并把日志写到文件可以避免这个问题（附录 F）。

## 后端、模拟器与前端

```powershell
$env:LEDGERCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55470/ledgercore_book_verify'
$env:LEDGERCORE_TEST_DB_USERNAME = 'ledgercore_book'
$env:LEDGERCORE_TEST_DB_PASSWORD = ''
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend\pom.xml clean verify
# 模拟器使用自己的测试库
$env:CLEARING_SIMULATOR_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55470/ledgercore_book_simverify'
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f clearing-simulator\pom.xml clean verify
Set-Location frontend; npm ci; npm run lint; npm run build; Set-Location ..
```

后端测试的数据源只来自 `LEDGERCORE_TEST_DB_URL`；没有设置时测试**快速失败**，而不是去连接某个默认的本地库。

## E12 独立评估

完整的命令、端口与判定规则在 `assessment/e12/README.md`。概括如下：

```powershell
$verify = [guid]::NewGuid().ToString()
.\assessment\e12\run-e12.ps1 -Mode verify -RunId $verify -Label 'E12 verify'
$fullstack = [guid]::NewGuid().ToString()
.\assessment\e12\run-e12.ps1 -Mode fullstack -RunId $fullstack -Label 'E12 full stack'
.\assessment\e12\run-e12-lab.ps1 -Label 'E12 lab'          # 约 45 分钟，破坏性，只用一次性集群
.\assessment\e12\run-e12-restore.ps1                       # 逻辑备份与跨系统恢复
.\assessment\e9\alert-lab.ps1                              # 真实 Prometheus 告警
python assessment/e9/extract-traces.py work/e6-lab/runs/<告警实验运行号> evidence/round4/e5-e9/E9-TRACES.json <对照付款ID>
.\assessment\e12\run-e12.ps1 -Mode collect -Include $verify, $fullstack
```

运行前确认工作区没有未提交的跟踪文件改动（`git status --porcelain --untracked-files=no` 为空），否则每条运行记录都会标注 `trackedTreeDirty: true`，不能作为验收证据。

## 清理

- **进程**：所有实验脚本只停止自己启动并记录的 PID；E12 运行器在每个用例之后检查 55437、18095、18094 端口已释放。若脚本被强行中断，先核对端口上的进程命令行与运行目录的 `run.json`，再停止它。
- **集群**：只停止核对过路径的集群：`pg_ctl.exe -D work\e12-lab\pg -m fast -w stop`。
- **运行目录**：`work/e6-lab/runs/<运行号>/`、`work/e12-lab/runs/<运行号>/` 是被 Git 忽略的原始证据。只在提交的摘要已经保留之后，按**精确的**运行号删除；不要基于一个计算出来的路径递归删除。
- **数据库**：一次性库可以随集群一起删除；永远不要在持久服务上执行 `DROP DATABASE`。

## 构建本书

```powershell
python manual/v2/tools/make_diagrams.py        # 生成 manual/v2/diagrams/*.svg（数据图读取 evidence/ 下的评分文件）
python manual/v2/tools/build.py                # 生成 manual/dist/LedgerCore-V2-Enterprise-Banking-Reliability-Engineering.pdf
python manual/v2/tools/qa_pdf.py manual/dist/LedgerCore-V2-Enterprise-Banking-Reliability-Engineering.pdf
```

构建需要 pandoc 3.8、已安装的 Microsoft Edge（Playwright 以 `msedge` 通道打印），以及一个装有 `playwright`、`pypdf`、`pypdfium2`、`Pillow` 的 Python 环境（`manual/v2/tools/requirements.txt`）。代码摘录在构建时用 `git show <冻结提交>:<路径>` 读取，因此构建只依赖已提交的源码，而不依赖工作区。字体使用系统已安装的 Microsoft YaHei 与 Cascadia Mono，由打印机嵌入 PDF；仓库不包含任何字体文件。
