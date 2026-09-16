"""Recompute manuscript evidence from preserved CSV inputs; does not run benchmarks.

Run from this directory: python reproduce.py
Dependencies: numpy, pandas, scipy, matplotlib. Seed identifiers remain strings.
"""
from pathlib import Path
import hashlib
import json
import sys
import numpy as np
import pandas as pd
from scipy.stats import wilcoxon

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "results" / "derived"
OUT.mkdir(exist_ok=True)
KEY = ["profile", "workload", "variant", "repetition", "seed"]
EK = KEY + ["event_id", "region_id"]
BASELINES = ["STATIC_BPLUS", "PERIODIC_GLOBAL_REBUILD", "THRESHOLD_GLOBAL_REBUILD", "LOCAL_COST_ADAPTIVE"]
ABLATIONS = ["LARS_NO_DIAGNOSIS", "LARS_NO_LOCALITY", "LARS_NO_RUNTIME_VALIDATION", "LARS_NO_ROLLBACK"]
checks = []


def check(name, condition, detail):
    checks.append({"check": name, "passed": bool(condition), "detail": str(detail)})
    if not condition:
        raise AssertionError(f"{name}: {detail}")


def read(campaign, filename):
    return pd.read_csv(ROOT / "data" / campaign / filename, dtype={"seed": str})


def ci(values, seed):
    values = np.asarray(values, dtype=float)
    rng = np.random.default_rng(seed)
    draws = rng.integers(0, len(values), size=(20000, len(values)))
    bounds = np.quantile(np.median(values[draws], axis=1), [.025, .975])
    return float(np.median(values)), float(bounds[0]), float(bounds[1])


def holm(values):
    values = np.asarray(values, dtype=float)
    order = np.argsort(values)
    result = np.empty(len(values))
    result[order] = np.minimum(1, np.maximum.accumulate(values[order] * np.arange(len(values), 0, -1)))
    return result


def main_pairs(run):
    rows = []
    for workload in sorted(run.workload.unique()):
        sub = run[run.workload.eq(workload)]
        full = sub[sub.variant.eq("LARS_FULL")].set_index("seed")
        for comparator in BASELINES + ABLATIONS:
            other = sub[sub.variant.eq(comparator)].set_index("seed")
            common = full.index.intersection(other.index)
            if not len(common):
                continue
            check(f"pairs {workload} {comparator}", len(common) == 10, len(common))
            for metric in ["throughput_ops_s", "p99_latency_ns"]:
                a, b = full.loc[common, metric].to_numpy(), other.loc[common, metric].to_numpy()
                dif = a - b if metric == "throughput_ops_s" else b - a
                pct = 100 * dif / b
                median, low, high = ci(pct, 20260908)
                p = float(wilcoxon(a, b, alternative="two-sided", zero_method="wilcox").pvalue)
                rows.append(dict(workload=workload, comparator=comparator, metric=metric,
                                 paired_n=len(common), improvement_pct=median, ci_low=low, ci_high=high,
                                 p_raw=p, family="baseline" if comparator in BASELINES else "ablation"))
    frame = pd.DataFrame(rows)
    frame["p_holm"] = np.nan
    for _, ix in frame.groupby(["workload", "metric", "family"]).groups.items():
        frame.loc[ix, "p_holm"] = holm(frame.loc[ix, "p_raw"])
    return frame


run, epoch, events, post = [read("main", name) for name in
    ["run_metrics.csv", "epoch_metrics.csv", "adaptation_events.csv", "post_commit_region_metrics.csv"]]
check("main table counts", [len(run), len(epoch), len(events), len(post)] == [730, 21520, 2385, 1064],
      [len(run), len(epoch), len(events), len(post)])
check("main run uniqueness", not run.duplicated(KEY).any(), "campaign/workload/variant/repetition/seed")
check("main primary finite positive", np.isfinite(run[["throughput_ops_s", "p99_latency_ns"]]).all().all()
      and (run[["throughput_ops_s", "p99_latency_ns"]] > 0).all().all(), "730 rows")
check("main event uniqueness", not events.duplicated(EK).any(), len(events))
term = events[events.outcome.isin(["COMMIT", "ROLLBACK", "COMMIT_NO_TRIAL", "KEEP_FAILED_TRIAL"])]
terminal_counts = term.groupby(KEY).size().rename("terminals").reset_index()
linked = run.merge(terminal_counts, on=KEY, how="left", validate="one_to_one").fillna({"terminals": 0})
regional = linked[~linked.variant.isin(["PERIODIC_GLOBAL_REBUILD", "THRESHOLD_GLOBAL_REBUILD"])]
check("main regional-policy terminal counts", (regional.trial_starts == regional.terminals).all(),
      "includes immediate/kept outcomes; global rebuild COMMIT events do not represent trials")
