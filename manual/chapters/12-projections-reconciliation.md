# 第 12 章　余额投影、历史与对账

以 Posting 为准，每次打开仪表盘都聚合所有历史记录，可能很慢。因此系统为每个账本账户保留 account_balance_projection，方便显示余额。但投影不是第二份权威事实：写账本成功与投影更新同事务，重算也从 Posting 历史出发；对账端点比较两者并报告是否一致。

![权威 Posting 与余额投影之间的对账关系](manual/figures/12-projection-reconciliation.svg)

事务历史是另外一个读模型。用户按账户浏览对方、方向和金额；服务端使用创建时间与 transfer UUID 的稳定顺序，并提供游标页。游标避免在大偏移量下反复跳过旧行，但多次 HTTP 调用不共享数据库快照，因此新交易可能出现在后续刷新中。

![本地实际运行的账户历史列表](manual/assets/screenshots/history-list.png)

![本地运行中的账户历史与分录详情](manual/assets/screenshots/history-detail.png)

交易详情只向本人显示安全的 Counterparty 标签，不返回对方账户 ID。跨客户访问时，应用统一 404。读模型的方便性不能削弱隐私边界：查询 SQL 要把账户 ID 与认证主体一起作为筛选条件。

![账户的余额对账状态](manual/assets/screenshots/account-reconciliation.png)

当 reconciliation 返回 matches=false，它是诊断信号，不是自动“修余额”的授权。应追溯 Posting、事务失败边界、手工数据变更和投影写入。自动覆盖投影可能隐藏根因。测试把迁移前后的空数据库、写入故障以及转账成功后的账本总额和投影分别断言。

**取舍：** 实时聚合能减少冗余，却随历史增长而变贵；投影加快读取，却引入双写一致性责任。对账与单事务写入让这种责任可检查，并没有消除其复杂度。
