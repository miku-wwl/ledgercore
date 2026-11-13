from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIG = ROOT / "manual/figures"
FIG.mkdir(parents=True, exist_ok=True)

DIAGRAMS = {
"01-transfer-flow.svg": ("一笔转账从点击到确认", [
    ("用户意图", "付款方 · 收款方\n金额 + 原幂等键", "blue"),
    ("应用入口", "Session · CSRF\n账户所有权", "blue"),
    ("转账用例", "锁定 · 校验\n凭证 · 成功回执", "gold"),
    ("PostgreSQL", "单事务提交\n触发器检查", "green"),
    ("可恢复结果", "同键返回原回执\n不会重复记账", "green"),
]),
"02-debit-credit.svg": ("一个事件，两侧等额分录", [
    ("付款负债账户", "借方 2,500 分\n客户存款减少", "red"),
    ("同一凭证", "总借方 = 总贷方\n币种均为 NZD", "gold"),
    ("收款负债账户", "贷方 2,500 分\n客户存款增加", "green"),
]),
"03-invariants.svg": ("不变量按它能观察的事实分层", [
    ("Money", "正整数分币\n规范 NZD 字符串", "blue"),
    ("JournalEntry", "至少两条分录\n借贷相等", "gold"),
    ("TransferService", "所有权 · 余额\n幂等 · 事务", "purple"),
    ("PostgreSQL", "唯一键 · 外键\n提交点完整性", "green"),
]),
"04-modules.svg": ("模块化单体以业务能力划分边界", [
    ("identity", "会话身份\n认证主体", "purple"),
    ("accounts", "客户账户\n所有权 · 状态", "blue"),
    ("transfers", "用例协调\n原子写入", "gold"),
    ("ledger", "凭证 · Posting\n余额投影", "green"),
    ("history / reconcile", "只读视图\n查询 · 对账", "blue"),
]),
"05-http-lifecycle.svg": ("HTTP 请求进入应用的顺序", [
    ("浏览器", "Cookie + CSRF\nJSON + Idempotency-Key", "blue"),
    ("Security", "身份认证\nCSRF 验证", "purple"),
    ("Spring MVC", "解析 · 校验\nprincipal 注入", "blue"),
    ("Controller", "金额解析\n调用转账用例", "gold"),
    ("HTTP 响应", "Receipt 或\nProblem Details", "green"),
]),
"06-jpa-lifecycle.svg": ("JPA 状态变更与版本检查", [
    ("读取", "Repository 查找\n实体进入上下文", "blue"),
    ("领域转换", "验证新状态\n保留账户身份", "gold"),
    ("脏检查 / flush", "生成 UPDATE\n版本号参与条件", "purple"),
    ("PostgreSQL", "影响 0 行时\n报告并发冲突", "green"),
]),
"07-journal-schema.svg": ("账本表用外键连接经济事件", [
    ("journal_entries", "一次经济事件\n币种 · 类型 · 时间", "blue"),
    ("postings", "分录归属凭证\n正数分币 · 方向", "gold"),
    ("ledger_accounts", "客户存款 / 储备\n正常余额方向", "purple"),
    ("balance_projection", "按账本账户\n快速读取余额", "green"),
]),
"08-mvcc.svg": ("READ COMMITTED 的新命令看到新快照", [
    ("初始提交版本", "余额 100.00\nT1 / T2 开始", "blue"),
    ("T1 锁账户行", "余额检查后\n扣出 80.00", "gold"),
    ("T1 提交", "新版本可见\n余额变为 20.00", "green"),
    ("T2 等待后读取", "后续命令取新快照\n拒绝再付 80.00", "red"),
]),
"09-concurrent-transfers.svg": ("竞争写入必须在同一资源上排队", [
    ("T1：转出 80", "等待付款账户锁", "blue"),
    ("账户锁", "同一账本行\n只允许顺序进入", "gold"),
    ("T2：转出 80", "锁后再读余额\n不会用旧值扣款", "purple"),
    ("事务结论", "成功总额 ≤ 100\n另一笔资金不足", "green"),
]),
"10-lock-order.svg": ("统一账户排序，避免交叉锁环", [
    ("事务 T1", "需要账户 A 与 B", "blue"),
    ("全序规则", "按 UUID 由小到大\n所有请求相同", "gold"),
    ("锁定顺序", "A → B\n或 B → A 请求也同序", "purple"),
    ("等待图", "单向等待\n不形成 A↔B 环", "green"),
]),
"10-rollback.svg": ("原子提交：所有写入一起成功或撤销", [
    ("请求开始", "保留键 · 取得锁\n校验余额", "blue"),
    ("写凭证", "JournalEntry\n两个 Posting", "gold"),
    ("更新读模型", "余额投影\nTransfer · Receipt", "purple"),
    ("提交检查", "借贷平衡\n状态约束", "green"),
    ("任一失败", "整个事务回滚\n无半笔转账", "red"),
]),
"11-idempotency.svg": ("同一意图的重试返回同一回执", [
    ("首次请求", "客户 + 操作 + 键\n保存请求指纹", "blue"),
    ("唯一键仲裁", "首次取得意图\n后续请求等待", "gold"),
    ("原子提交", "凭证 · 投影\nTransfer · 回执", "green"),
    ("相同指纹", "返回原回执\n不再生成新凭证", "purple"),
]),
"12-projection-reconciliation.svg": ("投影服务读取，账本支持复算", [
    ("Append-only Posting", "权威历史\n按正常方向求和", "blue"),
    ("同一事务", "写分录并\n重建投影", "gold"),
    ("余额投影", "仪表盘快速读取\n非第二份真相", "purple"),
    ("Reconciliation", "独立比较两者\n差异触发调查", "green"),
]),
"13-security-boundary.svg": ("身份、CSRF 与对象权限回答不同问题", [
    ("Session", "谁在发请求？\n服务端 principal", "purple"),
    ("CSRF", "请求是否带有\n会话所需 token？", "blue"),
    ("所有权查询", "是否拥有这个\n源账户和资源？", "gold"),
    ("业务执行", "允许后才校验\n状态与余额", "green"),
]),
"14-react-state.svg": ("React 页面把本地输入与服务端数据分开", [
    ("Dashboard / Transfer", "组件组织\n页面交互", "blue"),
    ("本地表单状态", "账户选择 · 金额\n确认 · pending 意图", "gold"),
    ("TanStack Query", "accounts / history\n读取缓存", "purple"),
    ("Typed API", "OpenAPI 类型\nHTTP 会话", "blue"),
    ("LedgerCore", "授权 · 事务\n权威回执", "green"),
]),
"14-unknown-result.svg": ("服务端提交与浏览器收到响应并不原子", [
    ("浏览器请求", "保存意图与\n原 Idempotency-Key", "blue"),
    ("服务端提交", "账本与成功回执\n已在数据库提交", "green"),
    ("网络断开", "响应未到达\n结果对用户未知", "red"),
    ("刷新 / 核实", "sessionStorage 恢复\n原键重试", "gold"),
    ("同一回执", "返回原 transferId\n确认成功", "purple"),
]),
"15-react-query.svg": ("缓存失效刷新视图，不改变账务事实", [
    ("Query cache", "accounts / history\n按 key 保存读取", "blue"),
    ("Mutation", "提交转账\n携带原幂等键", "gold"),
    ("服务端回执", "成功确定后\n返回 TransferReceipt", "green"),
    ("失效相关 key", "重取余额与历史\n同步页面视图", "purple"),
]),
"16-form-recovery.svg": ("表单状态必须保留用户的原始意图", [
    ("输入", "来源 / 目标 / 金额\n校验两位小数", "blue"),
    ("确认", "清楚展示两账户\n和总金额", "gold"),
    ("提交", "sessionStorage 先存\n意图 + UUID 键", "purple"),
    ("明确成功 / 拒绝", "回执确认后清除\n可重建新意图", "green"),
    ("未知", "重载同标签页\n仅用原键核实", "red"),
]),
"17-query-index.svg": ("索引由过滤、连接、排序与限制共同决定", [
    ("查询形状", "账户 + 时间游标\n排序 created_at, id", "blue"),
    ("可选索引", "source / target\nPostings account id", "purple"),
    ("执行计划", "Bitmap / index scan\n取决于统计信息", "gold"),
    ("受限结果", "Join · sort · LIMIT\n检查 buffers / rows", "green"),
]),
"18-test-pyramid.svg": ("每一层测试证明不同事实", [
    ("单元测试", "金额解析\n领域不变量", "blue"),
    ("PostgreSQL 集成", "事务 · 约束\n并发 · 回滚", "gold"),
    ("HTTP 集成", "会话 · CSRF\n错误与所有权", "purple"),
    ("Playwright", "真实浏览器\n刷新与恢复路径", "green"),
]),
"19-cross-stack.svg": ("一条需求需要全栈共同兑现", [
    ("用户需要", "安全查看\n交易对方与状态", "blue"),
    ("领域 / SQL", "账户视角\n稳定读模型", "gold"),
    ("REST / OpenAPI", "合同与授权\n可生成类型", "purple"),
    ("React", "清晰呈现\n不泄露账户 ID", "green"),
    ("测试 / 文档", "正向 + 越权\n证据与限制", "red"),
]),
"20-end-to-end.svg": ("可靠结果由多个可验证属性串联", [
    ("精确金额", "整数分币", "blue"),
    ("守恒", "借贷相等", "gold"),
    ("并发", "锁后校验", "purple"),
    ("原子性", "同一提交", "green"),
    ("可恢复", "幂等回执", "red"),
]),
}
COLORS = {
    "blue": ("#EAF2FF", "#2D5D9F"), "purple": ("#F1EDFF", "#6551A8"),
    "gold": ("#FFF3DB", "#A86B13"), "green": ("#E8F5EF", "#2F7A55"),
    "red": ("#FFF0EE", "#B24D42"),
}