global_rows = linked[linked.variant.isin(["PERIODIC_GLOBAL_REBUILD", "THRESHOLD_GLOBAL_REBUILD"])]
check("global rebuild outcomes", (global_rows.structural_attempts == global_rows.terminals).all(),
      "global COMMIT counts reconcile to structural attempts")
parents = events[events.outcome.isin(["COMMIT", "COMMIT_NO_TRIAL"])][EK + ["phase"]]
joined = post.merge(parents, on=EK, how="left", validate="many_to_one", suffixes=("_post", "_event"), indicator=True)
check("post parent linkage", joined._merge.eq("both").all(), len(joined))
check("post phase linkage", joined.phase_post.eq(joined.phase_event).all(), len(joined))

main = run[~run.workload.str.contains("CONTROL")]
check("main factorial cells", main.groupby(["workload", "variant"]).size().eq(10).all()
      and len(main) == 700, "7 workloads x 10 variants x 10 seeds")
pairs = main_pairs(main)
pairs.to_csv(OUT / "main_paired_effects.csv", index=False)
monitor_rows = []
for workload, group in main.groupby("workload"):
    m = group[group.variant.eq("LARS_MONITOR_ONLY")].set_index("seed")
    s = group[group.variant.eq("STATIC_BPLUS")].set_index("seed").loc[m.index]
    values = 100 * (1 - m.throughput_ops_s.to_numpy() / s.throughput_ops_s.to_numpy())
    med, lo, hi = ci(values, 20260908)
    monitor_rows.append(dict(workload=workload, tax_pct=med, ci_low=lo, ci_high=hi))
monitor = pd.DataFrame(monitor_rows)
monitor.to_csv(OUT / "main_monitoring_tax.csv", index=False)

# Reuse the delivered follow-up rule transparently. It uses trial type counts,
# not per-type trial latency-sample counts; the manuscript explicitly distinguishes it.
sys.path.insert(0, str(ROOT / "source" / "analysis"))
import analyze_ycsb_validation as supplied
full_events = events[events.variant.eq("LARS_FULL") & ~events.workload.str.contains("CONTROL")]
followup = supplied.post_commit_summary(full_events, post)
followup.to_csv(OUT / "main_post_commit_proxy.csv", index=False)
check("main follow-up classification", followup.classification.value_counts().to_dict()
      == {"SUSTAINED_IMPROVEMENT": 21, "RIGHT_CENSORED": 14}, followup.classification.value_counts().to_dict())
trials = full_events[full_events.outcome.isin(["COMMIT", "ROLLBACK"])]
check("main attempts", len(trials) == 81 and trials.outcome.eq("COMMIT").sum() == 35, len(trials))
check("main footprint medians", trials.records_moved.median() == 59 and trials.leaves_touched.median() == 3,
      "59 relocated records; 3 reported leaves")
check("main footprint totals", trials.records_moved.sum() == 4767 and trials.leaves_touched.sum() == 249,
      "4767 relocations; 249 leaf touches")
reasons = trials.groupby(["outcome", "reason"]).size().reset_index(name="count")
reasons.to_csv(OUT / "main_trial_terminal_reasons.csv", index=False)
check("boundary rollback count", trials.reason.str.contains("trial censored at").sum() == 30, "30/46 rollbacks")
main_fail = run[run.variant.eq("LARS_NO_ROLLBACK") & ~run.workload.str.contains("CONTROL")]
check("no-rollback failed retained", main_fail.failed_trials_kept.sum() == 44, main_fail.failed_trials_kept.sum())

yr, ye, yev, yp = [read("ycsb", name) for name in
    ["run_metrics.csv", "epoch_metrics.csv", "adaptation_events.csv", "post_commit_region_metrics.csv"]]
supplied.validate_integrity(yr, yev, yp, 90)
check("ycsb table counts", [len(yr), len(ye), len(yev), len(yp)] == [90, 2700, 20, 0],
      [len(yr), len(ye), len(yev), len(yp)])
