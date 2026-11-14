#!/usr/bin/env python3
"""Generate E3 figures only from preserved H01-H04 measurements."""

from __future__ import annotations

import csv
import re
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[3]
MEASUREMENTS = ROOT / "evidence" / "e3" / "measurements"
OUTPUT = ROOT / "evidence" / "e3" / "analysis" / "figures"
H01 = MEASUREMENTS / "h01-balance-history-20261114T023439Z"
H02 = MEASUREMENTS / "h02-transfer-concurrency-20261114T024011Z"
H03 = MEASUREMENTS / "h03-payroll-query-plans-20261114T025611Z"
H04 = MEASUREMENTS / "h04-cross-org-progress-followup"

plt.rcParams.update({"font.size": 9, "axes.titlesize": 11, "figure.dpi": 120})


def rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as stream:
        return list(csv.DictReader(stream))


def median_and_range(values: list[float]) -> tuple[float, float, float]:
    return statistics.median(values), min(values), max(values)


def plot_h01() -> None:
    observations = rows(H01 / "balance-history-summary.csv")
    grouped: dict[int, list[dict[str, str]]] = defaultdict(list)
    for row in observations:
        grouped[int(row["history_postings"])].append(row)
    histories = sorted(grouped)

    fig, axes = plt.subplots(1, 2, figsize=(10.4, 4.1), constrained_layout=True)
    for ax, field, title, color in (
        (axes[0], "sql_p50_ms", "Ledger balance SQL p50", "#2471a3"),
        (axes[1], "service_p50_ms", "TransferService transaction p50", "#b03a2e"),
    ):
        medians, lows, highs = [], [], []
        for size in histories:
            values = [float(row[field]) for row in grouped[size]]
            median, low, high = median_and_range(values)
            medians.append(median)
            lows.append(median - low)
            highs.append(high - median)
        ax.errorbar(histories, medians, yerr=[lows, highs], fmt="o-", capsize=4,
                    color=color, linewidth=1.8, markersize=5)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xticks(histories, [f"{size:,}" for size in histories])
        ax.set_xlabel("Postings on measured source account")
        ax.set_ylabel("Milliseconds (log scale)")
        ax.set_title(title)
        ax.grid(True, which="both", alpha=0.25)
    fig.suptitle("H01 — ledger-history growth (median; whiskers show repeat range)", fontsize=12)
    fig.savefig(OUTPUT / "h01-history-growth.png", dpi=180)
    plt.close(fig)


def plot_h02() -> None:
    observations = rows(H02 / "transfer-concurrency-summary.csv")
    grouped: dict[tuple[str, int], list[dict[str, str]]] = defaultdict(list)
    for row in observations:
        grouped[(row["distribution"], int(row["concurrency"]))].append(row)
    series = [
        ("independent-account-pairs", "Independent pairs", "#2471a3"),
        ("one-hot-payer", "One hot payer", "#b03a2e"),
        ("opposing-direction-pairs", "Opposing directions", "#229954"),
        ("80-percent-funded-20-percent-insufficient", "80/20 mixed", "#7d3c98"),
    ]
    levels = [1, 10, 30, 50]
    fig, axes = plt.subplots(1, 2, figsize=(11.2, 4.3), constrained_layout=True)
    for ax, field, title, ylabel in (
        (axes[0], "ops_per_second", "Transfer throughput", "Operations per second"),
        (axes[1], "p95_ms", "Transfer latency", "p95 milliseconds"),
    ):
        for key, label, color in series:
            medians, lows, highs = [], [], []
            for level in levels:
                values = [float(row[field]) for row in grouped[(key, level)]]
                median, low, high = median_and_range(values)
                medians.append(median)
                lows.append(median - low)
                highs.append(high - median)
            ax.errorbar(levels, medians, yerr=[lows, highs], fmt="o-", capsize=3,
                        label=label, color=color, linewidth=1.6, markersize=4)
        ax.set_xticks(levels)
        ax.set_xlabel("Concurrent clients")
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.grid(True, alpha=0.25)
    axes[1].legend(fontsize=8, loc="upper left")
    fig.suptitle("H02 — financial transfer concurrency (median; whiskers show repeat range)", fontsize=12)
    fig.savefig(OUTPUT / "h02-transfer-concurrency.png", dpi=180)
    plt.close(fig)


