"""Diagrams for Parts I-II (context, architecture, financial core). Every box names a real component, table, status or
port of the frozen implementation; nothing here is a roadmap."""
from __future__ import annotations

from charts import C
from diagrams import Diagram, sequence


def friday_story(path):
    d = Diagram(318, title="周五发薪：两条资金路径与一个运营视角")
    d.group(10, 30, 620, 118, "工资路径：银行内部转账（同步、逐项原子）", kind="cool")
    a = d.box(22, 62, 118, 54, ["工资专员", ("PAYROLL_MAKER", True), ("导入 500 项", True)], "plain", size=10)
    b = d.box(176, 62, 118, 54, ["财务主管", ("PAYROLL_APPROVER", True), ("独立批准", True)], "plain", size=10)
    c = d.box(330, 62, 136, 54, ["执行器", ("逐项 SKIP LOCKED", True), ("每项一个事务", True)], "real", size=10)
    e = d.box(502, 62, 116, 54, ["员工个人账户", ("立即入账", True), ("每项恰好一次", True)], "good", size=10)
    d.edge(a, b, "提交")
    d.edge(b, c, "批准")
    d.edge(c, e, "转账")
    d.group(10, 158, 620, 118, "供应商付款路径：经独立清算（异步、结果可能未知）", kind="warm")
    f = d.box(22, 190, 118, 54, ["付款专员", ("PAYMENT_MAKER", True), ("创建并提交", True)], "plain", size=10)
    g = d.box(176, 190, 118, 54, ["财务主管", ("PAYMENT_APPROVER", True), ("批准即记账", True)], "plain", size=10)
    h = d.box(330, 190, 136, 54, ["Outbox → 模拟器", ("签名指令", True), ("ACCEPTED/PENDING", True)], "sim", size=10)
    k = d.box(502, 190, 116, 54, ["回调 SETTLED", ("结算分录", True), ("REJECTED → 退款", True)], "good", size=10)
    d.edge(f, g, "提交")
    d.edge(g, h, "派发")
    d.edge(h, k, "回调")
    d.box(10, 284, 620, 28, ["运营（OPERATIONS）：查询、未知结果询价、受治理的重试、独立对账与例外复核"], "dark", size=10)
    d.save(path)


def modules(path):
    d = Diagram(372, title="模块化单体：每个模块真实依赖的模块（来自源码 import，省略人人都用的 api/identity）")
    d.group(10, 30, 474, 334, "LedgerCore 银行进程（Spring Boot 4 · Spring Modulith 2）", kind="zone")
    d.box(22, 54, 450, 30, ["api：REST 控制器、问题响应（RFC 9457）、关联 ID、W3C Trace Context"], "plain", size=10)
    mods = [
        (22, 94, "organizations", "accounts · events", "real"),
        (172, 94, "payments", "accounts · events · ledger · organizations", "real"),
        (322, 94, "payroll", "accounts · events · ledger · organizations · transfers", "real"),
        (22, 162, "reconciliation", "clearing · events · ledger · organizations", "sim"),
        (172, 162, "clearing", "events · ledger · organizations", "sim"),
        (322, 162, "transfers", "accounts · events · ledger · organizations", "real"),
        (22, 230, "operations", "events · organizations", "plain"),
        (172, 230, "accounts", "ledger", "plain"),
        (322, 230, "transactionhistory", "ledger", "plain"),
    ]
    for x, y, name, deps, kind in mods:
        lines, current = [], "依赖："
        for part in deps.split(" · "):
            candidate = current + ("" if current.endswith("：") else " · ") + part
            if len(candidate) > 27 and not current.endswith("："):
                lines.append(current)
                current = part
            else:
                current = candidate
        lines.append(current)
        d.box(x, y, 142 if x < 322 else 150, 60 if y < 230 else 52, [name] + [(line, True) for line in lines], kind, size=10, lh=13)
    d.box(22, 292, 222, 58, ["events", ("Outbox 写入、派发、消费收据", True), ("不依赖任何业务模块", True)], "store", size=10)
    d.box(252, 292, 220, 58, ["ledger", ("唯一写入金额的模块：LedgerWriter", True), ("不依赖任何业务模块", True)], "store", size=10)
    d.group(498, 30, 132, 334, "清算模拟器进程", kind="warm")
    s1 = d.box(508, 64, 112, 62, ["指令接口", ("POST /internal/", True), ("operations", True)], "sim", size=10)
    s2 = d.box(508, 150, 112, 62, ["回调派发器", ("HMAC 签名、序号", True), ("8 次退避后 DEAD", True)], "sim", size=10)
    s3 = d.box(508, 250, 112, 58, ["模拟器数据库", ("独立 PostgreSQL 库", True)], "store", shape="cyl", size=10)
    d.edge(s1, s2, "", src="b", dst="t")
    d.edge(s2, s3, "", src="b", dst="t")
    d.para(508, 334, ["只经签名 HTTP", "与银行交互"], 9.2)
    d.save(path)


