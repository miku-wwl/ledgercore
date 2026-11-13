# 第 7 章　让数据库拒绝不合法的账本

数据库模式是业务不变量的最后一道可执行边界。迁移 V2 创建 ledger_accounts、journal_entries、postings 与 account_balance_projection；外键要求分录引用有效账本账户，币种检查限制为 NZD，金额必须为正，单个凭证不能对同一账本账户重复记账。

![凭证、分录与余额投影的关系](manual/figures/07-journal-schema.svg)

普通 CHECK 约束不能直接聚合同一凭证的多行数据，所以迁移定义了延迟约束触发器。它在提交时统计借方、贷方和分录数，要求至少两笔且总额相等。这很关键：事务创建 journal entry 后才插入 postings，若立刻检查父行，合法的多步写入在中途会显得不完整。

![提交点触发器检查凭证](manual/figures/10-rollback.svg)

凭证和分录表另设 UPDATE/DELETE 拒绝触发器，保持已写历史 append-only。冲正通过新凭证反向记录，而不是改写旧事实。V3、V4 还为 transfer 和幂等成功回执增加跨表提交约束。

{{code:db_balance_trigger}}

Schema migration 是程序演进的一部分。Flyway 按版本顺序执行 V1–V4；Hibernate 使用 validate 而非自动更改生产形状。CI 从空 PostgreSQL 创建完整结构，能及早发现仅本机数据库拥有的隐藏状态。

提交触发器并不自动防住任意业务错误：平衡凭证可以记到错误账户，币种范围只有 NZD，储备账户也只是演示建模。约束缩小不可能状态的空间，不能代替授权和业务设计。

**练习：** 假设开发者直接插入一条只有借方的 journal entry，然后提交。错误应在哪个时刻出现？如果数据库只靠 Java 构造器，会漏掉哪类写入路径？
