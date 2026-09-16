#!/usr/bin/env python3
"""Strict analysis for the LARS-B+ YCSB-derived external-validity extension."""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import wilcoxon

WORKLOADS = ["YCSB_A", "YCSB_B", "YCSB_E"]
VARIANTS = ["STATIC_BPLUS", "LARS_MONITOR_ONLY", "LARS_FULL"]
PAIR_KEYS = ["workload", "seed"]
EVENT_KEYS = ["profile", "workload", "variant", "repetition", "seed", "event_id", "region_id"]
BOOTSTRAP_RESAMPLES = 20_000
MIN_POST_OPERATIONS = 256
MIN_SUSTAINED_GAIN = 0.03
METRIC_BY_TYPE = {
    "SEARCH": "search_p95_ns",
    "INSERT": "insert_p99_ns",
    "DELETE": "delete_p95_ns",
    "UPDATE": "update_p95_ns",
    "RANGE_SEARCH": "range_p95_ns",
}


def require_columns(frame: pd.DataFrame, columns: list[str], name: str) -> None:
    missing = [column for column in columns if column not in frame.columns]
    if missing:
        raise ValueError(f"{name} is missing required columns: {missing}")


def holm_adjust(p_values: list[float]) -> list[float]:
    values = np.asarray(p_values, dtype=float)
    order = np.argsort(values)
    adjusted = np.empty(len(values), dtype=float)
    running = 0.0
    for rank, index in enumerate(order):
        running = max(running, min(1.0, (len(values) - rank) * values[index]))
        adjusted[index] = running
    return adjusted.tolist()


def bootstrap_median_ci(values: np.ndarray, seed: int = 20260914) -> tuple[float, float, float]:
    clean = np.asarray(values, dtype=float)
    clean = clean[np.isfinite(clean)]
    if clean.size == 0:
        return math.nan, math.nan, math.nan
    estimate = float(np.median(clean))
    if clean.size == 1:
        return estimate, math.nan, math.nan
    random = np.random.default_rng(seed)
    samples = random.choice(clean, size=(BOOTSTRAP_RESAMPLES, clean.size), replace=True)
    medians = np.median(samples, axis=1)
    low, high = np.quantile(medians, [0.025, 0.975])
    return estimate, float(low), float(high)


def rank_biserial(differences: np.ndarray) -> float:
    differences = np.asarray(differences, dtype=float)
    differences = differences[np.isfinite(differences) & (differences != 0.0)]
    if differences.size == 0:
        return 0.0
    ranks = pd.Series(np.abs(differences)).rank(method="average").to_numpy()
    positive = ranks[differences > 0.0].sum()
    negative = ranks[differences < 0.0].sum()
    return float((positive - negative) / (positive + negative))


def load_inputs(root: Path) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    required = [
        "run_metrics.csv", "epoch_metrics.csv", "adaptation_events.csv",
        "post_commit_region_metrics.csv", "environment.txt", "YCSB_VALIDATION_MANIFEST.txt",
    ]
    missing = [name for name in required if not (root / name).is_file()]
    if missing:
        raise ValueError(f"missing YCSB validation artifacts: {missing}")
    return (
        pd.read_csv(root / "run_metrics.csv"),
        pd.read_csv(root / "epoch_metrics.csv"),
        pd.read_csv(root / "adaptation_events.csv"),
        pd.read_csv(root / "post_commit_region_metrics.csv"),
    )


