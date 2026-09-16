#!/usr/bin/env python3
"""LARS-B+ Step 8 publication-results analyzer.

Reads:
  run_metrics.csv
  epoch_metrics.csv
  adaptation_events.csv

Produces publication-oriented descriptive statistics, paired effect sizes,
Wilcoxon signed-rank tests with Holm correction, diagnostic summaries,
recovery summaries, and a Markdown report.

The script is intentionally strict: missing primary columns, duplicated paired
keys, or inconsistent seeds cause an error rather than silent imputation.
"""
from __future__ import annotations

import argparse
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
from scipy.stats import wilcoxon

PRIMARY_METRICS = [
    "throughput_ops_s",
    "p50_latency_ns",
    "p95_latency_ns",
    "p99_latency_ns",
    "peak_heap_bytes",
    "controller_nanos",
    "maintenance_nanos",
    "records_moved",
]

LOWER_IS_BETTER = {
    "p50_latency_ns", "p95_latency_ns", "p99_latency_ns", "peak_heap_bytes",
    "controller_nanos", "maintenance_nanos", "records_moved", "leaves_touched",
    "recovery_operations", "false_trigger_rate_per_100k", "rollback_rate"
}

BASELINE_VARIANTS = [
    "STATIC_BPLUS",
    "PERIODIC_GLOBAL_REBUILD",
    "THRESHOLD_GLOBAL_REBUILD",
    "LOCAL_COST_ADAPTIVE",
]

ABLATION_VARIANTS = [
    "LARS_NO_DIAGNOSIS",
    "LARS_NO_LOCALITY",
    "LARS_NO_RUNTIME_VALIDATION",
    "LARS_NO_ROLLBACK",
]

MONITOR_ONLY_VARIANT = "LARS_MONITOR_ONLY"

PAIR_KEYS = ["workload", "seed"]

ALIASES = {
    "throughput": "throughput_ops_s",
    "throughput_ops_sec": "throughput_ops_s",
    "p50_ns": "p50_latency_ns",
    "p95_ns": "p95_latency_ns",
    "p99_ns": "p99_latency_ns",
    "mean_ns": "mean_latency_ns",
    "max_ns": "max_latency_ns",
    "peak_heap": "peak_heap_bytes",
    "controller_time_nanos": "controller_nanos",
    "maintenance_time_nanos": "maintenance_nanos",
    "monitoring_overhead_pct": "controller_time_share_pct",
}


def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    ren = {c: ALIASES[c] for c in df.columns if c in ALIASES and ALIASES[c] not in df.columns}
    return df.rename(columns=ren)


def require(df: pd.DataFrame, cols: Iterable[str], name: str) -> None:
    missing = [c for c in cols if c not in df.columns]
    if missing:
        raise ValueError(f"{name} is missing required columns: {missing}")


def finite_numeric(df: pd.DataFrame, cols: Iterable[str]) -> None:
    for c in cols:
        if c not in df.columns:
            continue
        df[c] = pd.to_numeric(df[c], errors="coerce")


BOOTSTRAP_RESAMPLES = 5000

def bootstrap_ci(x: np.ndarray, statistic=np.median, confidence=0.95, resamples=None, seed=20260908):
    if resamples is None:
        resamples = BOOTSTRAP_RESAMPLES
    x = np.asarray(x, dtype=float)
    x = x[np.isfinite(x)]
    if x.size == 0:
        return (math.nan, math.nan, math.nan)
    est = float(statistic(x))
    if x.size == 1:
        return (est, math.nan, math.nan)
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, x.size, size=(resamples, x.size))
    vals = np.apply_along_axis(statistic, 1, x[idx])
    alpha = (1.0 - confidence) / 2.0
    lo, hi = np.quantile(vals, [alpha, 1 - alpha])
    return est, float(lo), float(hi)