def plot_h03() -> None:
    observations = rows(H03 / "payroll-concurrency-summary.csv")
    grouped: dict[tuple[int, int], list[dict[str, str]]] = defaultdict(list)
    for row in observations:
        grouped[(int(row["items"]), int(row["concurrency"]))].append(row)
    levels = [1, 10, 30, 50]
    fig, axes = plt.subplots(2, 2, figsize=(10.4, 7.0), constrained_layout=True)
    for row_index, item_count in enumerate((500, 5000)):
        for col_index, (field, title, color) in enumerate((
            ("ops_per_second", "Items per second", "#2471a3"),
            ("p95_ms", "Item latency p95 (ms)", "#b03a2e"),
        )):
            ax = axes[row_index][col_index]
            medians, lows, highs = [], [], []
            for level in levels:
                values = [float(item[field]) for item in grouped[(item_count, level)]]
                median, low, high = median_and_range(values)
                medians.append(median)
                lows.append(median - low)
                highs.append(high - median)
            ax.errorbar(levels, medians, yerr=[lows, highs], fmt="o-", capsize=4,
                        color=color, linewidth=1.8, markersize=5)
            ax.set_xticks(levels)
            ax.set_xlabel("Concurrent workers")
            ax.set_ylabel("Items/s" if field == "ops_per_second" else "Milliseconds")
            ax.set_title(f"{item_count:,}-item batch — {title}")
            ax.grid(True, alpha=0.25)
    fig.suptitle("H03 — payroll execution (median; whiskers show repeat range)", fontsize=12)
    fig.savefig(OUTPUT / "h03-payroll-concurrency.png", dpi=180)
    plt.close(fig)


def h04_plan_values() -> tuple[list[int], list[int], list[int]]:
    log = (H04 / "commands-and-results.log").read_text(encoding="utf-8")
    sections = (
        log.split("H04_BASELINE_TARGET_COUNTS_AND_PLAN", 1)[1].split("BEGIN", 1)[0],
        log.split("H04_TARGET_COUNTS_AND_PLAN_AFTER_OTHER_ORG_ROWS", 1)[1],
    )
    scans, buffers, targets = [], [], []
    for section in sections:
        match = re.search(r"Seq Scan on payroll_item_results r.*?actual time=[^\n]*?rows=([\d.]+) loops=1", section, re.S)
        buffer_match = re.search(r"Aggregate\s+\(cost=.*?\n\s+Buffers: shared hit=(\d+)", section, re.S)
        target_match = re.search(r"pending_count\s*\|\s*succeeded_count\s*\|\s*failed_count\s*\n[-+| ]+\n\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)", section)
        if not (match and buffer_match and target_match):
            raise ValueError("Could not parse H04 plan and target counts from preserved raw output")
        scans.append(int(float(match.group(1))))
        buffers.append(int(buffer_match.group(1)))
        targets.append(sum(map(int, target_match.groups())))
    return scans, buffers, targets


def plot_h04() -> None:
    labels = ["Before: 1 org", "After: +10 orgs"]
    scans, buffers, targets = h04_plan_values()
    fig, axes = plt.subplots(1, 2, figsize=(9.6, 4.1), constrained_layout=True)
    axes[0].bar(labels, scans, color=["#2471a3", "#b03a2e"], width=0.55)
    target_axis = axes[0].twinx()
    target_axis.plot(labels, targets, "ko--", label="Target batch rows returned")
    target_axis.set_ylim(0, 6000)
    target_axis.set_ylabel("Target batch rows returned")
    for index, value in enumerate(scans):
        axes[0].text(index, value + 2500, f"{value:,} scanned", ha="center", fontsize=8)
    axes[0].set_ylabel("Rows")
    axes[0].set_title("Global result rows scanned; target remains 5,000")
    handles_a, labels_a = axes[0].get_legend_handles_labels()
    handles_b, labels_b = target_axis.get_legend_handles_labels()
    axes[0].legend(handles_a + handles_b, labels_a + labels_b, fontsize=8, loc="upper left")
    axes[0].grid(axis="y", alpha=0.25)
    axes[1].bar(labels, buffers, color=["#2471a3", "#b03a2e"], width=0.55)
    for index, value in enumerate(buffers):
        axes[1].text(index, value + 25, f"{value:,} shared hits", ha="center", fontsize=8)
    axes[1].set_ylabel("Shared buffers (hit blocks)")
    axes[1].set_title("EXPLAIN total shared-buffer hits")
    axes[1].grid(axis="y", alpha=0.25)
    fig.suptitle("H04 — cross-organization payroll progress plan", fontsize=12)
    fig.savefig(OUTPUT / "h04-cross-organization-progress.png", dpi=180)
    plt.close(fig)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    plot_h01()
    plot_h02()
    plot_h03()
    plot_h04()
    print(f"Generated 4 figures from preserved E3 CSV and PostgreSQL output under {OUTPUT}")


if __name__ == "__main__":
    main()