def invariant_layers(path):
    d = Diagram(276, title="一条不变量由几层共同守护：以“分录必须平衡”和“终态必须有分录”为例")
    cols = [("Java 领域对象", "real", ["JournalEntry 构造", "金额 > 0、同币种", "借贷合计相等"]),
            ("事务边界", "plain", ["@Transactional", "账户按 UUID 排序加锁", "同一提交：分录与投影"]),
            ("PostgreSQL 约束", "store", ["CHECK / UNIQUE / FK", "延迟约束触发器：平衡", "V15：终态 ⇔ 分录"]),
            ("追加式历史", "store", ["postings 只增不改", "删除/改写被触发器拒绝", "Flyway 校验和固定"]),
            ("独立对账", "sim", ["只读 SQL 重新计算", "发现即记例外", "复核通过才可关闭"])]
    x = 12
    for i, (title, kind, lines) in enumerate(cols):
        b = d.box(x, 40, 116, 46, [title], kind, size=10.5)
        for j, line in enumerate(lines):
            d.box(x, 98 + j * 40, 116, 32, [line], "plain", size=9.5, bold=False)
        if i < len(cols) - 1:
            d.edge((x + 116, 63), (x + 128, 63), None)
        x += 126
    d.para(14, 238, ["由左到右越来越接近“最后一道防线”：应用层的检查让错误尽早失败，数据库约束让绕过应用的写入也失败，",
                     "追加式历史让已提交的事实无法被悄悄改写，独立对账则在以上任何一层出错时把差异显性化。",
                     "第 20 章的 18 个负例逐层验证“被拒绝且有效数据不变”。"], 9.4, C["ink"])
    d.save(path)


def lab_topology(path):
    d = Diagram(322, title="可复现实验环境：每类实验独占端口与一次性数据库")
    rows = [("55432", "持久本地服务", "从不连接", "bad"),
            ("55437", "E6/E9/G02 实验集群", "每次运行新建 ledgercore_e6_<run>", "store"),
            ("55450", "Round 4 修复与回归", "ledgercore_r4_*，每次 verify 新库", "store"),
            ("55460", "E12 独立评估", "ledgercore_e12_<run>_*，不复用", "store"),
            ("18094/18095", "实验室模拟器/银行", "精确 PID、JAR 哈希校验后才杀进程", "sim"),
            ("18196/18197/5173", "E12 全栈", "真实 API + Vite + Chromium", "real"),
            ("19090", "Prometheus 3.13.3", "仅抓取实验银行", "real")]
    y = 34
    for port, name, rule, kind in rows:
        d.box(12, y, 112, 30, [port], kind, size=10.5)
        d.box(132, y, 170, 30, [name], "plain", size=10)
        d.box(310, y, 318, 30, [rule], "plain", size=9.5, bold=False)
        y += 37
    d.para(14, 314, "所有脚本先核对端口归属、数据库名与 inet_server_port()，只停止自己启动的进程。", 9.2)
    d.save(path)


