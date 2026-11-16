"""Diagrams and data figures for Parts V-VI (fault lab, reconciliation, security, observability, performance, recovery,
full stack, E12). Data figures read their numbers from the committed evidence files at generation time; a missing file
fails the build instead of drawing stale or invented numbers."""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from charts import C, tw
from diagrams import Diagram, sequence

ROOT = Path(__file__).resolve().parents[3]
EV = ROOT / "evidence"


def load(rel: str):
    path = EV / rel
    if not path.exists():
        raise FileNotFoundError(f"evidence file missing: {path}")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def ts(s: str) -> datetime:
    s = s.replace("Z", "+00:00")
    if "." in s:
        head, rest = s.split(".", 1)
        frac = "".join(ch for ch in rest if ch.isdigit())[:6]
        tz = rest[len("".join(ch for ch in rest if ch.isdigit())):]
        s = f"{head}.{frac}{tz}"
    return datetime.fromisoformat(s)


def e6_lab(path):
    d = Diagram(318, title="故障注入实验室：真实进程、真实检查点、精确的杀进程与独立评分")
    g = d.group(10, 30, 300, 196, "被测银行 JVM（e6-lab 配置）", kind="zone")
    d.box(22, 56, 276, 44, ["启用需同时满足：profile e6-lab、开关为 true、", ("LEDGERCORE_E6_CONFIRM=DISPOSABLE_ONLY、库名白名单", True)], "plain", size=9.6)
    d.box(22, 108, 276, 44, ["BlockingCheckpoint：到达故障点", ("写 checkpoint.json（PID、故障点、事实）后阻塞", True)], "inject", size=9.6)
    d.box(22, 160, 276, 54, ["普通启动不注册任何钩子", ("LedgerCoreApplicationTests 断言", True), ("faultInjectionHookIsAbsent…", True)], "good", size=9.6)
    d.group(330, 30, 300, 196, "实验 harness（PowerShell）", kind="warm")
    k = d.box(342, 56, 276, 44, ["等待检查点；核对 PID、端口、JAR 哈希", ("Stop-Process -Force：杀的正是那个进程", True)], "inject", size=9.6)
    s = d.box(342, 108, 276, 44, ["独立 SQL 评分：不看应用日志、不看 HTTP 码", ("NOT_INJECTED 永远不算通过", True)], "store", size=9.6)
    r = d.box(342, 160, 276, 54, ["恢复：无故障配置重启、按例执行", ("再次独立评分 → restore.json", True), ("只停止自己启动的进程", True)], "real", size=9.6)
    d.edge((310, 130), (342, 78), "", via=[(326, 130), (326, 78)])
    d.edge(k, s, "", src="b", dst="t")
    d.edge(s, r, "", src="b", dst="t")
    d.box(10, 236, 620, 74, ["每次运行记录：源码 SHA、trackedTreeDirty、两个 JAR 的 SHA-256、一次性数据库与角色、时间线",
                             ("集群 127.0.0.1:55437，库名 ledgercore_e6_<run>；原始目录保留在被忽略的 work/e6-lab/runs/", True),
                             ("E12 实验运行器逐例检查“进程已停、端口已释放”（cleanup 字段）", True)], "note", size=10)
    d.save(path)


def e6_matrix(path):
    pre = load("round4/reproductions/R4-01-prefix-matrix.json")
    post = load("round4/remediation/R4-01-postfix-matrix.json")
    c_pre = load("round4/reproductions/R4-01-C-prefix-session-kill.json")
    lab = load("round4/e12/lab/E12-LAB.json")

    def first(rows, case):
        return next((r for r in rows if r.get("case") == case and r.get("runId")), None)

    cases = [("clearing-callback-commit-crash", "A 回调提交后崩溃"), ("clearing-effect-precommit-crash", "D 结算未提交时崩溃"),
             ("clearing-effect-ack-crash", "E 结算提交后、应答前崩溃"), ("clearing-effect-session-kill", "C 结算会话被 pg_terminate_backend")]
    d = Diagram(276, title=f"同一组崩溃窗口，修复前后的恢复结论（E12 列：{lab['sourceSha'][:7]}）")
    heads = ["崩溃窗口", "修复前 94a2978", "修复后 d529208", f"E12 {lab['sourceSha'][:7]}"]
    xs = [12, 262, 382, 502]
    ws = [242, 112, 112, 126]
    for x, w, h in zip(xs, ws, heads):
        d.box(x, 34, w, 30, [h], "dark", size=10)
    y = 72
    for case, label in cases:
        p = first(pre, case)
        pv = (p or {}).get("recoveryVerdict") if case != "clearing-effect-session-kill" else c_pre.get("recovery")
        q = first(post, case)
        qv = (q or {}).get("recoveryVerdict")
        e = lab.get(case, {}).get("recovery")
        d.box(xs[0], y, ws[0], 32, [label], "plain", size=10, bold=False)
        for x, w, v in zip(xs[1:], ws[1:], [pv, qv, e]):
            kind = "good" if v == "PASS" else "bad" if v else "ghost"
            d.box(x, y, w, 32, [v or "—"], kind, size=10)
        y += 38
    d.para(14, y + 12, ["修复前一列复现于插桩提交 94a2978（行为同 ba36f90）。A 的另一次运行（721bd898）恢复成功，是因为模拟器恰好重投了同一回调；",
                        "修复后 A/B 的“注入后”评分出现 FAIL，是因为持久工作项在快速路径冻结时已完成结算：资金正确，",
                        "所以增加了 A*（派发间隔 60 s）作为确定性的注入证据。所有数值来自 evidence/round4 的评分文件。"], 9.1, C["ink"], lh=14)
    d.save(path)


