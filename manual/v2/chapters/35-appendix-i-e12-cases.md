# 附录 I　E12 用例目录 {#app-e12-cases}

本附录由 `manual/v2/tools/gen_e12_catalogue.py` 从两份已提交的文件生成：用例定义 `assessment/e12/cases.json`（与冻结提交 `2c25ef4` 完全一致），以及正式评估的汇总结果 `evidence/round4/e12/E12-RESULTS.json`（运行 `2cd9995a`，纳入 2 次运行，源码 `2c25ef4`，工作区干净）。没有任何手工填写的数字。用例标题保留 `cases.json` 的英文原文，便于在仓库中搜索。

“证据”一行按来源类型计数：JUnit 测试（后端与模拟器的 Surefire 报告）、浏览器旅程（Playwright 报告）、实验判定（故障实验、恢复与告警实验写出的判定文件）以及独立 SQL 评分器在每个被纳入的运行数据库上的结果。一个用例只有在它的**每一个**来源都通过时才是 PASS；硬门槛用例失败，整个 E12 就是 BLOCKED（第 26 章）。

## A　账本与内部转账

4 个用例，其中硬门槛 4 个。

::: e12-case
**E12-A01**　硬门槛 · 资金正确性 · **PASS**\
Every committed journal balances; amounts are exact NZD minor units; posted history is append-only\
证据 19 项，全部通过：JUnit 测试 17 · SQL 评分器 2
:::

::: e12-case
**E12-A02**　硬门槛 · 资金正确性 · **PASS**\
Identical idempotency key returns the original receipt; a changed payload under the same key is rejected; a lost response recovers with the original key\
证据 4 项，全部通过：JUnit 测试 4
:::

::: e12-case
**E12-A03**　硬门槛 · 资金正确性 · **PASS**\
Concurrent debits of one hot payer never overdraw; opposing transfers use a deterministic lock order without deadlock\
证据 2 项，全部通过：JUnit 测试 2
:::

::: e12-case
**E12-A04**　硬门槛 · 资金正确性 · **PASS**\
Any failure inside the transfer transaction rolls back journal, postings, projection, receipt and outbox together; crash before commit leaves nothing\
证据 7 项，全部通过：JUnit 测试 5 · 实验判定 2
:::

## B　企业身份与 Policy B

5 个用例，其中硬门槛 3 个。

::: e12-case
**E12-B01**　硬门槛 · 安全与授权 · **PASS**\
Executed role x route x object matrix: 12 principals x 44 routes, concealment 404, missing role 403, denied mutations without side effects\
证据 1 项，全部通过：JUnit 测试 1
:::

::: e12-case
**E12-B02**　硬门槛 · 安全与授权 · **PASS**\
Maker cannot approve their own payment or payroll even when holding both roles; approval binds the immutable digest and version\
证据 3 项，全部通过：JUnit 测试 3
:::

::: e12-case
**E12-B03**　硬门槛 · 安全与授权 · **PASS**\
Generic transfers cannot debit a corporate account; payroll identities cannot be claimed by public transfers\
证据 2 项，全部通过：JUnit 测试 2
:::

::: e12-case
**E12-B04**　非硬门槛 · 安全与授权 · **PASS**\
Role revocation, member suspension and organization suspension remove authority; admin self-grant is refused; actions are audited\
证据 7 项，全部通过：JUnit 测试 7
:::

::: e12-case
**E12-B05**　非硬门槛 · 安全与授权 · **PASS**\
State changes without or with a forged CSRF token are rejected; sessions rotate on authentication\
证据 2 项，全部通过：JUnit 测试 2
:::

## C　工资

4 个用例，其中硬门槛 4 个。

::: e12-case
**E12-C01**　硬门槛 · 资金正确性 · **PASS**\
500 synthetic employees are paid exactly once with deterministic per-item identity and reconciled balances\
证据 3 项，全部通过：JUnit 测试 1 · SQL 评分器 2
:::