def validate_integrity(run: pd.DataFrame, events: pd.DataFrame, post: pd.DataFrame,
                       expected_runs: int) -> None:
    require_columns(run, ["profile", "workload", "variant", "repetition", "seed",
                          "throughput_ops_s", "p99_latency_ns", "trial_starts",
                          "commit_count", "rollback_count"], "run_metrics.csv")
    if len(run) != expected_runs:
        raise ValueError(f"expected {expected_runs} run rows, found {len(run)}")
    if run.duplicated(["workload", "variant", "seed"]).any():
        raise ValueError("duplicate workload/variant/seed run keys")
    if set(run["workload"]) != set(WORKLOADS):
        raise ValueError(f"unexpected workload set: {sorted(set(run['workload']))}")
    if set(run["variant"]) != set(VARIANTS):
        raise ValueError(f"unexpected variant set: {sorted(set(run['variant']))}")
    for workload in WORKLOADS:
        groups = run[run["workload"] == workload].groupby("variant")["seed"].apply(set)
        if any(groups[variant] != groups["STATIC_BPLUS"] for variant in VARIANTS):
            raise ValueError(f"unpaired seeds in {workload}")
    for column in ["throughput_ops_s", "p99_latency_ns"]:
        numeric = pd.to_numeric(run[column], errors="coerce")
        if numeric.isna().any() or (~np.isfinite(numeric)).any() or (numeric <= 0.0).any():
            raise ValueError(f"invalid primary metric: {column}")
    terminals = run["commit_count"].astype(int) + run["rollback_count"].astype(int)
    if not (run["trial_starts"].astype(int) == terminals).all():
        raise ValueError("one or more live trials lack an explicit COMMIT or ROLLBACK")

    if not events.empty:
        require_columns(events, EVENT_KEYS + ["outcome"], "adaptation_events.csv")
        if events.duplicated(EVENT_KEYS).any():
            raise ValueError("duplicate adaptation event key")
    if not post.empty:
        require_columns(post, EVENT_KEYS + ["operations"], "post_commit_region_metrics.csv")
        commits = events[(events["variant"] == "LARS_FULL") & (events["outcome"] == "COMMIT")]
        parent = commits[EVENT_KEYS].drop_duplicates()
        joined = post.merge(parent.assign(parent_commit=True), on=EVENT_KEYS, how="left", validate="many_to_one")
        if joined["parent_commit"].isna().any():
            raise ValueError("orphan post-COMMIT row")


def paired_effects(run: pd.DataFrame) -> pd.DataFrame:
    comparisons = [
        ("FULL_vs_STATIC", "LARS_FULL", "STATIC_BPLUS", "improvement"),
        ("MONITOR_vs_STATIC", "LARS_MONITOR_ONLY", "STATIC_BPLUS", "tax"),
        ("FULL_vs_MONITOR", "LARS_FULL", "LARS_MONITOR_ONLY", "improvement"),
    ]
    rows: list[dict] = []
    for comparison, left_name, right_name, sign in comparisons:
        for workload in WORKLOADS:
            subset = run[run["workload"] == workload]
            left = subset[subset["variant"] == left_name].set_index("seed")
            right = subset[subset["variant"] == right_name].set_index("seed")
            seeds = left.index.intersection(right.index)
            for metric in ["throughput_ops_s", "p99_latency_ns"]:
                left_values = left.loc[seeds, metric].astype(float).to_numpy()
                right_values = right.loc[seeds, metric].astype(float).to_numpy()
                if sign == "tax":
                    if metric == "throughput_ops_s":
                        percentages = (right_values - left_values) / right_values * 100.0
                        signed = right_values - left_values
                    else:
                        percentages = (left_values - right_values) / right_values * 100.0
                        signed = left_values - right_values
                else:
                    if metric == "throughput_ops_s":
                        percentages = (left_values - right_values) / right_values * 100.0
                        signed = left_values - right_values
                    else:
                        percentages = (right_values - left_values) / right_values * 100.0
                        signed = right_values - left_values
                estimate, low, high = bootstrap_median_ci(percentages)
                p_value = math.nan
                if len(seeds) >= 5 and np.any(left_values != right_values):
                    p_value = float(wilcoxon(left_values, right_values, alternative="two-sided").pvalue)
                rows.append({
                    "comparison": comparison,
                    "effect_sign": sign,
                    "workload": workload,
                    "metric": metric,
                    "paired_n": len(seeds),
                    "paired_median_effect_pct": estimate,
                    "effect_ci95_low": low,
                    "effect_ci95_high": high,
                    "rank_biserial_effect": rank_biserial(signed),
                    "p_raw": p_value,
                })
    result = pd.DataFrame(rows)
    result["p_holm"] = np.nan
    # Predefined extension families: correct across A/B/E within comparison and endpoint.
    for _, indexes in result.groupby(["comparison", "metric"]).groups.items():
        indexes = list(indexes)
        valid = [index for index in indexes if np.isfinite(result.loc[index, "p_raw"])]
        adjusted = holm_adjust([float(result.loc[index, "p_raw"]) for index in valid])
        for index, value in zip(valid, adjusted):
            result.loc[index, "p_holm"] = value
    return result