def polarity(path):
    d = Diagram(330, title="账户方向：借方正常的资产与贷方正常的负债，以及恒等式 R + T = D + P")
    d.group(10, 32, 300, 224, "借方正常（资产，增加记借方）", kind="cool")
    r = d.box(22, 60, 276, 50, ["SIMULATION_RESERVE（R）", ("…0001 · 银行在清算中的结算准备金", True)], "store", size=10.5)
    t = d.box(22, 122, 276, 50, ["SIMULATION_TREASURY（T）", ("…0003 · V13 新增，仅流动性划转可用", True)], "store", size=10.5)
    d.box(22, 184, 276, 60, ["余额 = Σ借方 − Σ贷方", ("准备金不能为负：结算被拒而不是透支", True)], "plain", size=10)
    d.group(330, 32, 300, 224, "贷方正常（负债，增加记贷方）", kind="warm")
    dd = d.box(342, 60, 276, 50, ["CUSTOMER_DEPOSIT（D）", ("每个客户账户一个；开户注入 1,000.00", True)], "sim", size=10.5)
    p = d.box(342, 122, 276, 50, ["CLEARING_PAYABLE（P）", ("…0002 · 已记账、尚未结算的应付", True)], "sim", size=10.5)
    d.box(342, 184, 276, 60, ["余额 = Σ贷方 − Σ借方", ("客户存款是银行欠客户的钱", True)], "plain", size=10)
    d.box(10, 266, 620, 56, ["每张分录借贷相等 ⇒ 全账簿：Σ借方正常余额 = Σ贷方正常余额，即 R + T = D + P",
                             ("记账 Dr D / Cr P；结算 Dr P / Cr R；退款 Dr P / Cr D；流动性划转 Dr 收方资产 / Cr 付方资产", True)], "dark", size=10.5)
    d.save(path)


def journal_schema(path):
    d = Diagram(330, title="分录、过账与余额投影：哪些写入被数据库拒绝")
    je = d.box(12, 40, 190, 92, ["journal_entries", ("id · entry_type · reference", True), ("reference 唯一（按类型）", True), ("只增，不可更新/删除", True)], "store", size=10)
    po = d.box(226, 40, 190, 92, ["postings", ("journal_entry_id · ledger_account_id", True), ("side DEBIT/CREDIT · amount_minor > 0", True), ("只增；每张分录 ≥ 2 行", True)], "store", size=10)
    la = d.box(440, 40, 188, 92, ["ledger_accounts", ("kind · normal_side", True), ("customer_account_id", True), ("系统账户固定 UUID", True)], "store", size=10)
    pr = d.box(440, 168, 188, 70, ["account_balance_projection", ("balance_minor（读模型）", True), ("与过账同事务更新", True)], "store", size=10)
    d.edge(po, je, "N:1", src="l", dst="r")
    d.edge(po, la, "N:1", src="r", dst="l")
    d.edge(pr, la, "1:1", src="t", dst="b")
    d.group(12, 160, 404, 160, "被拒绝的写入（第 6 章、第 20 章的负例逐条执行）", kind="bad")
    d.para(26, 196, ["• 借贷不等的分录在提交时被延迟约束触发器拒绝", "• UPDATE/DELETE 历史过账：append-only 触发器拒绝",
                     "• 金额 ≤ 0、币种非 NZD：CHECK 拒绝", "• 同一付款的第二张结算/退款：唯一约束拒绝",
                     "• V15：COMPLETED 却没有结算分录：ck_corporate_payment_terminal_journals",
                     "• 只有 RESERVE_LIQUIDITY_TRANSFER 可以触碰 TREASURY（V13 触发器）"], 9.4, C["ink"], lh=19)
    d.para(446, 262, ["投影可以由过账重算；", "对账发现漂移时按不可变", "过账修复，绝不反向改写。"], 9.2)
    d.save(path)


