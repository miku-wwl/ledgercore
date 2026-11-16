"""Diagrams for Parts III-IV (corporate payments, payroll, durable events, clearing and its Round 4 remediations).
Names, statuses, constants and transitions are those of the frozen implementation."""
from __future__ import annotations

from charts import C
from diagrams import Diagram, sequence


def maker_checker(path):
    d = Diagram(326, title="Policy B：职责分离由服务端和数据库同时执行")
    m = d.box(12, 40, 150, 58, ["付款专员 A", ("PAYMENT_MAKER", True), ("创建、提交", True)], "plain")
    s = d.box(196, 40, 160, 58, ["SUBMITTED", ("intent_digest 已保存", True), ("内容不可再改", True)], "real")
    a = d.box(392, 40, 236, 58, ["财务主管 B 批准", ("PAYMENT_APPROVER 且 B ≠ 提交者", True), ("摘要重新计算并比对", True)], "good")
    d.edge(m, s, "submit")
    d.edge(s, a, "approve")
    x1 = d.box(12, 132, 300, 46, ["A 批准自己的付款 → 403 PAYMENT_MAKER_CHECKER", ("即使 A 同时持有 MAKER 与 APPROVER 角色", True)], "bad", size=10)
    x2 = d.box(328, 132, 300, 46, ["通用转账从企业账户扣款 → 409", ("CORPORATE_APPROVAL_REQUIRED（Policy B 守卫）", True)], "bad", size=10)
    x3 = d.box(12, 192, 300, 46, ["管理员给自己授予角色 → 拒绝", ("“An administrator cannot grant roles…”", True)], "bad", size=10)
    x4 = d.box(328, 192, 300, 46, ["批准后角色被撤销 → 无法再记账", ("每次请求按当前成员资格重新授权", True)], "bad", size=10)
    d.box(12, 254, 616, 62, ["E8 实测矩阵：12 个主体 × 44 条路由 = 528 格，0 失败；243 个被拒绝的变更逐一核对“无副作用”",
                             ("非成员看到 404（隐藏组织存在），成员缺角色看到 403；组织或成员被暂停后立即失效", True)], "dark", size=10)
    d.save(path)


