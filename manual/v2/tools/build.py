"""Build the LedgerCore V2 textbook PDF from committed publication sources and the frozen application commit.

Run from the repository root with a Python that has playwright and pypdf (manual/v2/tools/requirements.txt) and with
Microsoft Edge installed:

  python manual/v2/tools/make_diagrams.py          # regenerates manual/v2/diagrams/*.svg (committed)
  python manual/v2/tools/build.py                  # -> manual/dist/LedgerCore-V2-Enterprise-Banking-Reliability-Engineering.pdf

1. Preprocess manual/v2/chapters/*.md into work/book-v2/src/:
   * `@@excerpt <path> | <start regex> | <end> | <caption>` is replaced by the source lines of <path> read with
     `git show <source-sha>:<path>` (source-sha from manual/v2/metadata.yaml, the E12 source freeze). <end> is a line
     count, `block` (to the brace that closes the block opened on the start line) or `/regex/` (inclusive). The
     excerpt keeps its real line numbers and links to the frozen GitHub blob.
   * figures `![caption](path){#fig-x}`, tables `Table: caption {#tbl-x}` and excerpts are numbered per chapter
     (图 5-2, 表 5-1, 代码 5-3); `@fig-x`, `@tbl-x` and `@code-x` in the text become links carrying those numbers.
     An unresolved reference fails the build.
2. pandoc -> work/book-v2/book.html (template, stylesheet and script from manual/v2/assets).
3. Edge (Playwright) prints twice: pass 1 finds the page of every heading from the PDF outline, the numbers go into
   the table of contents, pass 2 prints the final body; the cover is printed separately and put in front.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
V2 = ROOT / "manual" / "v2"
BUILD = ROOT / "work" / "book-v2"
REPO_URL = "https://github.com/miku-wwl/ledgercore"
DEFAULT_OUT = ROOT / "manual" / "dist" / "LedgerCore-V2-Enterprise-Banking-Reliability-Engineering.pdf"
LANG = {".java": "java", ".sql": "sql", ".ts": "typescript", ".tsx": "tsx", ".ps1": "powershell", ".yml": "yaml",
        ".yaml": "yaml", ".py": "python", ".properties": "ini", ".xml": "xml", ".json": "json"}


def metadata_value(text: str, key: str) -> str:
    m = re.search(rf'^{re.escape(key)}:\s*"?([^"\n]*?)"?\s*$', text, re.M)
    return m.group(1).strip() if m else ""


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True).stdout.decode("utf-8")


# ----------------------------------------------------------------------------------------------- preprocessing
class Numbering:
    def __init__(self) -> None:
        self.refs: dict[str, str] = {}
        self.excerpts: list[dict] = []
        self.figures: list[dict] = []
        self.tables: list[dict] = []


def chapter_label(path: Path) -> str | None:
    m = re.match(r"^(\d\d)-appendix-([a-z])", path.stem)
    if m:
        return m.group(2).upper()
    m = re.match(r"^(\d\d)-", path.stem)
    if m and m.group(1) != "00":
        return str(int(m.group(1)))
    return None


def block_end(lines: list[str], start: int) -> int:
    depth = 0
    seen = False
    for i in range(start, len(lines)):
        code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', lines[i])
        code = re.sub(r"//.*$", "", code)
        for ch in code:
            if ch == "{":
                depth += 1
                seen = True
            elif ch == "}":
                depth -= 1
        if seen and depth <= 0:
            return i
    raise ValueError(f"no closing brace after line {start + 1}")


def leading_context(lines: list[str], start: int) -> int:
    """Include annotations and the comment block directly above the start line."""
    first = start
    while first > 0:
        above = lines[first - 1].strip()
        if above.startswith("@") or above.startswith("//") or above.startswith("--") or above.startswith("/*") \
                or above.startswith("*"):
            first -= 1
            continue
        break
    return first


def excerpt(sha: str, spec: str, label: str, n: int, numbering: Numbering, explicit_id: str | None) -> str:
    parts = [p.strip() for p in spec.split("|")]
    if len(parts) != 4:
        raise ValueError(f"@@excerpt needs 4 fields separated by |: {spec}")
    path, start_re, end, caption = parts
    commit = sha
    pinned = re.match(r"^([0-9a-f]{7,40}):(.+)$", path)
    if pinned:
        # an excerpt of an older commit (before/after comparisons); the default is the frozen source
        commit = git("rev-parse", pinned.group(1) + "^{commit}").strip()
        path = pinned.group(2)
    lines = git("show", f"{commit}:{path}").splitlines()
    start = next((i for i, line in enumerate(lines) if re.search(start_re, line)), None)
    if start is None:
        raise ValueError(f"start /{start_re}/ not found in {path} at {sha[:7]}")
    first = leading_context(lines, start)
    if end == "block":
        last = block_end(lines, start)
    elif end.startswith("/") and end.endswith("/") and len(end) > 2:
        last = next((i for i in range(start, len(lines)) if re.search(end[1:-1], lines[i])), None)
        if last is None:
            raise ValueError(f"end {end} not found in {path}")
    else:
        last = min(len(lines) - 1, start + int(end) - 1)
    body = lines[first:last + 1]
    indent = min((len(l) - len(l.lstrip()) for l in body if l.strip()), default=0)
    body = [l[indent:] if l.strip() else "" for l in body]
    cid = explicit_id or f"code-{label.lower()}-{n}"
    visible = f"代码 {label}-{n}"
    numbering.refs[cid] = visible
    url = f"{REPO_URL}/blob/{commit}/{path}#L{first + 1}-L{last + 1}"
    numbering.excerpts.append({"id": cid, "label": visible, "path": path, "commit": commit, "start": first + 1,
                               "end": last + 1, "lines": last - first + 1, "anchor": lines[start].strip(),
                               "caption": caption, "url": url})
    lang = LANG.get(Path(path).suffix, "")
    classes = f".{lang} .numberLines" if lang else ".numberLines"
    fence = "~~~~"
    return (f'::: {{.excerpt #{cid}}}\n'
            f':::: excerpt-head\n'
            f'[{visible}]{{.code-no}} {caption}\n\n'
            f'[`{path}` · 第 {first + 1}–{last + 1} 行 · 提交 `{commit[:7]}` · [{"冻结源码" if commit == sha else "该提交的源码"}]({url})]{{.code-src}}\n'
            f'::::\n\n'
            f'{fence} {{{classes} startFrom="{first + 1}"}}\n' + "\n".join(body) + f"\n{fence}\n:::\n")


def display_width(cell: str) -> int:
    cell = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", cell).replace("**", "").replace("`", "")
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in cell.strip())


def split_row(line: str) -> list[str]:
    s = line.strip()
    s = s[1:] if s.startswith("|") else s
    s = s[:-1] if s.endswith("|") and not s.endswith("\\|") else s
    return [c.strip() for c in re.split(r"(?<!\\)\|", s)]


SEP_RE = re.compile(r"^\s*\|(\s*:?-{3,}:?\s*\|)+\s*$")


def proportion_tables(lines: list[str]) -> list[str]:
    """pandoc sizes a wide pipe table by the dashes of its separator; equal dashes make every column equally wide,
    which squeezes the text columns of most tables in this book. When the author left the dashes equal, derive them
    from the content (CJK characters count double; a floor keeps short columns readable, a cap stops one long cell
    from taking the whole width)."""
    out: list[str] = []
    i = 0
    in_fence = False
    while i < len(lines):
        line = lines[i]
        if line.startswith("```") or line.startswith("~~~"):
            in_fence = not in_fence
        if not in_fence and line.lstrip().startswith("|") and i + 1 < len(lines) and SEP_RE.match(lines[i + 1]):
            sep = split_row(lines[i + 1])
            j = i + 2
            while j < len(lines) and lines[j].lstrip().startswith("|"):
                j += 1
            if len({len(c.strip(":")) for c in sep}) == 1:
                rows = [split_row(r) for r in [line] + lines[i + 2:j]]
                cells = []
                for k, c in enumerate(sep):
                    head = display_width(rows[0][k]) + 2 if k < len(rows[0]) else 6
                    body = max((display_width(r[k]) for r in rows[1:] if k < len(r)), default=0)
                    width = max(10, head, min(body, 70))
                    cells.append((":" if c.startswith(":") else "") + "-" * width + (":" if c.endswith(":") else ""))
                out.extend([line, "|" + "|".join(cells) + "|"])
                out.extend(lines[i + 2:j])
                i = j
                continue
        out.append(line)
        i += 1
    return out


FIG_RE = re.compile(r"^!\[(?P<cap>.*)\]\((?P<src>[^)]+)\)\{(?P<attrs>[^}]*#(?P<id>fig-[\w-]+)[^}]*)\}\s*$")
TBL_RE = re.compile(r"^(?P<lead>Table:|:)\s+(?P<cap>.*?)\s*\{#(?P<id>tbl-[\w-]+)\}\s*$")
EXC_RE = re.compile(r"^@@excerpt(?:\{#(?P<id>code-[\w-]+)\})?\s+(?P<spec>.+)$")
REF_RE = re.compile(r"@(?P<id>(?:fig|tbl|code)-[\w-]+)")


def preprocess(chapters: list[Path], sha: str, out_dir: Path) -> tuple[list[Path], Numbering]:
    numbering = Numbering()
    staged: list[tuple[Path, list[str]]] = []
    for chapter in chapters:
        label = chapter_label(chapter)
        fig = tbl = code = 0
        output: list[str] = []
        in_fence = False
        source = chapter.read_text(encoding="utf-8").replace("{{source-sha7}}", sha[:7]).replace("{{source-sha}}", sha)
        for line in proportion_tables(source.splitlines()):
            if line.startswith("```") or line.startswith("~~~"):
                in_fence = not in_fence
                output.append(line)
                continue
            if in_fence:
                output.append(line)
                continue
            if (label is None or not label.isdigit()) and re.match(r"^#{1,6}\s", line):
                # the preface and the appendices carry no chapter number, also when the heading has an id block
                attrs = re.search(r"\{([^{}]*)\}\s*$", line)
                if attrs is None:
                    line = line + " {.unnumbered}"
                elif ".unnumbered" not in attrs.group(1):
                    line = line[:attrs.end(1)] + " .unnumbered" + line[attrs.end(1):]
            m = EXC_RE.match(line)
            if m:
                if label is None:
                    raise ValueError(f"{chapter.name}: excerpts belong in numbered chapters or appendices")
                code += 1
                output.append(excerpt(sha, m.group("spec"), label, code, numbering, m.group("id")))
                continue
            m = FIG_RE.match(line)
            if m and label is not None:
                fig += 1
                visible = f"图 {label}-{fig}"
                numbering.refs[m.group("id")] = visible
                numbering.figures.append({"id": m.group("id"), "label": visible, "src": m.group("src"),
                                          "caption": m.group("cap"), "chapter": chapter.name})
                output.append(f"![{visible}　{m.group('cap')}]({m.group('src')}){{{m.group('attrs')}}}")
                continue
            m = TBL_RE.match(line)
            if m and label is not None:
                tbl += 1
                visible = f"表 {label}-{tbl}"
                numbering.refs[m.group("id")] = visible
                numbering.tables.append({"id": m.group("id"), "label": visible, "caption": m.group("cap"),
                                         "chapter": chapter.name})
                output.append(f"{m.group('lead')} {visible}　{m.group('cap')} []{{#{m.group('id')}}}")
                continue
            output.append(line)
        if in_fence:
            raise ValueError(f"{chapter.name}: unterminated code fence")
        staged.append((chapter, output))

    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.md"):
        old.unlink()
    written: list[Path] = []
    unresolved: list[str] = []
    for chapter, output in staged:
        text = "\n".join(output) + "\n"

        def resolve(m: re.Match) -> str:
            rid = m.group("id")
            if rid not in numbering.refs:
                unresolved.append(f"{chapter.name}: @{rid}")
                return m.group(0)
            return f"[{numbering.refs[rid]}](#{rid})"

        # replace references only outside code: fenced blocks and inline code spans are kept verbatim
        pieces = re.split(r"(~~~~[\s\S]*?~~~~|```[\s\S]*?```|`[^`\n]*`)", text)
        text = "".join(p if i % 2 else REF_RE.sub(resolve, p) for i, p in enumerate(pieces))
        target = out_dir / chapter.name
        target.write_text(text, encoding="utf-8")
        written.append(target)
    if unresolved:
        raise ValueError("unresolved cross-references: " + ", ".join(unresolved))
    return written, numbering


# ----------------------------------------------------------------------------------------------- pandoc
def pandoc(chapters: list[Path], html_out: Path, metadata: Path) -> None:
    subprocess.run([
        "pandoc", *[str(c) for c in chapters],
        "--from", "markdown+east_asian_line_breaks",
        "--to", "html5", "--standalone",
        "--template", str(V2 / "assets" / "template.html"),
        "--metadata-file", str(metadata),
        "--toc", "--toc-depth=2", "--number-sections",
        "--syntax-highlighting=tango",
        "--lua-filter", str(V2 / "tools" / "codebreak.lua"),
        "--output", str(html_out),
    ], check=True, cwd=ROOT)


def rebase_resources(html: Path, source_dir: Path) -> int:
    """Chapters reference images relative to manual/v2/chapters and the template references manual/v2/assets; the HTML
    is written to work/book-v2. Rewrite those relative paths so the browser loads the committed files."""
    text = html.read_text(encoding="utf-8")
    count = 0

    def fix_img(m: re.Match) -> str:
        nonlocal count
        src = m.group(2)
        if re.match(r"^[a-z]+:|^/|^#", src):
            return m.group(0)
        count += 1
        return m.group(1) + Path(os.path.relpath((source_dir / src).resolve(), BUILD)).as_posix() + m.group(3)

    text = re.sub(r'(<img\b[^>]*?\bsrc=")([^"]+)(")', fix_img, text)
    text = re.sub(r'(<(?:link|script)\b[^>]*?\b(?:href|src)=")(assets/[^"]+)(")',
                  lambda m: m.group(1) + Path(os.path.relpath(V2 / m.group(2), BUILD)).as_posix() + m.group(3), text)
    html.write_text(text, encoding="utf-8")
    return count


# ----------------------------------------------------------------------------------------------- printing
def footer(title: str) -> str:
    return ('<div style="width:100%;font-size:7.5px;color:#8c959f;padding:0 16mm;'
            "font-family:'Microsoft YaHei',sans-serif;display:flex;justify-content:space-between;\">"
            f"<span>{title}</span>"
            '<span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>')


def flatten_outline(reader, items, out: list[tuple[str, int]]) -> None:
    for item in items:
        if isinstance(item, list):
            flatten_outline(reader, item, out)
        else:
            out.append((item.title, reader.get_destination_page_number(item) + 1))


ID_TOKEN = re.compile(r"[A-Z][A-Za-z0-9]*(?:[-–][A-Z0-9][A-Za-z0-9]*)+")


def polish_html(html: Path) -> tuple[int, int]:
    """A paragraph that ends with a colon introduces the list, code or table after it: mark it so the stylesheet
    keeps it on the same page. Identifiers such as R4-02 or E12-G02 must not break across lines in headings or in
    the table of contents."""
    doc = html.read_text(encoding="utf-8")
    doc, leads = re.subn(r"<p>((?:(?!</?p\b)[\s\S])*?[：:])</p>", r'<p class="lead">\1</p>', doc)
    wrapped = 0

    def nobr_text(fragment: str) -> str:
        nonlocal wrapped
        parts = re.split(r"(<[^>]+>)", fragment)
        for k in range(0, len(parts), 2):
            parts[k], n = ID_TOKEN.subn(lambda m: f'<span class="nobr">{m.group(0)}</span>', parts[k])
            wrapped += n
        return "".join(parts)

    doc = re.sub(r"(<h([1-3])\b[^>]*>)([\s\S]*?)(</h\2>)",
                 lambda m: m.group(1) + nobr_text(m.group(3)) + m.group(4), doc)
    doc = re.sub(r'(<nav id="TOC"[^>]*>)([\s\S]*?)(</nav>)',
                 lambda m: m.group(1) + nobr_text(m.group(2)) + m.group(3), doc)
    html.write_text(doc, encoding="utf-8")
    return leads, wrapped


def check_anchors(html: Path) -> list[str]:
    """Every in-document link must name an existing id: the PDF printer drops a link whose target is missing, so the
    PDF-level link check alone cannot see such a link."""
    import html as html_lib
    from urllib.parse import unquote
    doc = html.read_text(encoding="utf-8")
    ids = {html_lib.unescape(v) for v in re.findall(r'\sid="([^"]+)"', doc)}
    targets = {unquote(html_lib.unescape(v)) for v in re.findall(r'href="#([^"]+)"', doc)}
    return sorted(t for t in targets if t not in ids)


def norm(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def print_pdf(page, path: Path, title: str) -> None:
    page.pdf(path=str(path), format="A4", print_background=True, prefer_css_page_size=True,
             display_header_footer=True, header_template="<div></div>", footer_template=footer(title),
             outline=True, tagged=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--html-only", action="store_true")
    ap.add_argument("--allow-warnings", action="store_true")
    args = ap.parse_args()

    metadata = V2 / "metadata.yaml"
    meta = metadata.read_text(encoding="utf-8")
    sha = metadata_value(meta, "source-sha")
    title = metadata_value(meta, "title")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        print("manual/v2/metadata.yaml must name the frozen source-sha (40 hex digits)", file=sys.stderr)
        return 1
    git("cat-file", "-e", f"{sha}^{{commit}}")
    chapters = sorted((V2 / "chapters").glob("*.md"))
    if not chapters:
        print("no chapters in manual/v2/chapters", file=sys.stderr)
        return 1
    t0 = time.time()
    BUILD.mkdir(parents=True, exist_ok=True)
    staged, numbering = preprocess(chapters, sha, BUILD / "src")
    html = BUILD / "book.html"
    pandoc(staged, html, metadata)
    rebased = rebase_resources(html, V2 / "chapters")
    leads, wrapped = polish_html(html)
    missing = check_anchors(html)
    if missing:
        print(f"{len(missing)} in-document link target(s) missing: {missing[:12]}", file=sys.stderr)
        return 1
    (BUILD / "numbering.json").write_text(json.dumps({"sourceSha": sha, "figures": numbering.figures,
                                                      "tables": numbering.tables, "excerpts": numbering.excerpts},
                                                     ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"pandoc: {len(chapters)} chapter files, {len(numbering.figures)} figures, {len(numbering.tables)} tables, "
          f"{len(numbering.excerpts)} excerpts from {sha[:7]}, {rebased} image paths rebased, {leads} lead-ins, "
          f"{wrapped} identifiers kept whole")
    if args.html_only:
        return 0

    from playwright.sync_api import sync_playwright
    from pypdf import PdfReader, PdfWriter

    out = Path(args.out).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    pass1 = BUILD / "pass1.pdf"
    problems: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge")
        page = browser.new_page()
        page.emulate_media(media="print")
        page.goto(html.as_uri())
        page.wait_for_function("document.body.dataset.ready === '1'", timeout=300_000)
        broken = page.evaluate("() => document.body.dataset.brokenImages || ''")
        if broken:
            print(f"broken images: {broken}", file=sys.stderr)
            browser.close()
            return 2
        headings = page.evaluate(
            "() => Array.from(document.querySelectorAll('main h1, main h2'))"
            ".map(h => ({id: h.id || (h.parentElement && h.parentElement.id) || '', text: h.textContent}))")
        page.evaluate("document.body.classList.add('mode-body')")
        print_pdf(page, pass1, title)
        reader = PdfReader(str(pass1))
        outline: list[tuple[str, int]] = []
        flatten_outline(reader, reader.outline, outline)
        pages: dict[str, int] = {}
        cursor = 0
        for h in headings:
            target = norm(h["text"])
            for i in range(cursor, len(outline)):
                if norm(outline[i][0]) in (target, target + target):
                    if h["id"]:
                        pages[h["id"]] = outline[i][1]
                    cursor = i + 1
                    break
        missing = [h["text"].strip() for h in headings if h["id"] and h["id"] not in pages]
        if missing:
            problems.append(f"{len(missing)} heading(s) not found in the PDF outline: {missing[:5]}")
        page.evaluate("""(pages) => {
            document.querySelectorAll('#TOC a[href^="#"]').forEach(a => {
                const id = decodeURIComponent(a.getAttribute('href').slice(1));
                if (pages[id]) a.setAttribute('data-page', String(pages[id]));
            });
        }""", pages)
        body_pdf = BUILD / "body.pdf"
        print_pdf(page, body_pdf, title)
        cover_pdf = BUILD / "cover.pdf"
        page.evaluate("document.body.classList.replace('mode-body', 'mode-cover')")
        page.add_style_tag(content="@page { margin: 0 !important; }")
        page.pdf(path=str(cover_pdf), format="A4", print_background=True, prefer_css_page_size=True)
        browser.close()

    writer = PdfWriter(clone_from=str(body_pdf))
    writer.insert_page(PdfReader(str(cover_pdf)).pages[0], 0)
    writer.add_metadata({
        "/Title": title,
        "/Author": "Weilai Wang (miku-wwl) · written with Claude Code",
        "/Subject": metadata_value(meta, "subtitle"),
        "/Keywords": "LedgerCore, double-entry ledger, PostgreSQL, Spring Boot, outbox, clearing, reconciliation, "
                     "fault injection, PITR, W3C Trace Context, E12",
        "/LedgerCoreSourceSha": sha,
    })
    with open(out, "wb") as fh:
        writer.write(fh)
    n1 = len(reader.pages)
    n2 = len(PdfReader(str(body_pdf)).pages)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"pdf: {out.relative_to(ROOT)} | pages={n2 + 1} (body pass1={n1}, pass2={n2}) | headings mapped="
          f"{len(pages)}/{len(headings)} | {out.stat().st_size / 1_048_576:.1f} MB | sha256 {digest} | {time.time() - t0:.0f}s")
    if n1 != n2:
        problems.append(f"page count changed between passes ({n1} -> {n2})")
    for problem in problems:
        print(f"{'warning' if args.allow_warnings else 'error'}: {problem}", file=sys.stderr)
    return 3 if problems and not args.allow_warnings else 0


if __name__ == "__main__":
    sys.exit(main())
