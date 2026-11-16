"""Mandatory QA of the V2 textbook PDF (complements, never replaces, the page-by-page visual review).

  python manual/v2/tools/qa_pdf.py manual/dist/LedgerCore-V2-Enterprise-Banking-Reliability-Engineering.pdf \
      [--render 1,2,3,...] [--out work/book-v2/qa]

Checks and records in <out>/qa-report.json:
  * file size, SHA-256, page count, metadata (title, author, frozen source SHA);
  * outline (bookmarks): count, depth, every destination resolves to a page;
  * links: internal link annotations resolve to a page; external URIs listed (GitHub links must name the frozen SHA);
  * fonts: every embedded font name; Chinese text extracted per page; U+FFFD / .notdef-like replacement characters;
  * layout: characters beyond the right margin, pages whose content stops early (not before a chapter start), blank pages;
  * renders the requested pages (default: cover, TOC, early/middle/late and last pages) to PNG for visual inspection.
Exit code 1 when a hard check fails (missing metadata, unresolved bookmark or link, replacement characters, overflow,
blank pages).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

import pypdfium2 as pdfium
import pypdfium2.raw as pdfium_raw
from pypdf import PdfReader
from pypdf.generic import IndirectObject

MM = 72 / 25.4
CJK = re.compile(r"[一-鿿]")


def resolve(obj):
    return obj.get_object() if isinstance(obj, IndirectObject) else obj


def outline_entries(reader: PdfReader, items, depth=1, out=None):
    out = [] if out is None else out
    for item in items:
        if isinstance(item, list):
            outline_entries(reader, item, depth + 1, out)
        else:
            try:
                page = reader.get_destination_page_number(item) + 1
            except Exception:  # noqa: BLE001 - an unresolved destination is exactly what we report
                page = None
            out.append({"title": item.title, "page": page, "depth": depth})
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("--out", default="work/book-v2/qa")
    ap.add_argument("--render", default="")
    ap.add_argument("--min-fill", type=float, default=0.45)
    ap.add_argument("--accept-gaps", default="")
    a = ap.parse_args()
    pdf = Path(a.pdf)
    out = Path(a.out)
    (out / "pages").mkdir(parents=True, exist_ok=True)
    data = pdf.read_bytes()
    reader = PdfReader(str(pdf))
    report: dict = {"file": pdf.as_posix(), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                    "pages": len(reader.pages)}
    meta = reader.metadata or {}
    report["metadata"] = {k: str(v) for k, v in meta.items()}
    problems: list[str] = []
    for key in ("/Title", "/Author", "/LedgerCoreSourceSha"):
        if not meta.get(key):
            problems.append(f"metadata {key} missing")
    sha = str(meta.get("/LedgerCoreSourceSha", ""))

    entries = outline_entries(reader, reader.outline)
    report["outline"] = {"entries": len(entries), "maxDepth": max((e["depth"] for e in entries), default=0),
                         "unresolved": [e["title"] for e in entries if e["page"] is None]}
    if not entries:
        problems.append("no PDF outline (bookmarks)")
    if report["outline"]["unresolved"]:
        problems.append(f"{len(report['outline']['unresolved'])} unresolved bookmark(s)")

    page_ids = {p.indirect_reference.idnum: i + 1 for i, p in enumerate(reader.pages)}
    named = reader.named_destinations
    internal, external, broken = 0, [], []
    for index, page in enumerate(reader.pages):
        for annot_ref in page.get("/Annots", []) or []:
            annot = resolve(annot_ref)
            if annot.get("/Subtype") != "/Link":
                continue
            action = resolve(annot.get("/A")) if annot.get("/A") is not None else None
            dest = annot.get("/Dest")
            if action is not None and action.get("/S") == "/URI":
                external.append({"page": index + 1, "uri": str(action.get("/URI"))})
                continue
            if action is not None and action.get("/S") == "/GoTo":
                dest = action.get("/D")
            dest = resolve(dest) if dest is not None else None
            internal += 1
            target = None
            if isinstance(dest, str) or (dest is not None and not isinstance(dest, list) and hasattr(dest, "startswith")):
                nd = named.get(str(dest))
                if nd is not None:
                    try:
                        target = reader.get_destination_page_number(nd) + 1
                    except Exception:  # noqa: BLE001
                        target = None
            elif isinstance(dest, list) and dest:
                ref = dest[0]
                target = page_ids.get(ref.idnum) if isinstance(ref, IndirectObject) else None
            if target is None:
                broken.append(index + 1)
    github = [e for e in external if "github.com/miku-wwl/ledgercore/blob/" in e["uri"]]
    wrong_sha = [e for e in github if sha and sha not in e["uri"]]
    report["links"] = {"internal": internal, "internalUnresolved": len(broken), "internalUnresolvedPages": broken[:20],
                       "external": len(external), "githubBlob": len(github),
                       "githubBlobNotAtFrozenSha": [e["uri"] for e in wrong_sha][:20],
                       "externalSample": [e["uri"] for e in external[:12]]}
    if broken:
        problems.append(f"{len(broken)} internal link(s) without a resolvable target")

    fonts = Counter()
    for page in reader.pages:
        resources = resolve(page.get("/Resources")) or {}
        font_dict = resolve(resources.get("/Font")) if resources else None
        for font_ref in (font_dict or {}).values():
            font = resolve(font_ref)
            fonts[str(font.get("/BaseFont"))] += 1
    report["fonts"] = dict(fonts.most_common())

    doc = pdfium.PdfDocument(str(pdf))
    replacement_pages, cjk_counts = [], []
    gaps, overflow, blank = [], [], []
    starts = {e["page"] for e in entries if e["depth"] == 1 and e["page"]}
    for i in range(len(doc)):
        page = doc[i]
        textpage = page.get_textpage()
        text = textpage.get_text_range()
        cjk_counts.append(len(CJK.findall(text)))
        if "�" in text:
            replacement_pages.append(i + 1)
        if i == 0:
            continue  # the cover is printed full-bleed without margins
        w, h = page.get_size()
        bottom_limit, top_limit = 15 * MM, h - 16 * MM
        right_limit = w - 17 * MM + 1.5 * MM
        tops, bottoms, far = [], [], 0
        for obj in page.get_objects(max_depth=1):
            if obj.type == pdfium_raw.FPDF_PAGEOBJ_IMAGE:
                l, b, r, t = obj.get_bounds()
                if t > bottom_limit and b < top_limit:
                    tops.append(t)
                    bottoms.append(b)
        for c in range(textpage.count_chars()):
            l, b, r, t = textpage.get_charbox(c)
            if b <= bottom_limit or t >= top_limit or (r - l) <= 0:
                continue
            tops.append(t)
            bottoms.append(b)
            if r > right_limit and not textpage.get_text_range(c, 1).isspace():
                far += 1
        if far:
            overflow.append([i + 1, far])
        if not tops:
            blank.append(i + 1)
            continue
        used = (min(max(tops), top_limit) - max(min(bottoms), bottom_limit)) / (top_limit - bottom_limit)
        if used < a.min_fill and (i + 2) not in starts and i + 1 != len(doc):
            gaps.append([i + 1, round(used, 2)])
    accepted = {int(p) for p in a.accept_gaps.split(",") if p.strip()}
    report["text"] = {"cjkCharacters": sum(cjk_counts), "pagesWithoutCjk": [i + 1 for i, n in enumerate(cjk_counts) if n == 0],
                      "replacementCharacterPages": replacement_pages}
    report["layout"] = {"overflowPages": overflow, "blankPages": blank, "gaps": gaps,
                        "unacceptedGaps": [p for p, _ in gaps if p not in accepted], "minFill": a.min_fill}
    if replacement_pages:
        problems.append(f"replacement characters on pages {replacement_pages[:10]}")
    if overflow:
        problems.append(f"{len(overflow)} page(s) with characters past the right margin")
    if blank:
        problems.append(f"blank pages {blank}")

    n = len(doc)
    wanted = [int(p) for p in a.render.split(",") if p.strip()] or sorted({1, 2, 3, 4, 5, n // 4, n // 2, 3 * n // 4, n - 1, n})
    for p in wanted:
        if 1 <= p <= n:
            doc[p - 1].render(scale=1.4).to_pil().save(out / "pages" / f"page-{p:03d}.png")
    report["rendered"] = wanted
    report["problems"] = problems
    report["result"] = "PASS" if not problems else "FAIL"
    (out / "qa-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("pages", "bytes", "sha256", "result", "problems")}, ensure_ascii=False))
    print(f"outline {report['outline']['entries']} entries (depth {report['outline']['maxDepth']}); links internal "
          f"{internal} (unresolved {len(broken)}), external {len(external)} (GitHub blobs {len(github)}, not at frozen SHA "
          f"{len(wrong_sha)}); CJK characters {report['text']['cjkCharacters']}; fonts {list(fonts)[:6]}")
    print(f"layout: overflow {len(overflow)} page(s), blank {blank}, gaps {len(gaps)} (unaccepted "
          f"{report['layout']['unacceptedGaps'][:30]})")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