def digest(path):
    d = Diagram(300, title="不可变的批准意图：摘要覆盖什么，谁在何时校验")
    fields = ["FORMAT 标签", "payment id", "organization id", "source account id", "创建者 id", "收款人名称",
              "收款账户引用", "路由代码", "amount_minor", "currency", "version"]
    d.group(10, 30, 250, 262, "SHA-256 输入（长度前缀的二进制编码）", kind="cool")
    for i, f in enumerate(fields):
        d.box(22 + (i % 2) * 116, 56 + (i // 2) * 38, 110, 30, [f], "plain", size=9.5, bold=False)
    st = d.box(286, 48, 342, 50, ["submit：计算 intent_digest 并保存", ("之后任何内容变更都被触发器拒绝", True)], "real", size=10)
    ap = d.box(286, 112, 342, 50, ["approve：重新计算，必须等于 intent_digest", ("相同才写入 approved_digest 并记账", True)], "good", size=10)
    ins = d.box(286, 176, 342, 50, ["派发：instruction.intent_digest = approved_digest", ("模拟器回显摘要不一致 → 拒绝该响应", True)], "sim", size=10)
    cb = d.box(286, 240, 342, 50, ["回调：摘要不符 → CLEARING_INTENT_MISMATCH", ("CRITICAL 例外，不产生任何资金效果", True)], "bad", size=10)
    d.edge(st, ap, "", src="b", dst="t")
    d.edge(ap, ins, "", src="b", dst="t")
    d.edge(ins, cb, "", src="b", dst="t")
    d.save(path)


def payroll_claim(path):
    d = Diagram(332, title="500 项工资批次：认领、单项事务与批次收尾")
    d.box(12, 40, 186, 54, ["startProcessing", ("APPROVED → PROCESSING", True)], "real", size=10)
    loop = d.group(212, 30, 416, 196, "每一项（直到 limit 或没有 PENDING 项）", kind="zone")
    c = d.box(224, 54, 190, 62, ["认领下一项", ("FOR UPDATE OF r SKIP LOCKED", True), ("LIMIT 1（并发执行器互不阻塞）", True)], "real", size=9.8)
    t = d.box(426, 54, 190, 62, ["单项事务内转账", ("幂等键 payroll-<batch>-<item>", True), ("成功 → SUCCEEDED + transfer_id", True)], "good", size=9.8)
    f = d.box(224, 140, 190, 70, ["业务失败（如余额不足）", ("单项事务整体回滚", True), ("另一事务记 FAILED + error_code", True)], "bad", size=9.8)
    n = d.box(426, 140, 190, 70, ["下一项", ("一项的失败不影响其它项", True), ("已成功的项永不重付", True)], "plain", size=9.8)
    d.edge(c, t, "")
    d.edge(c, f, "", src="b", dst="t")
    d.edge(t, n, "", src="b", dst="t")
    d.edge(f, n, "")
    d.box(12, 110, 186, 100, ["finishIfReady", ("全部成功 → COMPLETED", True), ("部分失败 → PARTIALLY_COMPLETED", True), ("全部失败 → FAILED", True)], "store", size=10)
    d.box(12, 240, 616, 80, ["E3 H03：500 项批次 1/10/30/50 个执行器，中位数 112.50/148.46/153.74/150.41 项/秒；24 组场景 0 重复转账、0 死锁",
                             ("R4：执行器在失败项回滚之后、记录 FAILED 之前被杀死（E6 payroll-mark-failure-crash），", True),
                             ("重启后失败只记一次、第 3 项付一次、第 1 项不重付：付款方 −4.00、收款方 +4.00", True)], "note", size=10)
    d.save(path)


def payroll_crash(path):
    sequence(path, ["执行器 JVM", ("PostgreSQL", "store"), "实验 harness"], [
        ("msg", 0, 1, "第 1 项 1.00：转账提交，SUCCEEDED"),
        ("msg", 0, 1, "第 2 项 2,000.00：余额不足，事务回滚"),
        ("note", 0, 0, "PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK\n写 checkpoint.json 并阻塞", "inject"),
        ("msg", 2, 0, "核对 PID 与 JAR 后 Stop-Process -Force"),
        ("note", 1, 1, "此刻：第 1 项 SUCCEEDED，第 2、3 项 PENDING\n批次 PROCESSING，付款方 999.00", "sim"),
        ("band", "重启（无故障配置）后由同一批准人再次执行", "cool"),
        ("msg", 0, 1, "第 2 项重新认领 → 再次余额不足 → FAILED（1 次）"),
        ("msg", 0, 1, "第 3 项 3.00 → SUCCEEDED"),
        ("msg", 0, 1, "finishIfReady → PARTIALLY_COMPLETED"),
        ("end",),
        ("msg", 2, 1, "独立 SQL 评分：2 笔转账、2 个回执、0 不平衡分录"),
    ], title="工资崩溃窗口：失败项回滚之后、FAILED 记录之前", number=True)


def outbox(path):
    d = Diagram(352, title="持久化 Outbox：事实与事件同一次提交，派发至少一次，消费者自己去重")
    d.group(10, 30, 300, 150, "业务事务（例如批准并记账）", kind="cool")
    d.box(22, 56, 276, 34, ["业务事实：分录、状态、审计"], "good", size=10)
    d.box(22, 98, 276, 34, ["outbox_events 行：PENDING（event_key 唯一）"], "store", size=10)
    d.para(22, 158, "同一个 COMMIT：要么都在，要么都不在（E4、E6）", 9.3, C["ink"])
    d.group(330, 30, 300, 150, "派发器（每个实例都可运行）", kind="zone")
    c = d.box(342, 56, 276, 34, ["claimNext：FOR UPDATE SKIP LOCKED，租约 30 s"], "real", size=10)
    h = d.box(342, 98, 276, 34, ["处理器 → markDelivered（校验租约）"], "real", size=10)
    d.para(342, 158, "崩溃后租约过期，另一实例重新认领", 9.3, C["ink"])
    d.edge((310, 115), (342, 73), "", via=[(320, 115), (320, 73)])
    d.edge(c, h, "", src="b", dst="t")
    p = d.box(22, 214, 120, 34, ["PENDING"], "store", size=10.5)
    q = d.box(214, 214, 120, 34, ["PROCESSING"], "real", size=10.5)
    s = d.box(406, 214, 100, 34, ["DELIVERED"], "good", size=10.5)
    x = d.box(528, 214, 100, 34, ["DEAD"], "bad", size=10.5)
    d.edge(p, q, "认领")
    d.edge(q, s, "成功")
    d.edge(q, p, "失败：按 1/2/4/8 s 退避", src="b", dst="b", via=[(274, 268), (82, 268)], style="dash")
    d.edge(q, x, "第 5 次失败", src="t", dst="t", via=[(274, 198), (578, 198)], style="dash", color=C["red"])
    d.box(12, 284, 616, 58, ["受治理的重试：只有 OPERATIONS 能把 DEAD 重新置为 PENDING（attempt_count 归零、审计 OUTBOX_MANUAL_RETRY）",
                             ("消费者收据（outbox_consumer_receipts）按 (event, consumer) 去重；资金效果另有行锁 + 唯一约束保证只发生一次", True)], "note", size=10)
    d.save(path)


def two_databases(path):
    d = Diagram(300, title="两个系统、两个数据库：唯一的接口是签名的 HTTP")
    b = d.group(10, 30, 270, 258, "银行（LedgerCore）", kind="cool")
    d.box(22, 58, 246, 40, ["clearing_instructions", ("READY → SENT", True)], "store", size=10)
    d.box(22, 106, 246, 40, ["clearing_callback_inbox", ("callback_id 与 (operation, sequence) 唯一", True)], "store", size=10)
    d.box(22, 154, 246, 40, ["corporate_payment_intents", ("status 与 external_status 分开记录", True)], "store", size=10)
    d.box(22, 202, 246, 74, ["银行库里没有模拟器的表", ("模拟器的状态只能通过", True), ("签名询价得知", True)], "plain", size=10)
    s = d.group(360, 30, 270, 258, "清算模拟器", kind="warm")
    d.box(372, 58, 246, 40, ["simulator_operations", ("operation_id 唯一；事实不可改", True)], "store", size=10)
    d.box(372, 106, 246, 40, ["simulator_callback_outbox", ("序号递增；8 次投递预算", True)], "store", size=10)
    d.box(372, 154, 246, 74, ["V3/V5：DEAD 或银行已丢失的", ("DELIVERED 回调可被询价重新武装", True), ("（同一 id、序号、签名载荷）", True)], "plain", size=10)
    d.edge((280, 78), (360, 78), "指令/询价")
    d.edge((360, 126), (280, 126), "回调")
    d.para(286, 170, ["HMAC-SHA256", "timestamp.body", "±300 s"], 9.4, C["ink"])
    d.para(372, 262, ["共享同一个 HMAC 密钥（P3：建议", "在签名基串中加入方向标签）"], 9.0)
    d.save(path)


def clearing_states(path):
    d = Diagram(352, title="外部清算状态机（bank legalTransition，V9 触发器同样执行）")
    acc = d.box(24, 66, 120, 40, ["ACCEPTED"], "real")
    pen = d.box(250, 66, 120, 40, ["PENDING"], "real")
    unk = d.box(250, 184, 120, 40, ["UNKNOWN", ("询价推进", True)], "sim")
    set_ = d.box(498, 66, 120, 44, ["SETTLED", ("终态", True)], "good")
    rej = d.box(498, 180, 120, 44, ["REJECTED", ("终态", True)], "bad")
    g, r = C["green"], C["red"]
    d.edge(acc, pen, "")
    d.edge(acc, unk, "", src="b", dst="l", via=[(84, 204)])
    d.edge(pen, unk, "", src="b", dst="t")
    d.edge(pen, set_, "", src=("r", 0.4), dst=("l", 0.36), color=g)
    d.edge(unk, rej, "", src=("r", 0.6), dst=("l", 0.6), color=r)
    d.edge(pen, rej, "", src=("r", 0.8), dst=("l", 0.3), via=[(410, 98), (410, 193)], color=r)
    d.edge(unk, set_, "", src=("r", 0.3), dst=("l", 0.8), via=[(450, 196), (450, 101)], color=g)
    d.edge(acc, set_, "", src="t", dst=("t", 0.5), via=[(84, 48), (558, 48)], style="dash", color=g)
    d.para(150, 42, "ACCEPTED 也可直接到 SETTLED 或 REJECTED（虚线）", 9.2)
    d.para(392, 262, ["绿线 → SETTLED，红线 → REJECTED"], 9.2)
    d.box(12, 244, 300, 100, ["银行付款状态（另一个字段）", ("PENDING_CLEARING：已记账，应付仍在", True), ("UNKNOWN：结果不明，应付仍在", True),
                             ("COMPLETED：结算分录已过账（V15 绑定）", True), ("REJECTED：退款分录已过账", True)], "store", size=10)
    d.box(328, 272, 300, 72, ["被拒绝的转换 → CRITICAL 例外，无资金效果", ("SETTLED → REJECTED：ILLEGAL_CLEARING_TRANSITION", True),
                              ("外部 SETTLED ≠ 银行 COMPLETED", True)], "bad", size=10)
    d.save(path)


def journals(path):
    d = Diagram(318, title="记账、结算、退款与流动性划转：四种分录的借贷方")
    rows = [("记账（批准时）", "corporate-payment:<id>:booking", "Dr CUSTOMER_DEPOSIT", "Cr CLEARING_PAYABLE", "客户的钱离开存款，成为银行欠清算系统的应付", "real"),
            ("结算（SETTLED）", "corporate-payment:<id>:settlement", "Dr CLEARING_PAYABLE", "Cr SIMULATION_RESERVE", "应付清零，准备金减少；不足则拒绝", "good"),
            ("退款（REJECTED）", "corporate-payment:<id>:refund", "Dr CLEARING_PAYABLE", "Cr CUSTOMER_DEPOSIT", "仅当没有结算时；钱回到客户存款", "bad"),
            ("流动性划转", "reserve-liquidity:<uuid>:OUT|IN", "Dr 收方资产", "Cr 付方资产", "仅实验命令；R 与 T 之间平衡移动", "sim")]
    y = 38
    for name, ref, dr, cr, note, kind in rows:
        d.box(12, y, 196, 58, [name, (ref, True)], kind, size=9.6)
        d.box(216, y, 150, 28, [dr], "plain", size=9.4, bold=False)
        d.box(216, y + 30, 150, 28, [cr], "plain", size=9.4, bold=False)
        d.box(374, y, 254, 58, [note], "plain", size=9.4, bold=False)
        y += 66
    d.para(14, 312, "每种分录恰好两行过账、金额相等；V8/V13 约束触发器核对引用格式、账户种类与付款状态。", 9.2)
    d.save(path)


def timeout_unknown(path):
    sequence(path, ["银行派发器", "模拟器", ("银行数据库", "store")], [
        ("band", "提交超时（连接 2 s / 读取 5 s）", "zone"),
        ("msg", 0, 1, "签名提交 operation_id = X（超时）"),
        ("msg", 0, 2, "工作项失败：按退避重试，状态与应付都不变"),
        ("msg", 0, 1, "重试：同一 operation_id = X（模拟器按身份幂等）"),
        ("end",),
        ("note", 1, 1, "SIM-UNKNOWN：模拟器自己也无法确定结果", "sim"),
        ("msg", 1, 0, "回调 UNKNOWN（序号 1）", "async"),
        ("msg", 0, 2, "payment → UNKNOWN；应付保持；不退款"),
        ("band", "错误做法（本系统不做）", "warm"),
        ("note", 0, 2, "把超时或未知当成拒绝并退款 → 若对方其实已结算，钱被付两次", "bad"),
        ("end",),
        ("msg", 0, 1, "运营询价：同一 operation_id = X"),
        ("msg", 1, 0, "回调 SETTLED（序号 2）", "async"),
        ("msg", 0, 2, "结算分录一次；COMPLETED"),
    ], title="超时与未知都不等于拒绝：用原操作身份重试或询价，而不是换身份重发或退款", number=True)


def hmac_sequence(path):
    d = Diagram(352, title="回调的认证、去重与排序：每一种异常都有确定的处理")
    d.box(12, 36, 616, 44, ["签名：HMAC-SHA256(key, X-Clearing-Timestamp + \".\" + 原始请求体)；时间偏差 > 300 s 拒绝；常量时间比较"], "real", size=10)
    cases = [("篡改、缺头、过期签名", "401，什么都不记录", "bad"),
             ("同一 callback_id 原样重放", "200 DUPLICATE，无新效果", "good"),
             ("同一 callback_id、内容不同", "CLEARING_CALLBACK_ID_REUSE · CRITICAL", "bad"),
             ("同一 (operation, sequence)、不同回调", "CLEARING_SEQUENCE_CONFLICT · CRITICAL", "bad"),
             ("序号跳号（先到 3，缺 2）", "CLEARING_CALLBACK_OUT_OF_ORDER · HIGH", "sim"),
             ("摘要与批准意图不符", "CLEARING_INTENT_MISMATCH · CRITICAL", "bad"),
             ("SETTLED 之后又来 REJECTED", "ILLEGAL_CLEARING_TRANSITION · CRITICAL", "bad")]
    y = 92
    for left, right, kind in cases:
        d.box(12, y, 280, 28, [left], "plain", size=9.8, bold=False)
        d.box(302, y, 326, 28, [right], kind, size=9.8, bold=False)
        y += 32
    d.para(14, 344, "ClearingCallbackLifecyclePostgresIntegrationTests 在真实 PostgreSQL 上逐条执行并断言恰好这五类例外。", 9.2)
    d.save(path)


def r401_before(path):
    sequence(path, ["模拟器", "回调控制器", "Spring 事务", ("PostgreSQL", "store")], [
        ("msg", 0, 1, "签名回调 SETTLED"),
        ("msg", 1, 3, "收件箱 + external_status = SETTLED"),
        ("msg", 2, 3, "COMMIT"),
        ("self", 2, "afterCompletion 中调用\nAFTER_COMMIT 监听器"),
        ("note", 2, 3, "结算抛异常（或进程在此被杀）", "inject"),
        ("note", 1, 2, "invokeAfterCompletion 记录日志后吞掉异常", "sim"),
        ("msg", 1, 0, "200 ACCEPTED", "reply"),
        ("self", 0, "标记 DELIVERED，\n不再投递"),
        ("note", 0, 3, "修复前：付款停在 PENDING_CLEARING/SETTLED，无结算分录、无工作项、无对账发现", "bad"),
    ], title="R4-01 修复前（ba36f90 的行为，复现于插桩提交 94a2978）：回调已确认，结算却丢失", number=True)


def r401_after(path):
    sequence(path, ["模拟器", "回调控制器", ("PostgreSQL", "store"), "效果处理器"], [
        ("msg", 0, 1, "签名回调 SETTLED"),
        ("band", "同一事务（一次 COMMIT）", "cool"),
        ("msg", 1, 2, "收件箱 + external_status"),
        ("msg", 1, 2, "工作项 clearing.callback.accepted:<callbackId>"),
        ("end",),
        ("msg", 1, 3, "提交后快速路径 tryApplyNow（失败只记 WARN）"),
        ("msg", 3, 2, "REQUIRES_NEW：锁付款行；未结算才过账"),
        ("msg", 1, 0, "200 ACCEPTED", "reply"),
        ("band", "快速路径失败或进程崩溃时", "warm"),
        ("msg", 2, 3, "Outbox 派发工作项 → applyOwedEffect"),
        ("msg", 3, 2, "APPLIED 一次；重复到达 → ALREADY_APPLIED"),
        ("end",),
        ("note", 1, 2, "对账：终态外部结果缺本地分录 → CLEARING_FINANCIAL_EFFECT_PENDING", "note"),
    ], title="R4-01 修复后（d529208）：确认之前，欠下的效果已成为持久工作", number=True)


def effect_queue(path):
    d = Diagram(318, title="欠下的资金效果如何被看见：工作项、例外与严重度")
    w = d.box(12, 40, 190, 64, ["工作项 PENDING/PROCESSING", ("自动重试中（≤ 5 次）", True)], "real", size=10)
    dead = d.box(12, 130, 190, 54, ["工作项 DEAD", ("需要受治理的重试", True)], "bad", size=10)
    ok = d.box(12, 210, 190, 54, ["工作项 DELIVERED", ("效果已过账或确认已存在", True)], "good", size=10)
    d.edge(w, dead, "5 次失败", src="b", dst="t")
    d.edge(dead, ok, "OPERATIONS 重试", src="b", dst="t")
    r = d.box(240, 40, 388, 76, ["对账 CLEARING_FINANCIAL_EFFECT_PENDING", ("HIGH：仍有自动重试，且最终结果到达不足 60 s", True),
                                ("CRITICAL：没有待执行的重试，或已超过 60 s", True)], "sim", size=10)
    m = d.box(240, 130, 388, 54, ["掩盖检查：工作项 DELIVERED 但效果仍欠", ("评分器字段 deliveredWorkMaskingOwedEffect 必须为 0", True)], "bad", size=10)
    g = d.box(240, 198, 388, 66, ["运营界面与指标", ("Owed settlements/refunds（数量、最早时间）", True),
                                 ("ledgercore_clearing_effects_pending、…_oldest_pending_age_seconds", True)], "store", size=10)
    d.edge(w, r, "", src="r", dst="l")
    d.para(14, 290, ["关闭例外不能靠一句备注：resolve 先重新检查（STATE_DERIVED 类别重跑 inspect，模拟器类别做签名询价），",
                     "仍然欠着就返回 409。"], 9.4, C["ink"])
    d.save(path)


def reserve(path):
    sequence(path, ["运营/实验命令", ("账本", "store"), "效果处理器", "对账"], [
        ("msg", 0, 1, "流动性划转 OUT：准备金只剩 50.00（受限实验命令）"),
        ("note", 1, 1, "Dr TREASURY / Cr RESERVE，平衡、可审计", "store"),
        ("msg", 2, 1, "结算 80.00：准备金不足 → 拒绝（不透支）"),
        ("msg", 2, 3, "CLEARING_RESERVE_INSUFFICIENT · CRITICAL（去重）"),
        ("note", 2, 2, "工作项 1/2/4/8 s 重试后 DEAD；应付 80.00 仍在", "sim"),
        ("msg", 0, 3, "尝试关闭例外 → 409（复核仍欠）"),
        ("msg", 0, 1, "流动性划转 IN 100.00（审计 LAB_RESERVE_LIQUIDITY_TRANSFER）"),
        ("msg", 0, 2, "两名运营同时受治理重试 → [200, 400]"),
        ("msg", 2, 1, "结算一次：准备金 150.00 − 80.00 = 70.00"),
        ("msg", 0, 3, "关闭两个例外 → 200（复核通过）"),
    ], title="R4-02：真实的准备金不足、补足与一次受治理的重试", number=True)


def lost_callback(path):
    sequence(path, ["模拟器", "银行", "运营"], [
        ("msg", 0, 1, "回调 1 ACCEPTED（已收到）"),
        ("note", 1, 1, "银行停机，超过模拟器 8 次投递预算（约 254 s）", "inject"),
        ("msg", 0, 1, "回调 2 PENDING、3 SETTLED：投递失败 → DEAD"),
        ("band", "银行恢复", "cool"),
        ("msg", 2, 1, "对账：CLEARING_CALLBACK_MISSING"),
        ("msg", 1, 0, "签名询价（afterSequence = 银行持有的最大序号）"),
        ("self", 0, "重新武装 DEAD（或序号更大的 DELIVERED）回调：\n同一 id、序号、载荷；redelivery_count+1"),
        ("msg", 0, 1, "按序重投 2、3"),
        ("note", 1, 1, "结算一次；关闭例外需复核", "good"),
        ("end",),
    ], title="R4-04/R4-07：回调丢失（或银行回到备份时点）后的重投", number=True)


ALL = {
    "fig-maker-checker": maker_checker, "fig-digest": digest, "fig-payroll-claim": payroll_claim,
    "fig-payroll-crash": payroll_crash, "fig-outbox": outbox, "fig-two-db": two_databases,
    "fig-clearing-states": clearing_states, "fig-journals": journals, "fig-timeout-unknown": timeout_unknown,
    "fig-hmac-sequence": hmac_sequence, "fig-r401-before": r401_before, "fig-r401-after": r401_after,
    "fig-effect-queue": effect_queue, "fig-reserve": reserve, "fig-lost-callback": lost_callback,
}