::: e12-case
**E12-C02**　硬门槛 · 异步可靠性 · **PASS**\
Competing executors and batches sharing a payer pay each item once and never overdraw\
证据 2 项，全部通过：JUnit 测试 2
:::

::: e12-case
**E12-C03**　硬门槛 · 恢复 · **PASS**\
Worker crash after a failed item rolled back but before its failure was recorded: restart records it once, pays the rest once\
证据 1 项，全部通过：实验判定 1
:::

::: e12-case
**E12-C04**　硬门槛 · 异步可靠性 · **PASS**\
Transient and terminal item failures are durable; results cannot be forged, deleted or completed early\
证据 6 项，全部通过：JUnit 测试 6
:::

## D　持久事件

3 个用例，其中硬门槛 2 个。

::: e12-case
**E12-D01**　硬门槛 · 异步可靠性 · **PASS**\
Business fact and outbox event commit together; concurrent publishers and dispatchers deliver each event once\
证据 4 项，全部通过：JUnit 测试 4
:::

::: e12-case
**E12-D02**　硬门槛 · 恢复 · **PASS**\
Interrupted dispatch: an event persisted before a crash is delivered after restart; expired leases are retried idempotently\
证据 2 项，全部通过：JUnit 测试 1 · 实验判定 1
:::

::: e12-case
**E12-D03**　非硬门槛 · 异步可靠性 · **PASS**\
Retry exhaustion dead-letters visibly; a governed retry is role-gated, audited and idempotent\
证据 1 项，全部通过：JUnit 测试 1
:::

## E　清算

9 个用例，其中硬门槛 9 个。

::: e12-case
**E12-E01**　硬门槛 · 资金正确性 · **PASS**\
Settlement, definitive rejection and accepted/pending outcomes post exactly the journals their persisted outcome justifies\
证据 5 项，全部通过：JUnit 测试 3 · SQL 评分器 2
:::

::: e12-case
**E12-E02**　硬门槛 · 异步可靠性 · **PASS**\
UNKNOWN outcome is resolved by inquiry under the original operation identity and digest; no refund or new identity\
证据 3 项，全部通过：JUnit 测试 2 · 浏览器旅程 1
:::

::: e12-case
**E12-E03**　硬门槛 · 安全与授权 · **PASS**\
Signed callbacks: tampered, missing-header, replayed, reused-id, wrong-digest, colliding, gapped and contradictory callbacks never move money twice\
证据 2 项，全部通过：JUnit 测试 2
:::

::: e12-case
**E12-E04**　硬门槛 · 恢复 · **PASS**\
Crash after the callback commit, inside the uncommitted settlement, or before the acknowledgement: the original identity settles exactly once after restart without simulator redelivery\
证据 3 项，全部通过：实验判定 3
:::

::: e12-case
**E12-E05**　硬门槛 · 恢复 · **PASS**\
A terminated database session during the settlement leaves durable work that completes it once\
证据 2 项，全部通过：JUnit 测试 1 · 实验判定 1
:::

::: e12-case
**E12-E06**　硬门槛 · 恢复 · **PASS**\
Genuinely insufficient reserve: obligation kept, CRITICAL exception, restart, audited replenishment, one governed retry, exactly one settlement\
证据 3 项，全部通过：JUnit 测试 2 · 实验判定 1
:::

::: e12-case
**E12-E07**　硬门槛 · 恢复 · **PASS**\
Callbacks lost beyond the simulator\'s delivery budget are detected and redelivered in order after an authenticated inquiry\
证据 3 项，全部通过：JUnit 测试 2 · 实验判定 1
:::

::: e12-case
**E12-E08**　硬门槛 · 异步可靠性 · **PASS**\
Competing workers, a duplicate callback and a direct retry produce exactly one settlement\
证据 1 项，全部通过：JUnit 测试 1
:::

