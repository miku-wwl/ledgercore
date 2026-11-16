"""Generate appendix I (the E12 case catalogue) from committed acceptance material only.

  python manual/v2/tools/gen_e12_catalogue.py      # -> manual/v2/chapters/35-appendix-i-e12-cases.md

Inputs: assessment/e12/cases.json (must be identical to the frozen commit named in manual/v2/metadata.yaml) and
evidence/round4/e12/E12-RESULTS.json (the official collect run; its sourceSha must be the frozen commit). Every
number in the appendix is counted here; nothing is typed by hand. The run fails if a case and its result disagree.
"""
from __future__ import annotations

import json
import re
import subprocess
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
V2 = ROOT / "manual" / "v2"
OUT = V2 / "chapters" / "35-appendix-i-e12-cases.md"
CATEGORY = {"A": "账本与内部转账", "B": "企业身份与 Policy B", "C": "工资", "D": "持久事件", "E": "清算", "F": "对账",
            "G": "备份与恢复", "H": "界面与运营"}
DIMENSION = {"financial": "资金正确性", "security": "安全与授权", "async": "异步可靠性", "recovery": "恢复",
             "observability": "可观测性", "fullstack": "全栈行为"}
KIND_ORDER = ["JUnit 测试", "浏览器旅程", "实验判定", "SQL 评分器"]
MD_SPECIAL = re.compile(r"""([\\`*_{}\[\]<>#+!|~^$@'"])""")


def literal(text: str) -> str:
    """Escape Markdown punctuation so pandoc prints the text exactly as written: no smart quotes, emphasis or links."""
    return MD_SPECIAL.sub(r"\\\1", text)


def kind(source: str) -> str:
    if source.startswith("grader:"):
        return "SQL 评分器"
    if "::" in source:
        return "实验判定"
    if ".spec.ts#" in source:
        return "浏览器旅程"
    if re.match(r"^nz\.ac\.ledgercore\.", source):
        return "JUnit 测试"
    raise SystemExit(f"unknown evidence source kind: {source}")


def main() -> int:
    meta = (V2 / "metadata.yaml").read_text(encoding="utf-8")
    sha = re.search(r'^source-sha:\s*"([0-9a-f]{40})"', meta, re.M).group(1)
    diff = subprocess.run(["git", "-C", str(ROOT), "diff", "--quiet", sha, "--", "assessment/e12/cases.json"])
    if diff.returncode != 0:
        raise SystemExit("assessment/e12/cases.json differs from the frozen commit")
    cases = json.loads((ROOT / "assessment/e12/cases.json").read_text(encoding="utf-8"))["cases"]
    results = json.loads((ROOT / "evidence/round4/e12/E12-RESULTS.json").read_text(encoding="utf-8"))
    if results["sourceSha"] != sha or results["trackedTreeDirty"]:
        raise SystemExit("E12-RESULTS.json is not a clean run of the frozen commit")
    by_id = {r["id"]: r for r in results["cases"]}
    if sorted(by_id) != sorted(c["id"] for c in cases):
        raise SystemExit("cases.json and E12-RESULTS.json list different cases")

    lines = [
        "# 附录 I　E12 用例目录 {#app-e12-cases}",
        "",
        f"本附录由 `manual/v2/tools/gen_e12_catalogue.py` 从两份已提交的文件生成：用例定义 `assessment/e12/cases.json`"
        f"（与冻结提交 `{sha[:7]}` 完全一致），以及正式评估的汇总结果 `evidence/round4/e12/E12-RESULTS.json`（运行 "
        f"`{results['runId'][:8]}`，纳入 {len(results['included'])} 次运行，源码 `{results['sourceSha'][:7]}`，"
        f"工作区干净）。没有任何手工填写的数字。用例标题保留 `cases.json` 的英文原文，便于在仓库中搜索。",
        "",
        "“证据”一行按来源类型计数：JUnit 测试（后端与模拟器的 Surefire 报告）、浏览器旅程（Playwright 报告）、"
        "实验判定（故障实验、恢复与告警实验写出的判定文件）以及独立 SQL 评分器在每个被纳入的运行数据库上的结果。"
        "一个用例只有在它的**每一个**来源都通过时才是 PASS；硬门槛用例失败，整个 E12 就是 BLOCKED（第 26 章）。",
        "",
    ]
    totals = Counter()
    for letter, name in CATEGORY.items():
        group = [c for c in cases if c["category"].startswith(letter + " ")]
        if not group:
            continue
        hard = sum(1 for c in group if c.get("hardGate"))
        lines += [f"## {letter}　{name}", "", f"{len(group)} 个用例，其中硬门槛 {hard} 个。", ""]
        for case in group:
            result = by_id[case["id"]]
            if result["hardGate"] != bool(case.get("hardGate")) or result["dimension"] != case["dimension"]:
                raise SystemExit(f"{case['id']}: definition and result disagree")
            evidence = result["evidence"]
            counts = Counter(kind(e["source"]) for e in evidence)
            passed = sum(1 for e in evidence if e["outcome"] == "PASS")
            if result["verdict"] == "PASS" and passed != len(evidence):
                raise SystemExit(f"{case['id']}: PASS with a source that did not pass")
            totals["cases"] += 1
            totals["sources"] += len(evidence)
            totals[result["verdict"]] += 1
            parts = " · ".join(f"{k} {counts[k]}" for k in KIND_ORDER if counts[k])
            gate = "硬门槛" if case.get("hardGate") else "非硬门槛"
            status = "全部通过" if passed == len(evidence) else f"{passed} 项通过"
            lines += [
                "::: e12-case",
                f"**{case['id']}**　{gate} · {DIMENSION[case['dimension']]} · **{result['verdict']}**\\",
                f"{literal(case['title'])}\\",
                f"证据 {len(evidence)} 项，{status}：{parts}",
                ":::",
                "",
            ]
    lines += [
        "## 汇总",
        "",
        f"{totals['cases']} 个用例，{totals['PASS']} 个 PASS；共核对 {totals['sources']} 个证据来源。"
        "同一个来源（例如评分器对某次运行数据库的结果）可以计入多个用例。",
        "",
    ]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {totals['cases']} cases, {totals['PASS']} PASS, {totals['sources']} sources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
