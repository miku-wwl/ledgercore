import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "manual/reference/code-excerpts.json"
OUT_JSON = ROOT / "manual/reference/source-excerpts.json"
OUT_MD = ROOT / "manual/reference/source-excerpts.md"

config = json.loads(CONFIG.read_text(encoding="utf-8-sig"))
commit = config["sourceCommit"]
repository = config["repository"].rstrip("/")
items = []
for spec in config["excerpts"]:
    raw = subprocess.check_output(
        ["git", "show", f"{commit}:{spec['path']}"], cwd=ROOT, text=True, encoding="utf-8"
    )
    lines = raw.splitlines()
    start = next((i for i, line in enumerate(lines) if line.lstrip().startswith(spec["start"].lstrip())), None)
    if start is None:
        raise SystemExit(f"start marker not found: {spec['id']} ({spec['start']})")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].lstrip().startswith(spec["end"].lstrip())), None)
    if end is None:
        raise SystemExit(f"end marker not found: {spec['id']} ({spec['end']})")
    excerpt = "\n".join(lines[start:end]).rstrip()
    start_line, end_line = start + 1, end
    url = f"{repository}/blob/{commit}/{spec['path']}#L{start_line}-L{end_line}"
    items.append({**spec, "startLine": start_line, "endLine": end_line, "code": excerpt, "url": url})

OUT_JSON.write_text(json.dumps({"sourceCommit": commit, "excerpts": items}, ensure_ascii=False, indent=2), encoding="utf-8")
parts = [f"# 固定源码摘录\n\n源码提交：`{commit}`。以下代码均由工具从该 Git 对象读取，行号和链接按该对象计算。\n"]
for item in items:
    parts.append(f"## {item['id']}\n\n`{item['path']}:{item['startLine']}-{item['endLine']}` · [固定提交源码]({item['url']})\n\n```text\n{item['code']}\n```\n")
OUT_MD.write_text("\n".join(parts), encoding="utf-8")
print(f"Extracted {len(items)} fixed-commit excerpts to manual/reference/source-excerpts.md")
for item in items:
    print(f"{item['id']}: {item['path']}:{item['startLine']}-{item['endLine']}")
