"""Render SVG figures to PNG for visual review (not used by the build): svg_preview.py OUTDIR [names...]"""
from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

DIAGRAMS = Path(__file__).resolve().parents[1] / "diagrams"


def main() -> int:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    names = sys.argv[2:]
    files = [DIAGRAMS / f"{n}.svg" for n in names] if names else sorted(DIAGRAMS.glob("*.svg"))
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge")
        page = browser.new_page(device_scale_factor=2, viewport={"width": 660, "height": 400})
        for f in files:
            page.goto(f.as_uri())
            page.locator("svg").screenshot(path=str(out / f"{f.stem}.png"))
        browser.close()
    print(f"{len(files)} previews -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