def reconciliation(path):
    d = Diagram(330, title="对账控制流：独立查询 → 发现 → 例外（去重、升级）→ 复核后才能关闭")
    q = d.box(12, 40, 190, 120, ["ReconciliationQueryService", ("只读、按组织取数", True), ("过账 vs 投影", True), ("转账 vs 回执", True),
                                 ("付款 vs 分录 vs 外部状态", True), ("工资结果 vs 转账", True)], "store", size=9.8)
    f = d.box(226, 40, 190, 120, ["发现（finding）", ("类别、严重度、", True), ("expected / observed 事实", True), ("operationId", True),
                                  ("模拟器类别：签名询价比对", True)], "sim", size=9.8)
    e = d.box(440, 40, 188, 120, ["reconciliation_exceptions", ("同一异常只有一条未关闭", True), ("严重度只升不降", True),
                                   ("（审计 …_ESCALATED）", True), ("不跨组织可见", True)], "real", size=9.8)
    d.edge(q, f, "")
    d.edge(f, e, "upsert")
    d.box(12, 180, 616, 66, ["关闭（resolve）= 重新检查，而不是写一句备注",
                             ("STATE_DERIVED（含 CLEARING_FINANCIAL_EFFECT_PENDING、CLEARING_INSTRUCTION_NOT_DISPATCHED）：重跑 inspect", True),
                             ("SIMULATOR_DERIVED：签名询价 + compareClearing；CLEARING_RESERVE_INSUFFICIENT：付款必须已 COMPLETED 且无欠效果", True)], "good", size=9.8)
    d.box(12, 258, 616, 62, ["复核失败 → 409，例外保持 OPEN；复核通过 → 记录 resolution、correction、category、severity 与 recheck 证据",
                             ("对账代码从不改写历史；修复投影漂移只能从不可变过账重算（第 20 章）", True)], "note", size=9.8)
    d.save(path)


def exception_lifecycle(path):
    d = Diagram(250, title="例外的归属与生命周期")
    o = d.box(12, 60, 130, 50, ["OPEN", ("发现即创建", True)], "sim")
    a = d.box(196, 60, 130, 50, ["ASSIGNED", ("指派给运营成员", True)], "real")
    r = d.box(380, 60, 130, 50, ["RESOLVED", ("复核通过后", True)], "good")
    d.edge(o, a, "assign")
    d.edge(a, r, "resolve")
    d.edge(o, r, "resolve（同样先复核）", src="t", dst="t", via=[(77, 40), (445, 40)], style="dash")
    d.box(530, 52, 98, 66, ["复核失败", ("409", True), ("状态不变", True)], "bad", size=10)
    d.box(12, 136, 616, 102, ["谁能做什么（E8 矩阵逐格执行）",
                              ("只有该组织的 OPERATIONS 成员能查看、指派、解决、询价、重试；其它成员 403，非成员与其它组织 404", True),
                              ("每次解决都写审计：actor、resolution、correction、recheck 证据；并发的对账运行只保留一条未关闭例外", True),
                              ("CRITICAL 例外无法通过点击“Resolve”消失：复核仍欠就失败（R4-03）", True)], "store", size=10)
    d.save(path)


