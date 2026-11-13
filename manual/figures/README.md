# 编辑矢量机制图

以下 SVG 均为本教材原创、可用 Inkscape、Illustrator 或文本编辑器继续修改。图形表达 LedgerCore 固定源码中的机制与边界，不是架构全景或生产部署拓扑。

- 图 01：一笔转账从点击到确认（`figures/01-transfer-flow.svg`）
- 图 02：一个事件，两侧等额分录（`figures/02-debit-credit.svg`）
- 图 03：不变量按它能观察的事实分层（`figures/03-invariants.svg`）
- 图 04：模块化单体以业务能力划分边界（`figures/04-modules.svg`）
- 图 05：HTTP 请求进入应用的顺序（`figures/05-http-lifecycle.svg`）
- 图 06：JPA 状态变更与版本检查（`figures/06-jpa-lifecycle.svg`）
- 图 07：账本表用外键连接经济事件（`figures/07-journal-schema.svg`）
- 图 08：READ COMMITTED 的新命令看到新快照（`figures/08-mvcc.svg`）
- 图 09：竞争写入必须在同一资源上排队（`figures/09-concurrent-transfers.svg`）
- 图 10：统一账户排序，避免交叉锁环（`figures/10-lock-order.svg`）
- 图 11：原子提交：所有写入一起成功或撤销（`figures/10-rollback.svg`）
- 图 12：同一意图的重试返回同一回执（`figures/11-idempotency.svg`）
- 图 13：投影服务读取，账本支持复算（`figures/12-projection-reconciliation.svg`）
- 图 14：身份、CSRF 与对象权限回答不同问题（`figures/13-security-boundary.svg`）
- 图 15：React 页面把本地输入与服务端数据分开（`figures/14-react-state.svg`）
- 图 16：服务端提交与浏览器收到响应并不原子（`figures/14-unknown-result.svg`）
- 图 17：缓存失效刷新视图，不改变账务事实（`figures/15-react-query.svg`）
- 图 18：表单状态必须保留用户的原始意图（`figures/16-form-recovery.svg`）
- 图 19：索引由过滤、连接、排序与限制共同决定（`figures/17-query-index.svg`）
- 图 20：每一层测试证明不同事实（`figures/18-test-pyramid.svg`）
- 图 21：一条需求需要全栈共同兑现（`figures/19-cross-stack.svg`）
- 图 22：可靠结果由多个可验证属性串联（`figures/20-end-to-end.svg`）