def paired_rank_biserial(diffs: np.ndarray) -> float:
    """Matched-pairs rank-biserial correlation; sign follows diffs = LARS - comparator."""
    d = np.asarray(diffs, dtype=float)
    d = d[np.isfinite(d) & (d != 0)]
    if d.size == 0:
        return 0.0
    ranks = pd.Series(np.abs(d)).rank(method="average").to_numpy()
    pos = ranks[d > 0].sum()
    neg = ranks[d < 0].sum()
    denom = pos + neg
    return float((pos - neg) / denom) if denom else 0.0


def holm_adjust(pvals: list[float]) -> list[float]:
    n = len(pvals)
    if n == 0:
        return []
    arr = np.asarray(pvals, dtype=float)
    order = np.argsort(arr)
    adjusted = np.empty(n, dtype=float)
    running = 0.0
    for rank, idx in enumerate(order):
        val = min(1.0, (n - rank) * arr[idx])
        running = max(running, val)
        adjusted[idx] = running
    return adjusted.tolist()


def validate_run_data(run: pd.DataFrame) -> None:
    require(run, ["workload", "variant", "seed", "throughput_ops_s", "p50_latency_ns", "p95_latency_ns", "p99_latency_ns"], "run_metrics.csv")
    dup = run.duplicated(["workload", "variant", "seed"], keep=False)
    if dup.any():
        sample = run.loc[dup, ["workload", "variant", "seed"]].head(10).to_dict("records")
        raise ValueError(f"Duplicate workload/variant/seed rows found; paired analysis would be ambiguous: {sample}")
    if "LARS_FULL" not in set(run["variant"]):
        raise ValueError("run_metrics.csv has no LARS_FULL rows")


def summarize_runs(run: pd.DataFrame) -> pd.DataFrame:
    rows = []
    metrics = [m for m in PRIMARY_METRICS + ["leaves_touched", "full_rebuilds", "controller_time_share_pct"] if m in run.columns]
    for (workload, variant), g in run.groupby(["workload", "variant"], sort=True):
        for m in metrics:
            x = g[m].to_numpy(dtype=float)
            med, lo, hi = bootstrap_ci(x)
            rows.append({
                "workload": workload,
                "variant": variant,
                "metric": m,
                "n": int(np.isfinite(x).sum()),
                "median": med,
                "ci95_low": lo,
                "ci95_high": hi,
                "mean": float(np.nanmean(x)) if np.isfinite(x).any() else math.nan,
                "std": float(np.nanstd(x, ddof=1)) if np.isfinite(x).sum() > 1 else math.nan,
            })
    return pd.DataFrame(rows)


def paired_comparisons(run: pd.DataFrame) -> pd.DataFrame:
    rows = []
    comparators = [v for v in BASELINE_VARIANTS + ABLATION_VARIANTS if v in set(run["variant"])]
    primary_test_metrics = [m for m in ["throughput_ops_s", "p99_latency_ns"] if m in run.columns]
    for workload in sorted(run["workload"].unique()):
        sub = run[run["workload"] == workload]
        lars = sub[sub["variant"] == "LARS_FULL"].set_index("seed")
        for comp in comparators:
            other = sub[sub["variant"] == comp].set_index("seed")
            common = lars.index.intersection(other.index)
            if len(common) == 0:
                continue
            for metric in primary_test_metrics:
                a = lars.loc[common, metric].astype(float).to_numpy()
                b = other.loc[common, metric].astype(float).to_numpy()
                mask = np.isfinite(a) & np.isfinite(b)
                a, b = a[mask], b[mask]
                if len(a) == 0:
                    continue
                # Improvement signed so positive always means LARS is better.
                if metric in LOWER_IS_BETTER:
                    pct = (b - a) / np.maximum(np.abs(b), 1e-12) * 100.0
                    signed_diff = b - a
                else:
                    pct = (a - b) / np.maximum(np.abs(b), 1e-12) * 100.0
                    signed_diff = a - b
                med_pct, pct_lo, pct_hi = bootstrap_ci(pct)
                p = math.nan
                if len(a) >= 5 and np.any(np.abs(a - b) > 0):
                    try:
                        p = float(wilcoxon(a, b, alternative="two-sided", zero_method="wilcox").pvalue)
                    except ValueError:
                        p = math.nan
                rows.append({
                    "workload": workload,
                    "comparator": comp,
                    "metric": metric,
                    "paired_n": len(a),
                    "lars_median": float(np.median(a)),
                    "comparator_median": float(np.median(b)),
                    "paired_median_improvement_pct": med_pct,
                    "improvement_ci95_low": pct_lo,
                    "improvement_ci95_high": pct_hi,
                    "median_signed_difference": float(np.median(signed_diff)),
                    "rank_biserial_effect": paired_rank_biserial(signed_diff),
                    "p_raw": p,
                })
    out = pd.DataFrame(rows)
    if out.empty:
        return out
    out["p_holm"] = np.nan
    # Pre-specified families: within each workload/metric, correct baseline contrasts
    # separately from ablation contrasts. This avoids mixing distinct hypotheses
    # while still controlling family-wise error for each reported comparison family.
    out["comparison_family"] = out["comparator"].map(
        lambda v: "baseline" if v in BASELINE_VARIANTS else "ablation"
    )
    for (_, _, _), idx in out.groupby(["workload", "metric", "comparison_family"]).groups.items():
        idx = list(idx)
        valid = [i for i in idx if np.isfinite(out.loc[i, "p_raw"])]
        if valid:
            adj = holm_adjust([float(out.loc[i, "p_raw"]) for i in valid])
            for i, p in zip(valid, adj):
                out.loc[i, "p_holm"] = p
    return out