check("ycsb no trials", yr.trial_starts.sum() == 0 and yr.commit_count.sum() == 0, "no action-performance evidence")
yeffects = supplied.paired_effects(yr.assign(seed=yr.seed.astype(np.int64)))
yeffects.to_csv(OUT / "ycsb_paired_effects.csv", index=False)
supplied.adaptation_summary(yr, yev).to_csv(OUT / "ycsb_adaptation_summary.csv", index=False)

# Independent median checks use keyed joins and direct ratio calculations.
for workload, group in yr.groupby("workload"):
    wide = group.pivot(index="seed", columns="variant", values="throughput_ops_s")
    independent = 100 * (wide.LARS_FULL / wide.STATIC_BPLUS - 1)
    reported = yeffects[(yeffects.workload == workload) & (yeffects.comparison == "FULL_vs_STATIC")
                       & (yeffects.metric == "throughput_ops_s")].iloc[0].paired_median_effect_pct
    check(f"independent YCSB median {workload}", np.isclose(independent.median(), reported), reported)

# Vector figure, with separate campaign panels and pointwise intervals.
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8, "pdf.fonttype": 42,
                     "axes.spines.top": False, "axes.spines.right": False})
fig, axes = plt.subplots(1, 2, figsize=(7.0, 3.05), gridspec_kw={"width_ratios": [1.5, 1]},
                         constrained_layout=True)
primary = pairs[(pairs.comparator == "STATIC_BPLUS") & (pairs.metric == "throughput_ops_s")]
names = ["READ_HEAVY", "WRITE_HEAVY", "MIXED", "SKEW_80_20", "LOCAL_HOTSPOT", "WORKLOAD_SHIFT", "STRESS"]
for ax, labels, title in [(axes[0], names, "(a) Main campaign"),
                         (axes[1], ["YCSB_A", "YCSB_B", "YCSB_E"], "(b) YCSB-derived extension")]:
    y = np.arange(len(labels))
    for variant, offset, color, marker in [("Full", -.13, "#174a72", "o"), ("Monitor", .13, "#666666", "s")]:
        if ax is axes[0]:
            if variant == "Full":
                d = primary.set_index("workload").loc[labels]
                mids, lows, highs = d.improvement_pct, d.ci_low, d.ci_high
            else:
                d = monitor.set_index("workload").loc[labels]
                mids, lows, highs = -d.tax_pct, -d.ci_high, -d.ci_low
        else:
            comparison = "FULL_vs_STATIC" if variant == "Full" else "MONITOR_vs_STATIC"
            d = yeffects[(yeffects.comparison == comparison) & (yeffects.metric == "throughput_ops_s")].set_index("workload").loc[labels]
            sign = 1 if variant == "Full" else -1
            mids = sign * d.paired_median_effect_pct
            lows = d.effect_ci95_low if sign == 1 else -d.effect_ci95_high
            highs = d.effect_ci95_high if sign == 1 else -d.effect_ci95_low
        ax.errorbar(mids, y + offset, xerr=[mids - lows, highs - mids], fmt=marker,
                    color=color, markersize=4, capsize=2, linewidth=1, label=variant)
    ax.axvline(0, color="black", linewidth=.8)
    ax.set_xlim(-43, 3)
    ax.set_yticks(y, [label.replace("_", " ") for label in labels])
    ax.invert_yaxis()
    ax.set_title(title, loc="left", fontsize=9)
    ax.set_xlabel("Throughput change vs static (%)")
    ax.grid(axis="x", color="#dddddd", linewidth=.5)
handles, labels = axes[0].get_legend_handles_labels()
fig.legend(handles, labels, loc="lower center", bbox_to_anchor=(.56, -.06),
           ncol=2, frameon=False, fontsize=8)
(OUT / "figures").mkdir(exist_ok=True)
fig.savefig(OUT / "figures" / "main_throughput_effects.pdf", bbox_inches="tight")
fig.savefig(OUT / "figures" / "main_throughput_effects.png", dpi=180, bbox_inches="tight")
plt.close(fig)

pd.DataFrame(checks).to_csv(OUT / "integrity_checks.csv", index=False)
paths = sorted((ROOT / "data").rglob("*")) + sorted((ROOT / "source").rglob("*"))
manifest = [{"path": str(p.relative_to(ROOT)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
             "bytes": p.stat().st_size} for p in paths if p.is_file() and "__pycache__" not in str(p)]
(OUT / "input_source_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print("MANUSCRIPT_EVIDENCE_OK", len(checks), "checks")
print(pairs[pairs.comparator.eq("STATIC_BPLUS")].to_string(index=False))
print(yeffects.to_string(index=False))