def auth_matrix(path):
    d = Diagram(306, title="E8：执行过的角色 × 路由 × 对象矩阵（12 个主体 × 44 条路由 = 528 格）")
    principals = ["ANONYMOUS", "PERSONAL_CUSTOMER", "ORG_ADMIN", "PAYMENT_MAKER", "PAYMENT_APPROVER", "PAYROLL_MAKER",
                  "PAYROLL_APPROVER", "FINANCE_VIEWER", "OPERATIONS", "SUSPENDED_MEMBER", "CROSS_TENANT_ADMIN",
                  "SUSPENDED_ORG_ADMIN"]
    for i, p in enumerate(principals):
        x = 12 + (i % 4) * 156
        y = 38 + (i // 4) * 40
        d.box(x, y, 148, 32, [p], "plain", size=9.6, bold=False)
    d.box(12, 162, 302, 64, ["每一格：真实 HTTP 请求 + CSRF", ("期望状态码与实际状态码逐一比对", True), ("0 个不一致", True)], "good", size=10)
    d.box(326, 162, 302, 64, ["243 个被拒绝的变更请求", ("事后用 SQL 核对：无分录、无状态变化、无事件", True), ("拒绝必须没有副作用", True)], "real", size=10)
    d.box(12, 236, 616, 60, ["另三项：缺 CSRF 的变更对每个已认证主体都被拒；同时持有 MAKER+APPROVER 也不能批准自己；",
                             ("批准人在提交后被撤销角色，就无法再记账；矩阵结果写入 target/e8-authorization-matrix.json", True)], "note", size=10)
    d.save(path)


def trace_chain(path):
    tr = load("round4/e5-e9/E9-TRACES.json")
    steps = tr["settlementChain"][:7]
    if len(steps) < 7 or not tr.get("chainComplete"):
        raise ValueError("E9-TRACES.json has no complete chain")
    d = Diagram(40 + 7 * 52 + 62, title="一笔结算的真实追踪链（E9 实验运行，仅通过记录的父 ID 与链接拼接）")
    traces = []
    for s in steps:
        if s["traceId"] not in traces:
            traces.append(s["traceId"])
    colors = ["real", "sim", "store"]
    y = 40
    for i, s in enumerate(steps):
        k = colors[traces.index(s["traceId"]) % 3]
        d.box(12, y, 300, 36, [s["name"][:46], (f"{s['system']} · {s['kind']} · {s['status']}", True)], k, size=9.6, lh=13)
        d.box(320, y, 308, 36, [f"trace {s['traceId'][:12]}…  span {s['spanId'][:8]}", (f"parent {(s['parentId'] or '—')[:8]} · {s['durationUs']} µs", True)],
              "plain", size=9.2, bold=False, lh=13)
        if i < 6:
            same = steps[i + 1]["traceId"] == s["traceId"]
            d.edge((162, y + 36), (162, y + 52), None)
            d.text(172, y + 48, "父子" if same else "新 trace（span link）", 8.6, C["ink"] if same else C["red"], weight="600")
        y += 52
    d.para(14, y + 8, [f"框的颜色区分 trace（共 {len(traces)} 个）；“父子”是同一 trace 内的父 span 关系，“新 trace”处由 span link 关联上一段。",
                       f"运行目录 {tr['runDirectory'][:8]}…，付款 {tr['paymentId'][:8]}…；客户端提供的 trace id 从不参与授权。"], 9.2, C["ink"])
    d.save(path)


def alert_timeline(path):
    a = load("round4/e5-e9/E9-ALERT-LAB.json")
    t0 = ts(a["failureCreatedAt"])
    active = {f["alert"]: (ts(f["activeAt"]) - t0).total_seconds() for f in a["firing"]}
    seen = (ts(a["firingAt"]) - t0).total_seconds()
    resolved = (ts(a["resolvedAt"]) - t0).total_seconds()
    d = Diagram(282, title=f"E9 告警实验（Prometheus {a['prometheus']}，{a['sourceSha'][:7]}）：两条告警各自的触发时刻与恢复")
    span = max(resolved * 1.08, 60)
    x0, x1 = 160, 616
    X = lambda s: x0 + (s / span) * (x1 - x0)
    lanes = [(76, "准备金缺口告警", "LedgerCoreClearingReserveShortfall"),
             (114, "欠效果告警", "LedgerCoreClearingEffectPending")]
    for y, name, rule in lanes:
        on = active[rule]
        d.text(14, y + 11, name, 10, C["ink"], weight="600")
        d.text(14, y + 24, rule, 7.4, C["muted"])
        if on > 3:
            d.rect(X(0), y, X(on) - X(0), 24, C["lorange"], rx=3)
        d.rect(X(on), y, X(seen) - X(on), 24, C["lred"], rx=3)
        d.rect(X(seen), y, X(resolved) - X(seen), 24, C["lred"], rx=3, opacity=0.4)
        label_x = X(seen) + 4 if 0 <= X(seen) - X(on) < 14 else X(on) + 4  # keep clear of the dashed marker
        d.text(label_x, y + 16, f"+{on:.1f} s 起 firing", 9, "#7a1f16")
    pending_on = active["LedgerCoreClearingEffectPending"]
    d.text(X(pending_on / 2), 114 + 16, "最早欠效果未满 60 s：规则尚未满足", 9, C["ink"], "middle")
    d.line(x0, 156, x1, 156, C["axis"], 1.2)
    for s in range(0, int(span) + 1, 20):
        d.line(X(s), 152, X(s), 160, C["axis"])
        d.text(X(s), 174, f"{s} s", 9.5, C["muted"], "middle")
    d.text(X(0), 50, "0 s：准备金不足，结算被拒", 9.2, C["ink"], "start")
    d.text(X(seen) - 2, 50, f"+{seen:.1f} s 实验确认两条均 firing", 9.2, C["ink"], "end")
    d.text(X(resolved), 64, f"+{resolved:.1f} s 实验确认两条均已解除", 9.2, C["ink"], "end")
    for s in (0, seen, resolved):
        d.line(X(s), 68, X(s), 156, C["ink"], 1.0, "3 3")
    retry = "一次受治理的死信重试" if "governed" in str(a.get("retryPath")) else "自动重试"
    shortfall = int(a["shortfallWhileFiring"])
    d.para(14, 200, [f"缺口实测 {shortfall} 分（{shortfall / 100:.2f} NZD），欠效果 {a['effectsPendingWhileFiring']} 笔；确认 firing 之后补足准备金，经{retry}结算恰好一次。",
                     "规则：reserve_shortfall_minor > 0；effects_pending > 0 且最早欠效果 > 60 s；都没有等待期，也都没有客户或付款标签。",
                     "深色段：Prometheus 记录的 activeAt 起、到实验确认 firing 为止；浅色段：恢复动作进行中，两条告警在此期间解除，",
                     "各自的解除时刻没有单独记录。对照付款 1.23 NZD 先正常结算，期间无告警；通知投递（Alertmanager）不在范围内。"],
           9.2, C["ink"], lh=16)
    d.save(path)


def query_plans(path):
    d = Diagram(262, title="E3 实测：历史增长与跨组织扫描（本地笔记本，单次计划观测）")
    rows = [("100", 0.8354, 10.9683), ("1,000", 0.8769, 9.9432), ("10,000", 4.9721, 17.2608), ("100,000", 105.5857, 219.9671)]
    d.text(14, 46, "H01：源账户过账数 → 余额聚合与转账 p50 中位数（ms），数据见 evidence/e3/verdicts/H01", 10.2, C["ink"], weight="600")
    mx = 219.9671
    y = 58
    for label, sql, svc in rows:
        d.text(14, y + 16, label, 10, C["muted"])
        d.rect(80, y + 2, max(2, sql / mx * 470), 11, C["blue"], rx=2)
        d.text(84 + sql / mx * 470, y + 12, f"SQL {sql}", 9.2, C["ink"])
        d.rect(80, y + 15, max(2, svc / mx * 470), 11, C["orange"], rx=2)
        d.text(84 + svc / mx * 470, y + 25, f"服务 {svc}", 9.2, C["ink"])
        y += 32
    d.para(14, 198, ["100→100,000：SQL 126.4×、服务 20.1×；重复间差异 > 2×、计划在重复间变化 ⇒ 按预注册规则判 INCONCLUSIVE。",
                     "H04：无关组织增加 50,000 行后，目标批次进度查询扫描 71,500 → 121,500 行（返回仍是 5,000 行），",
                     "计划时间 16.135 → 28.207 ms。R2-P3 的 V10 索引候选未被计划选用、未降低 p50/p95 ⇒ 被否决，V11 向前移除。"], 9.4, C["ink"], lh=16)
    d.save(path)


def pitr(path):
    d = Diagram(226, title="E10（Round 3）PITR：一次本地运行的时间线与实测 RPO/RTO")
    d.line(30, 92, 610, 92, C["axis"], 1.4)
    marks = [(60, "pg_basebackup 3.958 s", "1,379 文件，清单 SHA-256"), (250, "命名恢复点", "之后的 3.11 转账将丢失"),
             (400, "模拟失败边界", "RPO = 118.922 s"), (590, "恢复后首次就绪", "RTO = 670.008 s")]
    for x, a, b in marks:
        d.line(x, 64, x, 100, C["ink"], 1.2)
        anchor = "middle" if 60 < x < 590 else ("start" if x <= 60 else "end")
        d.text(x, 54, a, 9.8, C["ink"], anchor, "600")
        d.text(x, 118, b, 9.4, C["muted"], anchor)
    d.rect(250, 84, 150, 16, C["lred"], rx=2)
    d.rect(400, 84, 190, 16, C["lorange"], rx=2)
    d.para(30, 152, ["期间模拟器保持在线：签名询价把付款推进到 SETTLED 并保存第二个回调；恢复后的银行重放该回调，结算一次。",
                     "这是 Windows + PostgreSQL 18.7 的一次本地运行：不代表生产 RPO/RTO、多主机备份持久性或重复恢复的方差。",
                     "Round 4 的 E12-G02 另做了一次逻辑备份恢复（第 23 章），两者不混为一谈。"], 9.4, C["ink"], lh=16)
    d.save(path)


def restore(path):
    r = load("round4/e12/lab/E12-RESTORE.json")
    m = r["measured"]
    d = Diagram(232, title=f"E12-G02（{r['sourceSha'][:7]}）：银行回到备份时点，模拟器已经向前；如何恢复而不重复记账")
    d.line(30, 112, 610, 112, C["axis"], 1.4)
    pts = [(50, "pg_dump -Fc", "付款仅 ACCEPTED"), (190, "活银行结算", "回调 2、3 已 DELIVERED"), (310, "银行库丢失", f"RPO {m['rpoSeconds']} s"),
           (450, "恢复到新库并重启", f"服务 RTO {m['rtoServiceSeconds']} s"), (590, "对账询价 → 重投", f"结算 RTO {m['rtoReconciledSeconds']} s")]
    for x, a, b in pts:
        d.line(x, 86, x, 120, C["ink"], 1.2)
        anchor = "middle" if 50 < x < 590 else ("start" if x <= 50 else "end")
        d.text(x, 76, a, 9.8, C["ink"], anchor, "600")
        d.text(x, 138, b, 9.3, C["muted"], anchor)
    d.para(30, 172, ["恢复后的银行只看到 ACCEPTED：对账发现 CLEARING_CALLBACK_MISSING，并在签名询价中报告自己持有的最大序号；",
                     "模拟器把序号更大的 DELIVERED 回调重新武装（R4-07，V5 记录 redelivery_count），按序重投；结算恰好一次、记账仍只一次，",
                     "独立评分器的硬门槛字段全部为 0。逻辑备份：不是 PITR、不是异地备份；数值只属于这一次运行。"], 9.4, C["ink"], lh=16)
    d.save(path)


def ops_incident(path):
    sequence(path, ["运营人员", "运营控制台", "银行 API", ("PostgreSQL", "store")], [
        ("msg", 1, 2, "GET metrics / exceptions"),
        ("note", 0, 1, "卡片：欠结算 1、准备金阻塞 1、\n未结清应付 NZ$80.00、失败投递 1", "sim"),
        ("msg", 0, 1, "打开 CRITICAL 例外"),
        ("msg", 0, 2, "resolve（未补足）"),
        ("msg", 2, 3, "复核：仍欠 → 409"),
        ("note", 0, 0, "司库按审计流程补足准备金（实验命令）", "note"),
        ("msg", 0, 2, "受治理重试 DEAD 工作项"),
        ("msg", 2, 3, "结算一次；审计 OUTBOX_MANUAL_RETRY"),
        ("msg", 0, 2, "resolve → 复核通过 → 200"),
        ("note", 0, 3, "没有“强制结算”或任意借贷按钮：所有动作都经后端授权、审计与独立复核", "good"),
    ], title="运营事故流程：从卡片到复核关闭", number=True)


def ci_pipeline(path):
    d = Diagram(300, title="CI：每次推送 main 的精确 SHA 都跑完整验收（.github/workflows/ci.yml）")
    steps = [("npm ci · npm audit（high）", "plain"), ("前端 lint + build", "plain"), ("后端 clean verify（PostgreSQL 服务）", "real"),
             ("模拟器 clean verify（独立库）", "real"), ("启动模拟器与银行", "sim"), ("OpenAPI TypeScript 契约比对", "store"),
             ("Playwright：真实 API 的浏览器旅程", "good")]
    y = 36
    d.group(10, 26, 400, 266, "job: Build, security, and full-stack acceptance", kind="cool")
    for i, (s, k) in enumerate(steps):
        b = d.box(22, y + 8, 376, 28, [s], k, size=10)
        y += 36
    d.group(424, 26, 206, 120, "job: dependency-scan", kind="zone")
    d.box(436, 58, 182, 50, ["OSV 扫描 Maven 依赖", ("漏洞即失败", True)], "bad", size=10)
    d.box(424, 160, 206, 132, ["规则", ("绿色的旧 SHA 不能证明新代码", True), ("冻结时记录 run URL 与 head SHA", True),
                               ("任何跳过的必需步骤 = 不验收", True)], "note", size=10)
    d.save(path)


def e12_grader(path):
    d = Diagram(318, title="E12：独立评分——证据从哪里来，判定如何汇总")
    srcs = [("JUnit XML", "Surefire 自己写出的结果", "real"), ("Playwright JSON", "真实 API 浏览器旅程", "real"),
            ("实验判定文件", "E12-LAB / RESTORE / ALERT / TRACES", "inject"), ("SQL 评分器", "financial-hard-gates.sql，只读", "store")]
    y = 40
    for name, note, kind in srcs:
        d.box(12, y, 220, 46, [name, (note, True)], kind, size=10)
        d.edge((232, y + 23), (276, 150), "", via=[(254, y + 23), (254, 150)])
        y += 56
    c = d.box(276, 118, 160, 64, ["collect", ("cases.json 36 例", True), ("每例的每个来源都要通过", True)], "dark", size=10)
    v = d.box(462, 60, 166, 180, ["判定", ("PASS：全部来源通过", True), ("FAIL：任一失败", True), ("PARTIAL：缺失或跳过", True),
                                  ("NOT_RUN：未执行", True), ("硬门槛或评分器", True), ("任一不过 ⇒ BLOCKED", True)], "good", size=10)
    d.edge(c, v, "")
    d.para(14, 286, ["命令退出码和界面提示从不作为资金正确性的证据；每个来源都记录源码 SHA 与 trackedTreeDirty。"], 9.4, C["ink"])
    d.save(path)


def e12_dimensions(path):
    res = load("round4/e12/E12-RESULTS.json")
    dims = res["dimensions"]
    names = {"financial": "资金正确性", "security": "安全与授权", "async": "异步可靠性", "recovery": "恢复",
             "observability": "可观测性", "fullstack": "全栈行为"}
    order = [k for k in ["financial", "security", "async", "recovery", "observability", "fullstack"] if k in dims]
    d = Diagram(70 + 36 * len(order) + 70, title=f"E12 结果（{res['sourceSha'][:7]}）：按验收维度的通过数")
    mx = max(dims[k]["total"] for k in order)
    y = 44
    for k in order:
        t, p = dims[k]["total"], dims[k]["pass"]
        d.text(14, y + 17, names[k], 10.5, C["ink"])
        d.rect(120, y + 4, t / mx * 400, 20, C["lgray"], rx=3)
        d.rect(120, y + 4, p / mx * 400, 20, C["green"] if p == t else C["orange"], rx=3)
        d.text(126 + t / mx * 400, y + 19, f"{p}/{t}", 10, C["ink"])
        y += 36
    cnt = res["counts"]
    d.para(14, y + 16, [f"共 {cnt['total']} 例：PASS {cnt['pass']}、PARTIAL {cnt['partial']}、FAIL {cnt['fail']}、NOT_RUN {cnt['notRun']}；"
                        f"硬门槛未通过 {cnt['hardGateFailures']}。",
                        "可复现性另由同一 SHA 的 CI 运行与 trackedTreeDirty=false 记录评分（第 26 章）。"], 9.4, C["ink"], lh=16)
    d.save(path)


ALL = {
    "fig-e6-lab": e6_lab, "fig-e6-matrix": e6_matrix, "fig-reconciliation": reconciliation,
    "fig-exception-lifecycle": exception_lifecycle, "fig-auth-matrix": auth_matrix, "fig-trace-chain": trace_chain,
    "fig-alert-timeline": alert_timeline, "fig-query-plans": query_plans, "fig-pitr": pitr, "fig-restore": restore,
    "fig-ops-incident": ops_incident, "fig-ci": ci_pipeline, "fig-e12-grader": e12_grader, "fig-e12-dimensions": e12_dimensions,
}
