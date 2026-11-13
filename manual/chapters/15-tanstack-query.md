# 第 15 章　TanStack Query 管理服务端状态

TanStack Query v5 的 [官方文档](https://tanstack.com/query/v5/docs/framework/react/overview)介绍请求缓存、刷新和失效；本项目用 query key 管理账户列表、近期活动、历史与账户对账读取。缓存中的值代表最近一次成功查询，不是账本权威记录本身。

![React Query 缓存如何响应转账写入](manual/figures/15-react-query.svg)

账户数据查询使用 accounts key；历史查询以 transactions 和账户 ID 组成分页 key。转账成功后，页面失效账户、交易历史和对账缓存，再重新读取服务端状态。缓存失效只表示这些数据可能过期，不会回滚或补写账务记录。

用户未确认结果的操作不应跟着 query cache 的生命周期消失。TransferPage 在发请求前把原始意图与 UUID key 放进 sessionStorage，供同一个标签页刷新后恢复；标签页关闭后不承诺保留。此状态与 TanStack Query 的账户读取缓存是两个用途、两条生命周期。

客户端不能过度乐观地显示“余额已扣”并把它当成确认。若请求提交后响应丢失，乐观 UI 可能恰好看起来成功，却没有持久回执。LedgerCore 等待服务端返回成功回执后再展示 Transfer complete；未知时以单独提示让用户用原键核实。

![失败相关 key 不会替代未知结果恢复](manual/figures/14-unknown-result.svg)

**小练习：** 为账户详情、历史页和转账回执设计 key。完成转账后哪些 key 应失效？若回执已成功，但历史刷新失败，应告诉用户转账失败，还是历史列表未能更新？这两个结论必须分开。
