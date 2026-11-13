# Bank of Anthos 账务参考审计

## 审计基线

只读检查 GoogleCloudPlatform/bank-of-anthos 的浅克隆，提交 db35fea9fd090150e2398106aadb475576f80d94。本文判断针对该固定快照；后续上游变化不自动改变本文结论。主要检查 ledgerwriter、balancereader、transactionhistory、ledger-db 与 ledgermonolith，以及上游 README。

## 五个模块的实际责任

| 模块 | 业务责任和数据 | 设计取舍与可迁移教训 |
|---|---|---|
| Ledger Writer | Spring Boot HTTP 端点校验 JWT、账户格式和金额，检查客户端发来的 transaction UUID 缓存，再查询 Balance Reader 的余额，最后把 Transaction 保存到 ledger-db。Transaction 有交易 ID、来源/目标账户与路由、整数 AMOUNT、时间戳；uuid 标为 JPA Transient，不落库。 | 把可理解的写入规则放在一个服务中。内存 UUID 缓存一小时，不是跨进程或重启持久幂等；余额远程读取与写入分离，不等于同一事务的并发扣款保证。LedgerCore 用持久唯一键、请求指纹与单数据库原子事务。 |
| Balance Reader | 从 TRANSACTIONS 计算本行本地路由账户的 SUM(入账) - SUM(出账)，并以 Guava LoadingCache 保存读值。LedgerReader 启动时读最大交易 ID，之后按固定间隔轮询新增 ID 并调用回调更新缓存。 | 账本派生读模型和有限缓存减少读取工作；周期轮询带来投影延迟与后台线程生命周期问题。MVP 保留可重算投影的概念，但交易中源余额的安全校验必须经过 PostgreSQL 锁和账本读取。 |
| Transaction History | 查询来源或目标账户命中的交易，按 timestamp 降序取 historyLimit 条，并通过 Guava LoadingCache 按配置容量和有效期缓存。每个 Java 服务拥有自己的 Transaction/JPA 映射。 | 查询与账户过滤是可复用的业务需求；固定窗口缓存和多个副本需要一致性设计，有限结果不等于完整游标分页。LedgerCore 使用服务端所有权过滤、稳定排序键和 API 分页。 |
| Ledger Database | PostgreSQL 单表 TRANSACTIONS 保存一行包含 from/to/amount 的转账记录；有来源、目标账户与时间戳索引，SQL 规则抑制 UPDATE 和 DELETE；初始化脚本可放入演示数据。 | 单表追加历史直观地表达内部付款及不可变记录。它不是复式账务：没有 JournalEntry 中多条借/贷 Posting，也没有按会计类别定义正常余额或可重建余额对账。LedgerCore 独立建模这些概念。 |
| Java Ledger Monolith | README 描述把 Ledger Writer、Balance Reader 和 Transaction History 三个 Java 服务合到一个 Spring Boot/Guava 服务；Controller 共享数据库访问和聚合缓存，后台 LedgerReader 仍按交易 ID 轮询。对完整应用而言，前端、身份、联系人及数据库等其他组件仍在 monolith 外。 | 说明服务边界可因部署目标而合并；“一个服务”本身不形成清楚的领域模块或消除投影语义。LedgerCore 采用单个进程内显式模块，而不复制其缓存、交易表或 Kubernetes 部署机制。 |

## 金融业务概念与工程判断

可借鉴的是付款人/收款人、金额正数校验、账户归属、资金充足性、交易历史追加以及由账本计算余额等概念。LedgerCore 将金额扩大为 long 分/BIGINT，API 采用精确十进制字符串；将单行 payment 记录拆为至少两条 Posting 的平衡凭证；将进程缓存 UUID 改为持久幂等表；将余额读取与扣款提交放在同一事务协议；用独立的 Integration 测试证明 PostgreSQL 行锁、约束与回滚。

不借用的部分包括多语言服务拆分、Kubernetes/Skaffold、服务间 JWT 和远程同步、云专用发布/遥测、GKE/Cloud SQL/Service Mesh、负载生成器和样例级运行缓存。LedgerCore MVP 不需要这些基础设施。

## 代码复用与许可

上游仓库声明 Apache License 2.0，检查的 Java、SQL 文件也带有 Google LLC 版权及许可头。LedgerCore 的初始实现独立编写，没有从该克隆复制代码。README 保留“受 Bank of Anthos 启发”的归属说明，并澄清 LedgerCore 与 Google 无关联或背书。若将来确需复制/改编代码，必须随派生文件保留上游版权与许可通知、提供 Apache-2.0 许可证并标明修改；本审计不授予商标使用权。

## 固定上游文件

- [Bank of Anthos README at audited commit](https://github.com/GoogleCloudPlatform/bank-of-anthos/blob/db35fea9fd090150e2398106aadb475576f80d94/README.md)
- [Ledger Writer Transaction](https://github.com/GoogleCloudPlatform/bank-of-anthos/blob/db35fea9fd090150e2398106aadb475576f80d94/src/ledger/ledgerwriter/src/main/java/anthos/samples/bankofanthos/ledgerwriter/Transaction.java)
- [Ledger Writer Controller](https://github.com/GoogleCloudPlatform/bank-of-anthos/blob/db35fea9fd090150e2398106aadb475576f80d94/src/ledger/ledgerwriter/src/main/java/anthos/samples/bankofanthos/ledgerwriter/LedgerWriterController.java)
- [Balance Reader cache and repository](https://github.com/GoogleCloudPlatform/bank-of-anthos/tree/db35fea9fd090150e2398106aadb475576f80d94/src/ledger/balancereader/src/main/java)
- [Transaction History repository and cache](https://github.com/GoogleCloudPlatform/bank-of-anthos/tree/db35fea9fd090150e2398106aadb475576f80d94/src/ledger/transactionhistory/src/main/java)
- [Ledger database schema](https://github.com/GoogleCloudPlatform/bank-of-anthos/blob/db35fea9fd090150e2398106aadb475576f80d94/src/ledger/ledger-db/initdb/0_init_tables.sql)
- [Java monolith source](https://github.com/GoogleCloudPlatform/bank-of-anthos/tree/db35fea9fd090150e2398106aadb475576f80d94/src/ledgermonolith/src/main/java)
- [Apache License 2.0](https://github.com/GoogleCloudPlatform/bank-of-anthos/blob/db35fea9fd090150e2398106aadb475576f80d94/LICENSE)

This review is source analysis, not a claim that Bank of Anthos was run in this session.