::: e12-case
**E12-E09**　硬门槛 · 恢复 · **PASS**\
Simulator crash with an ACCEPTED operation: the bank\'s inquiry fails and is reported, nothing is assumed; after restart the same operation settles once\
证据 1 项，全部通过：实验判定 1
:::

## F　对账

5 个用例，其中硬门槛 3 个。

::: e12-case
**E12-F01**　非硬门槛 · 资金正确性 · **PASS**\
Projection drift is detected exactly and repaired from immutable postings only\
证据 1 项，全部通过：JUnit 测试 1
:::

::: e12-case
**E12-F02**　硬门槛 · 资金正确性 · **PASS**\
A terminal external outcome without its local journal is detected while the payment is not COMPLETED, also after restart and without a new callback\
证据 2 项，全部通过：JUnit 测试 2
:::

::: e12-case
**E12-F03**　硬门槛 · 资金正确性 · **PASS**\
Negative fixtures: corrupt payment, journal, payroll and event states are refused by PostgreSQL with valid rows unchanged; recoverable dead work is reported\
证据 5 项，全部通过：JUnit 测试 5
:::

::: e12-case
**E12-F04**　硬门槛 · 安全与授权 · **PASS**\
CRITICAL exceptions close only after an independent recheck; the actor and the recheck are audited; undispatched instructions are reported\
证据 2 项，全部通过：JUnit 测试 2
:::

::: e12-case
**E12-F05**　非硬门槛 · 安全与授权 · **PASS**\
Exceptions and Operations views stay inside the tenant\
证据 2 项，全部通过：JUnit 测试 1 · 浏览器旅程 1
:::

## G　备份与恢复

2 个用例，其中硬门槛 0 个。

::: e12-case
**E12-G01**　非硬门槛 · 恢复 · **PASS**\
Migrations: fresh install, populated V4/V5/V6/V8/V11 upgrades without rewriting history, V7 and V15 refuse inconsistent history; checksums pinned\
证据 10 项，全部通过：JUnit 测试 10
:::

::: e12-case
**E12-G02**　非硬门槛 · 恢复 · **PASS**\
Logical backup (pg\_dump -Fc) of the bank while a payment is only ACCEPTED, restore into a new database after the live bank settled it, and recovery of the simulator\'s later callbacks under the original operation identity with no second booking; measured RPO/RTO of this run\
证据 1 项，全部通过：实验判定 1
:::

## H　界面与运营

4 个用例，其中硬门槛 0 个。

::: e12-case
**E12-H01**　非硬门槛 · 全栈行为 · **PASS**\
Real-API Chromium journeys: personal banking, corporate maker/checker settlement, payroll with lost response, UNKNOWN inquiry, exception, concealment, role denial and revocation\
证据 10 项，全部通过：浏览器旅程 8 · SQL 评分器 2
:::

::: e12-case
**E12-H02**　非硬门槛 · 全栈行为 · **PASS**\
Accessibility subset on the real application: on nine screens every interactive control and image has an accessible name in the computed accessibility tree, one main landmark, a level-1 heading, a document language and title, and no duplicate element ids\
证据 1 项，全部通过：浏览器旅程 1
:::

::: e12-case
**E12-H03**　非硬门槛 · 可观测性 · **PASS**\
Alerting: a real Prometheus 3.13.3 scrape fires the owed-effect and reserve-shortfall alerts on a genuinely created shortage (shortfall 30.00 NZD measured) and both resolve after the audited replenishment and one governed retry\
证据 1 项，全部通过：实验判定 1
:::

::: e12-case
**E12-H04**　非硬门槛 · 可观测性 · **PASS**\
W3C trace continuity: approval -\> instruction dispatch -\> simulator request -\> callback delivery -\> bank callback -\> settlement, stitched only through recorded parent ids and links; client trace ids are never authority\
证据 5 项，全部通过：JUnit 测试 4 · 实验判定 1
:::

## 汇总

36 个用例，36 个 PASS；共核对 129 个证据来源。同一个来源（例如评分器对某次运行数据库的结果）可以计入多个用例。