def adaptation_summary(run: pd.DataFrame, events: pd.DataFrame) -> pd.DataFrame:
    rows: list[dict] = []
    full_runs = run[run["variant"] == "LARS_FULL"]
    full_events = events[events["variant"] == "LARS_FULL"] if not events.empty else events
    structural_names = ["COMMIT", "ROLLBACK"]
    for workload in WORKLOADS:
        run_rows = full_runs[full_runs["workload"] == workload]
        event_rows = full_events[full_events["workload"] == workload]
        structural = event_rows[event_rows["outcome"].isin(structural_names)]
        attempts = len(structural)
        moved = pd.to_numeric(structural.get("moved_record_fraction"), errors="coerce")
        touched = pd.to_numeric(structural.get("touched_leaf_fraction"), errors="coerce")
        rows.append({
            "workload": workload,
            "observation_windows": int(run_rows["observation_windows"].sum()),
            "degraded_windows": int(run_rows["degraded_windows"].sum()),
            "persistent_triggers": int(run_rows["persistent_triggers"].sum()),
            "diagnosis_attempts": int(run_rows["diagnosis_attempts"].sum()),
            "confident_diagnoses": int(run_rows["confident_diagnoses"].sum()),
            "candidate_selections": int(run_rows["candidate_selections"].sum()),
            "trial_starts": int(run_rows["trial_starts"].sum()),
            "commits": int((event_rows["outcome"] == "COMMIT").sum()),
            "rollbacks": int((event_rows["outcome"] == "ROLLBACK").sum()),
            "rollback_rate": int((event_rows["outcome"] == "ROLLBACK").sum()) / attempts if attempts else 0.0,
            "abstentions": int((event_rows["outcome"] == "ABSTAIN").sum()),
            "slacken_attempts": int(((structural["action"] == "SLACKEN")).sum()),
            "compact_attempts": int(((structural["action"] == "COMPACT")).sum()),
            "records_moved": int(pd.to_numeric(structural.get("records_moved"), errors="coerce").fillna(0).sum()),
            "leaves_touched": int(pd.to_numeric(structural.get("leaves_touched"), errors="coerce").fillna(0).sum()),
            "median_moved_record_fraction": float(moved.median()) if len(moved) else math.nan,
            "maximum_moved_record_fraction": float(moved.max()) if len(moved) else math.nan,
            "median_touched_leaf_fraction": float(touched.median()) if len(touched) else math.nan,
            "maximum_touched_leaf_fraction": float(touched.max()) if len(touched) else math.nan,
            "action_nanos": int(pd.to_numeric(structural.get("action_nanos"), errors="coerce").fillna(0).sum()),
        })
    return pd.DataFrame(rows)


def parse_mix(value: str) -> dict[str, float]:
    return {name: float(share) for name, share in (part.split("=") for part in str(value).split(";"))}


def trial_weighted_post_objective(row: pd.Series) -> float:
    mix = parse_mix(row["trial_operation_mix"])
    weighted: list[tuple[float, float]] = []
    for operation_type, share in mix.items():
        if round(share * row["trial_operations"]) < 16:
            continue
        value = row[METRIC_BY_TYPE[operation_type]]
        if pd.notna(value):
            weighted.append((share, float(value)))
    if not weighted:
        return math.nan
    return sum(share * value for share, value in weighted) / sum(share for share, _ in weighted)