def lock_order(path):
    d = Diagram(300, title="锁顺序与热点付款账户：先按 UUID 排序再 SELECT … FOR UPDATE")
    d.group(10, 32, 300, 200, "相反方向的两笔转账（A→B 与 B→A）", kind="zone")
    t1 = d.box(22, 62, 130, 40, ["事务 1：A→B"], "real", size=10)
    t2 = d.box(168, 62, 130, 40, ["事务 2：B→A"], "real", size=10)
    l1 = d.box(22, 122, 276, 32, ["账本账户 UUID 升序：先 min 后 max"], "good", size=10)
    d.box(22, 170, 276, 50, ["后到者在第一把锁上等待，", ("不会形成环 → 0 次死锁（E3 H02，50 客户端）", True)], "plain", size=10)
    d.edge(t1, l1, "", src="b", dst=("t", 0.25))
    d.edge(t2, l1, "", src="b", dst=("t", 0.75))
    d.group(330, 32, 300, 200, "一个热点付款账户（E3 H02 实测中位数）", kind="warm")
    rows = [("1 客户端", "144.37 ops/s", "p95 8.59 ms"), ("10", "158.16", "74.95"), ("30", "131.39", "307.67"), ("50", "112.64", "606.04")]
    y = 64
    for a, b, c in rows:
        d.box(342, y, 80, 28, [a], "plain", size=9.5, bold=False)
        d.box(428, y, 96, 28, [b], "sim", size=9.5, bold=False)
        d.box(530, y, 88, 28, [c], "plain", size=9.5, bold=False)
        y += 34
    d.para(342, 214, "同一行锁串行化：吞吐在 10 客户端后下降，p95 上升。", 9.2)
    d.box(10, 244, 620, 48, ["正确性不随并发退化：48 组场景/重复全部 0 透支、0 投影偏差、0 死锁；性能退化是可见、可解释的",
                             ("本地笔记本合成负载，Hikari 上限 10 连接；不代表生产容量", True)], "note", size=10)
    d.save(path)


def idempotency(path):
    sequence(path, ["浏览器", "TransferService", ("idempotency_records", "store"), ("账本", "store")], [
        ("msg", 0, 1, "POST /api/transfers\nIdempotency-Key: k1"),
        ("band", "一个数据库事务：要么全部提交，要么全部回滚", "cool"),
        ("msg", 1, 2, "插入 (owner, op, k1, 请求指纹) IN_PROGRESS"),
        ("msg", 1, 3, "按账本账户 UUID 加锁；分录 + 投影"),
        ("msg", 1, 2, "IN_PROGRESS → SUCCEEDED，保存回执"),
        ("end",),
        ("msg", 1, 0, "201 回执（假设响应在网络中丢失）", "reply"),
        ("band", "重试：同一键、同一请求体", "zone"),
        ("msg", 0, 1, "POST，同一 k1"),
        ("msg", 1, 2, "查到 SUCCEEDED，指纹一致"),
        ("msg", 1, 0, "返回原回执，不再过账", "reply"),
        ("end",),
        ("band", "误用：同一键、不同金额", "warm"),
        ("msg", 0, 1, "POST k1，金额改变"),
        ("msg", 1, 0, "409 IDEMPOTENCY_KEY_REUSED", "reply"),
        ("end",),
    ], title="幂等：稳定的操作身份让“响应丢失后重试”只产生一次过账", number=True)


def flyway_timeline(path):
    d = Diagram(352, title="Flyway 只向前演进：V1–V11 校验和固定，Round 4 只新增 V12–V15")
    items = [("V1–V4", "客户、账户、账本、转账与幂等回执生命周期", "real"),
             ("V5–V7", "组织与企业账户；工资批次；R2 加固（意图与结果完整性）", "real"),
             ("V8–V9", "Policy B 付款、持久事件、清算回调生命周期", "real"),
             ("V10–V11", "被否决的索引候选及其向前移除", "ghost"),
             ("V12", "为“已结算未记账”的历史付款补登持久工作项（不过账）", "good"),
             ("V13", "SIMULATION_TREASURY 与受限的流动性划转分录", "good"),
             ("V14", "outbox_events.trace_parent（不可变事实）", "good"),
             ("V15", "终态付款必须绑定分录（含迁移前预检）", "good")]
    y = 34
    for v, text, kind in items:
        d.box(12, y, 90, 30, [v], kind, size=10.5)
        d.box(112, y, 516, 30, [text], "plain", size=9.8, bold=False)
        y += 36
    d.para(14, 344, "模拟器库独立演进：V1–V3 已发布；V4 追踪上下文、V5 银行恢复后的重投（Round 4）。", 9.2)
    d.save(path)


ALL = {
    "fig-friday-story": friday_story, "fig-modules": modules, "fig-invariant-layers": invariant_layers,
    "fig-lab-topology": lab_topology, "fig-polarity": polarity, "fig-journal-schema": journal_schema,
    "fig-lock-order": lock_order, "fig-idempotency": idempotency, "fig-flyway": flyway_timeline,
}
