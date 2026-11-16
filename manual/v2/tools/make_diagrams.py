"""Generate every V2 figure as SVG into manual/v2/diagrams/ (committed; the book build only reads them).

  python manual/v2/tools/make_diagrams.py              # all figures (data figures need the E12 evidence files)
  python manual/v2/tools/make_diagrams.py --no-data    # only the mechanism diagrams
  python manual/v2/tools/make_diagrams.py --only fig-reserve,fig-outbox
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import figs_a  # noqa: E402
import figs_b  # noqa: E402
import figs_c  # noqa: E402

OUT = Path(__file__).resolve().parents[1] / "diagrams"
DATA = {"fig-e6-matrix", "fig-trace-chain", "fig-alert-timeline", "fig-restore", "fig-e12-dimensions"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--no-data", action="store_true")
    args = ap.parse_args()
    figures = {**figs_a.ALL, **figs_b.ALL, **figs_c.ALL}
    wanted = set(filter(None, args.only.split(",")))
    OUT.mkdir(parents=True, exist_ok=True)
    made = 0
    for name, fn in figures.items():
        if wanted and name not in wanted:
            continue
        if args.no_data and name in DATA:
            continue
        fn(OUT / f"{name}.svg")
        made += 1
    print(f"{made} of {len(figures)} figures written to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