def post_commit_summary(events: pd.DataFrame, post: pd.DataFrame) -> pd.DataFrame:
    commits = events[(events["variant"] == "LARS_FULL") & (events["outcome"] == "COMMIT")].copy()
    if commits.empty:
        return pd.DataFrame(columns=EVENT_KEYS + ["classification"])
    fields = EVENT_KEYS + ["phase", "trial_operation_mix", "trial_operations",
                           "baseline_objective_nanos", "candidate_objective_nanos",
                           "raw_gain", "action", "diagnosis"]
    joined = post.merge(commits[fields], on=EVENT_KEYS, how="right", validate="many_to_one")
    joined["post_objective_nanos"] = joined.apply(trial_weighted_post_objective, axis=1)
    joined["post_gain_vs_trigger"] = 1.0 - (
        joined["post_objective_nanos"] / joined["baseline_objective_nanos"]
    )
    joined["adequate"] = joined["operations"].fillna(0) >= MIN_POST_OPERATIONS

    summaries: list[dict] = []
    for key, group in joined.sort_values("observation_index_after_commit").groupby(EVENT_KEYS, dropna=False):
        commit = group.iloc[0]
        observed = group[group["observation_index_after_commit"].notna()]
        same_phase = observed[observed["phase_x"] == observed["phase_y"]]
        adequate = same_phase[same_phase["adequate"]]
        classification = "RIGHT_CENSORED"
        median_gain = math.nan
        final_gain = math.nan
        if len(adequate) >= 2:
            median_gain = float(adequate["post_gain_vs_trigger"].median())
            final_gain = float(adequate.iloc[-1]["post_gain_vs_trigger"])
            first_gain = float(adequate.iloc[0]["post_gain_vs_trigger"])
            if median_gain >= MIN_SUSTAINED_GAIN and final_gain >= MIN_SUSTAINED_GAIN:
                classification = "SUSTAINED_IMPROVEMENT"
            elif first_gain >= MIN_SUSTAINED_GAIN and final_gain < MIN_SUSTAINED_GAIN:
                classification = "TRANSIENT_IMPROVEMENT"
            elif median_gain <= -MIN_SUSTAINED_GAIN and final_gain <= -MIN_SUSTAINED_GAIN:
                classification = "REGRESSION"
            elif abs(median_gain) < MIN_SUSTAINED_GAIN:
                classification = "NO_MEASURABLE_IMPROVEMENT"
            else:
                classification = "UNRESOLVED_PATTERN"

        latency_gain = 1.0 - float(commit["candidate_objective_nanos"]) / float(commit["baseline_objective_nanos"])
        derived_scan_gain = math.nan
        if commit["diagnosis"] == "SCAN_FRAGMENTATION" and pd.notna(commit["raw_gain"]):
            # Frozen controller equation: raw_gain = 0.5*latency_gain + 0.5*scan_gain.
            derived_scan_gain = 2.0 * float(commit["raw_gain"]) - latency_gain
        summaries.append(dict(zip(EVENT_KEYS, key)) | {
            "diagnosis": commit["diagnosis"],
            "action": commit["action"],
            "post_windows": len(observed),
            "adequate_same_phase_windows": len(adequate),
            "median_post_gain_vs_trigger": median_gain,
            "final_post_gain_vs_trigger": final_gain,
            "derived_trial_scan_amplification_gain": derived_scan_gain,
            "median_post_scan_amplification": float(adequate["average_scan_amplification"].median())
                if len(adequate) else math.nan,
            "classification": classification,
        })
    return pd.DataFrame(summaries)