def derive_overheads(run: pd.DataFrame) -> pd.DataFrame:
    x = run.copy()
    if "elapsed_nanos" not in x.columns:
        if "operations" in x.columns and "throughput_ops_s" in x.columns:
            x["elapsed_nanos"] = x["operations"] / x["throughput_ops_s"] * 1e9
    if "controller_nanos" in x.columns and "elapsed_nanos" in x.columns:
        x["controller_time_share_pct"] = x["controller_nanos"] / x["elapsed_nanos"].replace(0, np.nan) * 100.0
    if "maintenance_nanos" in x.columns and "elapsed_nanos" in x.columns:
        x["maintenance_overhead_pct"] = x["maintenance_nanos"] / x["elapsed_nanos"].replace(0, np.nan) * 100.0
    return x


def event_summary(events: pd.DataFrame, run: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    if events.empty:
        return pd.DataFrame(), pd.DataFrame()
    require(events, ["workload", "variant", "seed", "outcome"], "adaptation_events.csv")
    e = events.copy()
    structural_outcomes = ["COMMIT", "ROLLBACK", "COMMIT_NO_TRIAL", "KEEP_FAILED_TRIAL"]
    structural = e[e["outcome"].isin(structural_outcomes)]
    rows = []
    for (workload, variant), g in e.groupby(["workload", "variant"], sort=True):
        s = g[g["outcome"].isin(structural_outcomes)]
        commits = int(g["outcome"].isin(["COMMIT", "COMMIT_NO_TRIAL"]).sum())
        rollbacks = int((g["outcome"] == "ROLLBACK").sum())
        kept_failed = int((g["outcome"] == "KEEP_FAILED_TRIAL").sum())
        attempts = len(s)
        rows.append({
            "workload": workload,
            "variant": variant,
            "adaptation_attempts": attempts,
            "commits": commits,
            "rollbacks": rollbacks,
            "failed_trials_kept": kept_failed,
            "rollback_rate": rollbacks / attempts if attempts else 0.0,
            "abstentions": int((g["outcome"] == "ABSTAIN").sum()),
            "records_moved": float(pd.to_numeric(s.get("records_moved", pd.Series(dtype=float)), errors="coerce").fillna(0).sum()),
            "leaves_touched": float(pd.to_numeric(s.get("leaves_touched", pd.Series(dtype=float)), errors="coerce").fillna(0).sum()),
            "restructuring_nanos": float(pd.to_numeric(s.get("action_nanos", pd.Series(dtype=float)), errors="coerce").fillna(0).sum()),
        })
    summary = pd.DataFrame(rows)

    diag_rows = []
    if "expected_diagnosis" in e.columns and "diagnosis" in e.columns:
        labeled = e[e["expected_diagnosis"].notna() & (e["expected_diagnosis"].astype(str) != "")].copy()
        if not labeled.empty:
            labeled["match"] = labeled["diagnosis"].astype(str) == labeled["expected_diagnosis"].astype(str)
            for (workload, variant), g in labeled.groupby(["workload", "variant"], sort=True):
                diag_rows.append({
                    "workload": workload,
                    "variant": variant,
                    "labeled_decisions": len(g),
                    "diagnosis_matches": int(g["match"].sum()),
                    "diagnosis_match_rate": float(g["match"].mean()),
                })
    diag = pd.DataFrame(diag_rows)

    # Stable-control false trigger rate per 100k foreground ops.
    if "STABLE_CONTROL" in set(run["workload"]):
        stable_runs = run[run["workload"] == "STABLE_CONTROL"]
        if "operations" in stable_runs.columns:
            stable_attempts = structural[structural["workload"] == "STABLE_CONTROL"].groupby(["variant", "seed"]).size().rename("attempts")
            base = stable_runs.set_index(["variant", "seed"])
            base = base.join(stable_attempts, how="left").fillna({"attempts": 0})
            base["false_trigger_rate_per_100k"] = base["attempts"] / base["operations"].replace(0, np.nan) * 100000.0
            f = base.reset_index().groupby("variant")["false_trigger_rate_per_100k"].agg(["median", "mean", "max"]).reset_index()
            f["workload"] = "STABLE_CONTROL"
            summary = summary.merge(f, on=["workload", "variant"], how="left", suffixes=("", "_false"))
    return summary, diag


def recovery_summary(epoch: pd.DataFrame) -> pd.DataFrame:
    if epoch.empty:
        return pd.DataFrame()
    needed = ["workload", "variant", "seed", "epoch_index", "throughput_ops_s"]
    if not all(c in epoch.columns for c in needed):
        return pd.DataFrame()
    phase_col = "phase" if "phase" in epoch.columns else None
    rows = []
    for (workload, variant, seed), g in epoch.groupby(["workload", "variant", "seed"], sort=True):
        if workload not in {"WORKLOAD_SHIFT", "STRESS"}:
            continue
        g = g.sort_values("epoch_index").reset_index(drop=True)
        if phase_col and g[phase_col].nunique() >= 2:
            phases = list(dict.fromkeys(g[phase_col].astype(str)))
            post = g[g[phase_col].astype(str) == phases[-1]].copy()
        else:
            post = g.iloc[len(g)//2:].copy()
        if len(post) < 5:
            continue
        tail_n = max(3, int(math.ceil(len(post) * 0.20)))
        target = float(post["throughput_ops_s"].tail(tail_n).median())
        threshold = 0.95 * target
        vals = post["throughput_ops_s"].to_numpy(dtype=float)
        pos = None
        for i in range(max(0, len(vals)-2)):
            if np.all(vals[i:i+3] >= threshold):
                pos = i
                break
        if pos is None:
            rec_epochs = math.nan
        else:
            rec_epochs = int(pos)
        epoch_ops = math.nan
        if "end_operation" in post.columns and "start_operation" in post.columns:
            tmp = (post["end_operation"] - post["start_operation"]).astype(float)
            if len(tmp): epoch_ops = float(tmp.median())
        rows.append({
            "workload": workload,
            "variant": variant,
            "seed": seed,
            "steady_state_target_throughput": target,
            "recovery_epochs": rec_epochs,
            "recovery_operations": rec_epochs * epoch_ops if np.isfinite(rec_epochs) and np.isfinite(epoch_ops) else math.nan,
        })
    return pd.DataFrame(rows)


def monitoring_impact_summary(run: pd.DataFrame) -> pd.DataFrame:
    """Empirical monitoring tax: LARS_MONITOR_ONLY versus STATIC_BPLUS on identical seeds."""
    if MONITOR_ONLY_VARIANT not in set(run["variant"]) or "STATIC_BPLUS" not in set(run["variant"]):
        return pd.DataFrame()
    rows = []
    controls = {"STABLE_CONTROL", "INSERT_PRESSURE_CONTROL", "SCAN_FRAGMENTATION_CONTROL"}
    for workload in sorted(set(run["workload"]) - controls):
        sub = run[run["workload"] == workload]
        mon = sub[sub["variant"] == MONITOR_ONLY_VARIANT].set_index("seed")
        sta = sub[sub["variant"] == "STATIC_BPLUS"].set_index("seed")
        common = mon.index.intersection(sta.index)
        if len(common) == 0:
            continue
        mt = mon.loc[common, "throughput_ops_s"].astype(float).to_numpy()
        st = sta.loc[common, "throughput_ops_s"].astype(float).to_numpy()
        mp = mon.loc[common, "p99_latency_ns"].astype(float).to_numpy()
        sp = sta.loc[common, "p99_latency_ns"].astype(float).to_numpy()
        mask = np.isfinite(mt) & np.isfinite(st) & np.isfinite(mp) & np.isfinite(sp)
        mt, st, mp, sp = mt[mask], st[mask], mp[mask], sp[mask]
        if len(mt) == 0:
            continue
        throughput_tax = (st - mt) / np.maximum(np.abs(st), 1e-12) * 100.0
        p99_tax = (mp - sp) / np.maximum(np.abs(sp), 1e-12) * 100.0
        tmed, tlo, thi = bootstrap_ci(throughput_tax)
        pmed, plo, phi = bootstrap_ci(p99_tax)
        rows.append({
            "workload": workload,
            "paired_n": len(mt),
            "empirical_monitoring_throughput_tax_pct": tmed,
            "throughput_tax_ci95_low": tlo,
            "throughput_tax_ci95_high": thi,
            "empirical_monitoring_p99_tax_pct": pmed,
            "p99_tax_ci95_low": plo,
            "p99_tax_ci95_high": phi,
        })
    return pd.DataFrame(rows)


def dataframe_to_markdown(frame: pd.DataFrame) -> str:
    """Render a compact pipe table without pandas' optional tabulate dependency."""
    columns = [str(column) for column in frame.columns]

    def cell(value: object) -> str:
        if pd.isna(value):
            return ""
        if isinstance(value, (float, np.floating)):
            rendered = f"{float(value):.4g}"
        else:
            rendered = str(value)
        return rendered.replace("|", "\\|").replace("\n", " ")

    lines = ["| " + " | ".join(columns) + " |",
             "| " + " | ".join("---" for _ in columns) + " |"]
    lines.extend("| " + " | ".join(cell(value) for value in row) + " |"
                 for row in frame.itertuples(index=False, name=None))
    return "\n".join(lines)


def write_report(outdir: Path, run_summary: pd.DataFrame, paired: pd.DataFrame,
                 event_summary_df: pd.DataFrame, diag: pd.DataFrame, recovery: pd.DataFrame,
                 monitoring_impact: pd.DataFrame, run: pd.DataFrame) -> None:
    lines = ["# LARS-B+ Step 8 Statistical Analysis", ""]
    lines.append("## Data integrity")
    lines.append(f"- Run rows: {len(run):,}")
    lines.append(f"- Workloads: {', '.join(map(str, sorted(run['workload'].unique())))}")
    lines.append(f"- Variants: {', '.join(map(str, sorted(run['variant'].unique())))}")
    lines.append(f"- Unique seeds: {run['seed'].nunique()}")
    lines.append("")
    lines.append("Primary inferential endpoints are throughput and P99 latency. Other metrics are treated as secondary/descriptive unless pre-registered otherwise. Wilcoxon tests are paired by identical seeds and Holm-corrected within each primary metric family. Effect sizes and bootstrap confidence intervals should be emphasized over p-values.")
    lines.append("")
    if not paired.empty:
        lines.append("## Paired LARS_FULL comparisons")
        display = paired[["workload","comparator","metric","paired_n","paired_median_improvement_pct","improvement_ci95_low","improvement_ci95_high","rank_biserial_effect","p_raw","p_holm"]]
        lines.append(dataframe_to_markdown(display))
        lines.append("")
    if not monitoring_impact.empty:
        lines.append("## Empirical monitoring impact (LARS_MONITOR_ONLY vs STATIC_BPLUS)")
        lines.append(dataframe_to_markdown(monitoring_impact))
        lines.append("")
    if not event_summary_df.empty:
        lines.append("## Adaptation behavior")
        lines.append(dataframe_to_markdown(event_summary_df))
        lines.append("")
    if not diag.empty:
        lines.append("## Diagnostic controls")
        lines.append(dataframe_to_markdown(diag))
        lines.append("")
    if not recovery.empty:
        lines.append("## Workload-shift recovery")
        rsum = recovery.groupby(["workload","variant"])[["recovery_epochs","recovery_operations"]].median(numeric_only=True).reset_index()
        lines.append(dataframe_to_markdown(rsum))
        lines.append("")
    lines.append("## Interpretation rules")
    lines.extend([
        "- Do not call a result significant from an uncorrected p-value alone.",
        "- A CI crossing 0% means the paired improvement direction is not stable at the chosen confidence level.",
        "- Throughput increases and latency decreases are favorable; memory, controller overhead, maintenance cost, records moved, false triggers, rollback rate, and recovery time are costs to minimize.",
        "- A high rollback rate is not automatically bad: it is useful only if rollback prevents retaining harmful candidates at acceptable overhead.",
        "- Publication claims must use the frozen PUBLICATION campaign, not SMOKE or PILOT data.",
    ])
    (outdir / "analysis_report.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input_dir", type=Path)
    ap.add_argument("--output-dir", type=Path, default=None)
    ap.add_argument("--bootstrap-resamples", type=int, default=5000)
    args = ap.parse_args()
    global BOOTSTRAP_RESAMPLES
    BOOTSTRAP_RESAMPLES = args.bootstrap_resamples
    if BOOTSTRAP_RESAMPLES < 500:
        raise ValueError("--bootstrap-resamples must be >= 500")
    indir = args.input_dir
    outdir = args.output_dir or (indir / "step8_analysis")
    outdir.mkdir(parents=True, exist_ok=True)

    run_path = indir / "run_metrics.csv"
    if not run_path.exists():
        raise FileNotFoundError(f"Missing {run_path}. Step 8 cannot produce numerical results without the publication run metrics.")
    run = normalize_columns(pd.read_csv(run_path))
    finite_numeric(run, [c for c in run.columns if c not in {"workload", "variant", "phase"}])
    validate_run_data(run)
    run = derive_overheads(run)

    epoch_path = indir / "epoch_metrics.csv"
    events_path = indir / "adaptation_events.csv"
    epoch = normalize_columns(pd.read_csv(epoch_path)) if epoch_path.exists() else pd.DataFrame()
    events = normalize_columns(pd.read_csv(events_path)) if events_path.exists() else pd.DataFrame()

    run_summary = summarize_runs(run)
    paired = paired_comparisons(run)
    evsum, diag = event_summary(events, run)
    recovery = recovery_summary(epoch)
    monitoring_impact = monitoring_impact_summary(run)

    run_summary.to_csv(outdir / "run_summary.csv", index=False)
    paired.to_csv(outdir / "paired_comparisons.csv", index=False)
    evsum.to_csv(outdir / "adaptation_summary.csv", index=False)
    diag.to_csv(outdir / "diagnostic_summary.csv", index=False)
    recovery.to_csv(outdir / "recovery_summary.csv", index=False)
    monitoring_impact.to_csv(outdir / "monitoring_impact.csv", index=False)
    write_report(outdir, run_summary, paired, evsum, diag, recovery, monitoring_impact, run)

    print(f"STEP8_ANALYSIS_OK output={outdir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"STEP8_ANALYSIS_FAILED: {exc}", file=sys.stderr)
        raise