def svg_text(x, y, text, size, color, weight="400", anchor="middle"):
    lines = text.splitlines() or [text]
    if len(lines) == 1:
        content = escape(lines[0])
    else:
        content = "".join(f'<tspan x="{x}" dy="{0 if index == 0 else 1.5}em">{escape(line)}</tspan>' for index, line in enumerate(lines))
    return f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="Microsoft YaHei, Noto Sans CJK SC, sans-serif" font-size="{size}" font-weight="{weight}" fill="{color}">{content}</text>'
for filename, (title, nodes) in DIAGRAMS.items():
    width, height = 1040, 500
    left, right = 42, 42
    gap = 22
    count = len(nodes)
    node_width = min(184, (width-left-right-gap*(count-1)) / count)
    y, node_height = 190, 170
    total = count*node_width + (count-1)*gap
    x0 = (width-total)/2
    pieces = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
      '<rect width="100%" height="100%" rx="24" fill="#FBFCFE"/>',
      '<path d="M40 104 H1000" stroke="#E7EBF2" stroke-width="1"/>',
      svg_text(52, 68, title, 27, "#17263C", "700", "start"),
      svg_text(52, 92, "LEDGERCORE · 机制图", 12, "#66758B", "600", "start")]
    for i, (label, body, color) in enumerate(nodes):
        x = x0 + i*(node_width+gap)
        fill, stroke = COLORS[color]
        if i < count-1:
            ax1, ax2 = x+node_width+4, x+node_width+gap-7
            ay = y+node_height/2
            pieces.append(f'<path d="M{ax1:.1f} {ay:.1f} H{ax2:.1f}" stroke="#8B98AB" stroke-width="2" marker-end="url(#arrow)"/>')
        pieces.append(f'<rect x="{x:.1f}" y="{y}" width="{node_width:.1f}" height="{node_height}" rx="18" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
        pieces.append(svg_text(x+node_width/2, y+46, label, 17, stroke, "700"))
        lines = body.split("\\n")
        for j, line in enumerate(lines):
            pieces.append(svg_text(x+node_width/2, y+88+j*26, line, 13, "#39485E"))
        pieces.append(f'<circle cx="{x+node_width/2:.1f}" cy="{y+node_height-18}" r="4" fill="{stroke}"/>')
    pieces.extend([svg_text(52, 438, "同一过程的职责边界与可观察事实", 13, "#53657B", "400", "start"),
        '<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#8B98AB"/></marker></defs>',
        '</svg>'])
    (FIG / filename).write_text("\n".join(pieces), encoding="utf-8")

captions = ["# 编辑矢量机制图", "", "以下 SVG 均为本教材原创、可用 Inkscape、Illustrator 或文本编辑器继续修改。图形表达 LedgerCore 固定源码中的机制与边界，不是架构全景或生产部署拓扑。", ""]
for i, name in enumerate(DIAGRAMS, 1):
    title = DIAGRAMS[name][0]
    captions.append(f"- 图 {i:02d}：{title}（`figures/{name}`）")
(FIG / "README.md").write_text("\n".join(captions)+"\n", encoding="utf-8")
print(f"Wrote {len(DIAGRAMS)} editable SVG diagrams")
