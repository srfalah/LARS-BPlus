#!/usr/bin/env python3
"""Independent deep review of the frozen LARS-B+ YCSB-derived campaign."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd


WORKLOADS = ("YCSB_A", "YCSB_B", "YCSB_E")
VARIANTS = ("STATIC_BPLUS", "LARS_MONITOR_ONLY", "LARS_FULL")


def paired_percent(frame: pd.DataFrame, workload: str, metric: str,
                   left: str, right: str, benefit: bool) -> np.ndarray:
    subset = frame[frame["workload"] == workload]
    pivot = subset.pivot(index="seed", columns="variant", values=metric)
    lhs = pivot[left].astype(float).to_numpy()
    rhs = pivot[right].astype(float).to_numpy()
    if benefit and metric == "throughput_ops_s":
        return (lhs - rhs) / rhs * 100.0
    if benefit:
        return (rhs - lhs) / rhs * 100.0
    return (lhs - rhs) / rhs * 100.0


def validate(run: pd.DataFrame, epoch: pd.DataFrame, events: pd.DataFrame,
             post: pd.DataFrame) -> list[dict[str, object]]:
    checks: list[dict[str, object]] = []

    def add(name: str, passed: bool, evidence: str) -> None:
        checks.append({"check": name, "status": "PASS" if passed else "FAIL", "evidence": evidence})

    add("run row count", len(run) == 90, f"observed={len(run)}, expected=90")
    duplicate_runs = int(run.duplicated(["workload", "variant", "seed"]).sum())
    add("unique run key", duplicate_runs == 0, f"duplicate workload/variant/seed keys={duplicate_runs}")
    matrix = run.groupby(["workload", "variant"]).size()
    add("balanced 3x3x10 matrix", len(matrix) == 9 and bool((matrix == 10).all()),
        f"cells={len(matrix)}, min={int(matrix.min())}, max={int(matrix.max())}")
    seed_sets = run.groupby(["workload", "variant"])["seed"].apply(set)
    paired = all(seed_sets[(workload, variant)] == seed_sets[(workload, "STATIC_BPLUS")]
                 for workload in WORKLOADS for variant in VARIANTS)
    add("paired seeds", paired, "all three variants use the same ten seeds per workload")
    primary = run[["throughput_ops_s", "p99_latency_ns"]].apply(pd.to_numeric, errors="coerce")
    valid_primary = bool(primary.notna().all().all() and np.isfinite(primary.to_numpy()).all()
                         and (primary > 0).all().all())
    add("primary metric validity", valid_primary, "no missing, non-finite, or non-positive values")
    terminals = run["commit_count"].astype(int) + run["rollback_count"].astype(int)
    add("trial lifecycle", bool((run["trial_starts"].astype(int) == terminals).all()),
        f"trial starts={int(run.trial_starts.sum())}, terminals={int(terminals.sum())}")

    epoch_key = ["workload", "variant", "seed", "epoch_index"]
    epoch_groups = epoch.groupby(["workload", "variant", "seed"])
    add("epoch row count", len(epoch) == 2700, f"observed={len(epoch)}, expected=2700")
    add("unique epoch key", int(epoch.duplicated(epoch_key).sum()) == 0,
        f"duplicate epoch keys={int(epoch.duplicated(epoch_key).sum())}")
    add("complete epochs per run", bool((epoch_groups.size() == 30).all()),
        f"min={int(epoch_groups.size().min())}, max={int(epoch_groups.size().max())}")
    add("epoch operation reconciliation", bool((epoch_groups.operations.sum() == 300_000).all()),
        f"per-run sum min={int(epoch_groups.operations.sum().min())}, max={int(epoch_groups.operations.sum().max())}")

    duplicate_events = int(events.duplicated(
        ["profile", "workload", "variant", "repetition", "seed", "event_id", "region_id"]
    ).sum()) if not events.empty else 0
    add("unique event key", duplicate_events == 0, f"duplicate event keys={duplicate_events}")
    add("post-COMMIT consistency", post.empty and int(run.commit_count.sum()) == 0,
        f"post rows={len(post)}, commits={int(run.commit_count.sum())}")

    ycsb_e = run[run["workload"] == "YCSB_E"].pivot(
        index="seed", columns="variant", values="final_records"
    )
    insertion_spread = int((ycsb_e.max(axis=1) - ycsb_e.min(axis=1)).max())
    add("YCSB-E generator parity", insertion_spread == 0,
        f"maximum final-record spread across variants for a paired seed={insertion_spread}")
    return checks


def run_summary(run: pd.DataFrame) -> pd.DataFrame:
    metrics = ["throughput_ops_s", "p99_latency_ns", "mean_latency_ns",
               "controller_time_share_pct", "current_thread_cpu_nanos",
               "current_thread_allocated_bytes", "jit_compilation_millis",
               "gc_collections", "gc_time_millis"]
    rows: list[dict[str, object]] = []
    for (workload, variant), group in run.groupby(["workload", "variant"]):
        row: dict[str, object] = {"workload": workload, "variant": variant}
        for metric in metrics:
            values = pd.to_numeric(group[metric], errors="coerce")
            row[f"median_{metric}"] = float(values.median())
            row[f"iqr_{metric}"] = float(values.quantile(0.75) - values.quantile(0.25))
        rows.append(row)
    return pd.DataFrame(rows)


def instrumentation_summary(run: pd.DataFrame) -> pd.DataFrame:
    metrics = ["elapsed_nanos", "current_thread_cpu_nanos", "process_cpu_nanos",
               "current_thread_allocated_bytes", "peak_heap_bytes",
               "jit_compilation_millis", "gc_time_millis"]
    rows: list[dict[str, object]] = []
    for workload in WORKLOADS:
        subset = run[run["workload"] == workload]
        pivot = subset.pivot(index="seed", columns="variant", values=metrics)
        for metric in metrics:
            for comparison, left, right in (
                ("MONITOR_vs_STATIC", "LARS_MONITOR_ONLY", "STATIC_BPLUS"),
                ("FULL_vs_MONITOR", "LARS_FULL", "LARS_MONITOR_ONLY"),
            ):
                lhs = pivot[metric][left].astype(float)
                rhs = pivot[metric][right].astype(float)
                change = ((lhs - rhs) / rhs * 100.0).replace([np.inf, -np.inf], np.nan)
                rows.append({
                    "workload": workload,
                    "comparison": comparison,
                    "metric": metric,
                    "paired_n": int(change.notna().sum()),
                    "median_cost_increase_pct": float(change.median()) if change.notna().any() else np.nan,
                })
    return pd.DataFrame(rows)


def steady_state_summary(epoch: pd.DataFrame) -> pd.DataFrame:
    rows: list[dict[str, object]] = []
    for skipped_epochs in (0, 1, 5, 10):
        tail = epoch[epoch["epoch_index"] >= skipped_epochs]
        aggregate = tail.groupby(["workload", "variant", "seed"]).agg(
            operations=("operations", "sum"), elapsed_nanos=("elapsed_nanos", "sum")
        ).reset_index()
        aggregate["throughput_ops_s"] = aggregate["operations"] / aggregate["elapsed_nanos"] * 1e9
        for workload in WORKLOADS:
            pivot = aggregate[aggregate["workload"] == workload].pivot(
                index="seed", columns="variant", values="throughput_ops_s"
            )
            monitor_tax = (pivot["STATIC_BPLUS"] - pivot["LARS_MONITOR_ONLY"]) / pivot["STATIC_BPLUS"] * 100.0
            full_effect = (pivot["LARS_FULL"] - pivot["STATIC_BPLUS"]) / pivot["STATIC_BPLUS"] * 100.0
            rows.append({
                "skipped_measured_epochs": skipped_epochs,
                "skipped_measured_operations": skipped_epochs * 10_000,
                "workload": workload,
                "monitoring_tax_median_pct": float(monitor_tax.median()),
                "full_vs_static_effect_median_pct": float(full_effect.median()),
            })
    return pd.DataFrame(rows)


def safety_summary(run: pd.DataFrame, events: pd.DataFrame) -> pd.DataFrame:
    rows: list[dict[str, object]] = []
    full = run[run["variant"] == "LARS_FULL"]
    for workload in WORKLOADS:
        runs = full[full["workload"] == workload]
        ev = events[events["workload"] == workload]
        observation_windows = int(runs.observation_windows.sum())
        degraded = int(runs.degraded_windows.sum())
        persistent = int(runs.persistent_triggers.sum())
        rows.append({
            "workload": workload,
            "full_runs": len(runs),
            "observation_windows": observation_windows,
            "degraded_windows": degraded,
            "degraded_window_rate_pct": degraded / observation_windows * 100.0 if observation_windows else 0.0,
            "persistent_triggers": persistent,
            "diagnosis_attempts": int(runs.diagnosis_attempts.sum()),
            "abstentions": int((ev["outcome"] == "ABSTAIN").sum()),
            "candidate_selections": int(runs.candidate_selections.sum()),
            "trial_starts": int(runs.trial_starts.sum()),
            "commits": int(runs.commit_count.sum()),
            "rollbacks": int(runs.rollback_count.sum()),
        })
    return pd.DataFrame(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--paired-effects", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    run = pd.read_csv(args.input_dir / "run_metrics.csv")
    epoch = pd.read_csv(args.input_dir / "epoch_metrics.csv")
    events = pd.read_csv(args.input_dir / "adaptation_events.csv")
    post = pd.read_csv(args.input_dir / "post_commit_region_metrics.csv")
    paired = pd.read_csv(args.paired_effects)

    checks = pd.DataFrame(validate(run, epoch, events, post))
    if (checks["status"] != "PASS").any():
        raise SystemExit(checks.to_string(index=False))

    args.output_dir.mkdir(parents=True, exist_ok=True)
    checks.to_csv(args.output_dir / "data_quality_checks.csv", index=False)
    run_summary(run).to_csv(args.output_dir / "run_summary.csv", index=False)
    instrumentation_summary(run).to_csv(args.output_dir / "instrumentation_costs.csv", index=False)
    steady_state_summary(epoch).to_csv(args.output_dir / "steady_state_sensitivity.csv", index=False)
    safety_summary(run, events).to_csv(args.output_dir / "safety_summary.csv", index=False)

    # Independent reconciliation of the published median effects.
    mismatches: list[str] = []
    for _, row in paired.iterrows():
        comparison = row["comparison"]
        if comparison == "FULL_vs_STATIC":
            values = paired_percent(run, row["workload"], row["metric"],
                                    "LARS_FULL", "STATIC_BPLUS", True)
        elif comparison == "MONITOR_vs_STATIC":
            values = paired_percent(run, row["workload"], row["metric"],
                                    "LARS_MONITOR_ONLY", "STATIC_BPLUS", False)
            if row["metric"] == "throughput_ops_s":
                values = -values
        else:
            values = paired_percent(run, row["workload"], row["metric"],
                                    "LARS_FULL", "LARS_MONITOR_ONLY", True)
        if not np.isclose(np.median(values), row["paired_median_effect_pct"], atol=1e-9):
            mismatches.append(f"{comparison}/{row['workload']}/{row['metric']}")
    if mismatches:
        raise SystemExit(f"paired-effect reconciliation failed: {mismatches}")
    print("YCSB_DEEP_REVIEW_OK")


if __name__ == "__main__":
    main()