def ycsb_e_compact_summary(run: pd.DataFrame, events: pd.DataFrame,
                           post_summary: pd.DataFrame) -> dict:
    full_events = events[(events["workload"] == "YCSB_E") & (events["variant"] == "LARS_FULL")]
    compact = full_events[full_events["action"] == "COMPACT"]
    structural = compact[compact["outcome"].isin(["COMMIT", "ROLLBACK"])]
    abstain_reasons = full_events[full_events["outcome"] == "ABSTAIN"]["reason"].value_counts()
    run_rows = run[(run["workload"] == "YCSB_E") & (run["variant"] == "LARS_FULL")]
    return {
        "diagnosis_attempts": int(run_rows["diagnosis_attempts"].sum()),
        "confident_diagnoses": int(run_rows["confident_diagnoses"].sum()),
        "scan_fragmentation_decisions": int((full_events["diagnosis"] == "SCAN_FRAGMENTATION").sum()),
        "compact_candidates": int(len(compact)),
        "compact_trials": int(len(structural)),
        "compact_commits": int((structural["outcome"] == "COMMIT").sum()),
        "compact_rollbacks": int((structural["outcome"] == "ROLLBACK").sum()),
        "dominant_abstention_reasons": "; ".join(f"{reason} ({count})" for reason, count in abstain_reasons.head(5).items()),
        "post_commit_classifications": post_summary[post_summary["workload"] == "YCSB_E"]["classification"].value_counts().to_dict()
            if not post_summary.empty else {},
    }


def markdown_table(frame: pd.DataFrame) -> str:
    if frame.empty:
        return "No rows."
    columns = list(frame.columns)

    def cell(value: object) -> str:
        if pd.isna(value):
            return ""
        if isinstance(value, (float, np.floating)):
            return f"{float(value):.6g}"
        return str(value).replace("|", "\\|").replace("\n", " ")

    lines = ["| " + " | ".join(columns) + " |",
             "|" + "|".join("---" for _ in columns) + "|"]
    for _, row in frame.iterrows():
        lines.append("| " + " | ".join(cell(row[column]) for column in columns) + " |")
    return "\n".join(lines)


def render_report(effects: pd.DataFrame, adaptation: pd.DataFrame,
                  post: pd.DataFrame, compact: dict, expected_runs: int) -> str:
    lines = [
        "# LARS-B+ YCSB-Derived Validation Analysis",
        "",
        f"Integrity result: PASS ({expected_runs}/{expected_runs} unique paired run rows).",
        "",
        "This extension represents YCSB-derived profiles adapted to the fixed long-to-long B+Tree substrate; it is not a complete YCSB client/database benchmark.",
        "",
        "## Paired primary effects",
        "",
        "Positive improvement favors LARS. Positive tax is a cost relative to STATIC_BPLUS. Holm correction is applied across A/B/E within each comparison and endpoint.",
        "",
        markdown_table(effects),
        "",
        "## Adaptation summary",
        "",
        markdown_table(adaptation),
        "",
        "## YCSB-E / COMPACT",
        "",
    ]
    lines.extend(f"- {key}: {value}" for key, value in compact.items())
    lines.extend([
        "",
        "The frozen event schema does not store absolute pre-action scan amplification. For a COMPACT trial, the report reconstructs the relative trial scan-amplification gain from the frozen controller equation and reports absolute post-COMMIT amplification separately; it does not invent an absolute pre-action value.",
        "",
        "## Post-COMMIT persistence",
        "",
    ])
    if post.empty:
        lines.append("No COMMIT event was available for persistence classification.")
    else:
        lines.append(markdown_table(post))
    lines.extend(["", "Result: `YCSB_ANALYSIS_OK`", ""])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-runs", type=int, default=90)
    args = parser.parse_args()

    run, _epoch, events, post = load_inputs(args.input_dir)
    validate_integrity(run, events, post, args.expected_runs)
    effects = paired_effects(run)
    adaptation = adaptation_summary(run, events)
    post_summary = post_commit_summary(events, post)
    compact = ycsb_e_compact_summary(run, events, post_summary)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    effects.to_csv(args.output_dir / "ycsb_paired_effects.csv", index=False)
    adaptation.to_csv(args.output_dir / "ycsb_adaptation_summary.csv", index=False)
    post_summary.to_csv(args.output_dir / "ycsb_post_commit_summary.csv", index=False)
    report = render_report(effects, adaptation, post_summary, compact, args.expected_runs)
    (args.output_dir / "YCSB_ANALYSIS_REPORT.md").write_text(report, encoding="utf-8")
    print(f"YCSB_ANALYSIS_OK {args.output_dir.resolve()}")


if __name__ == "__main__":
    main()
