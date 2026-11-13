from pathlib import Path
p=Path('manual/tools/make_figures.py')
s=p.read_text(encoding='utf-8-sig')
anchor='"14-unknown-result.svg": ("服务端提交与浏览器收到响应并不原子", ['
addition='''"14-react-state.svg": ("React 页面把本地输入与服务端数据分开", [
    ("Dashboard / Transfer", "组件组织\n页面交互", "blue"),
    ("本地表单状态", "账户选择 · 金额\n确认 · pending 意图", "gold"),
    ("TanStack Query", "accounts / history\n读取缓存", "purple"),
    ("Typed API", "OpenAPI 类型\nHTTP 会话", "blue"),
    ("LedgerCore", "授权 · 事务\n权威回执", "green"),
]),
'''
if anchor not in s: raise SystemExit('diagram insertion anchor missing')
p.write_text(s.replace(anchor, addition+anchor), encoding='utf-8')
print('Added React component-state SVG.')
