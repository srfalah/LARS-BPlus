# LARS-B+ Step 7 — Frozen Experimental Protocol

This file defines the publication-oriented experiment design. `SMOKE` results are validation only. `PILOT` is for calibration. `FREEZE_VALIDATION` is the longer, paired pre-freeze check and is not publication evidence. Only a separately executed, frozen `PUBLICATION` campaign on one documented machine should be used for manuscript claims.

## 1. Primary research matrix

Seven required workloads:

1. `READ_HEAVY`
2. `WRITE_HEAVY`
3. `MIXED`
4. `WORKLOAD_SHIFT`
5. `SKEW_80_20`
6. `LOCAL_HOTSPOT`
7. `STRESS`

Each run uses the same deterministic logical operation stream for every variant at the same seed.

### Main baselines

- `STATIC_BPLUS` — conventional B+ tree with no adaptive maintenance.
- `PERIODIC_GLOBAL_REBUILD` — full-tree reconstruction at a fixed operation interval.
- `THRESHOLD_GLOBAL_REBUILD` — full-tree reconstruction using simple global split-pressure / sparse-scan thresholds.
- `LOCAL_COST_ADAPTIVE` — mechanism baseline using persistent trigger + local cost-based maintenance but **without explicit diagnosis, hard locality admission, or empirical live validation**. This is intentionally **not** presented as a reimplementation of Quake, Slalom, AHA-tree, or any named prior system.

### Monitoring control, LARS, and single-component ablations

- `LARS_MONITOR_ONLY` — executes the same regional telemetry/reference-learning path but never starts adaptation; used to estimate the empirical monitoring tax against `STATIC_BPLUS`.
- `LARS_FULL`
- `LARS_NO_DIAGNOSIS`
- `LARS_NO_LOCALITY`
- `LARS_NO_RUNTIME_VALIDATION`
- `LARS_NO_ROLLBACK`

The ablations are designed to test the three claimed contribution mechanisms rather than create unrelated alternative algorithms.

## 2. Diagnostic control suite

Three additional control workloads are not part of the seven main performance workloads:

- `STABLE_CONTROL` — no structural churn; used to measure false adaptations.
- `INSERT_PRESSURE_CONTROL` — contains a labeled insert-pressure phase.
- `SCAN_FRAGMENTATION_CONTROL` — learns a stable scan reference, then applies a legal bounded local SLACKEN perturbation before a labeled scan-fragmentation phase.

The event CSV records the phase and expected diagnosis so diagnosis matches/mismatches can be quantified. These labels are experimental ground truth, not formal causal inference.

## 3. Dataset and tree

- conventional single-threaded in-memory array-backed B+ tree
- leaf capacity: 64 keys
- internal fanout: 64 children
- initial bulk-load target leaf fill: 0.75
- unique `long -> long` mappings
- deterministic sparse logical key domain so inserts can occur inside existing key ranges
- standard publication profile: 1,000,000 initial records
- optional scale runs should separately test 100k, 1M, and 10M when machine memory permits

## 4. Workload operation mixes

The exact mixes are encoded in `WorkloadGenerator.java` and must be frozen before the primary campaign. `WORKLOAD_SHIFT`, `STRESS`, and diagnostic controls are explicitly phased. The phase name is exported in `epoch_metrics.csv`.

## 5. Timing protocol

- each run is executed in a fresh JVM by default (`ExperimentCampaign` forks child JVMs)
- deterministic run order randomization prevents all variants from always running in the same temporal order
- G1 GC is explicitly selected in the child JVM
- a disposable untimed JVM warm-up executes before each measured run
- initial tree construction is outside measured workload timing
- operation latency is measured around the complete index call, therefore LARS controller work and synchronous maintenance are visible to the user-facing latency
- global rebuild cost is inside the triggering operation and is therefore reflected in tail latency and throughput
- full-tree invariant validation is disabled in the timed LARS hot path; the complete index is validated after every run
- Step 6 eager validation remains available for debugging/correctness testing

## 6. Repetitions and seeds

- `SMOKE`: 1 repetition by default
- `PILOT`: 3 repetitions by default
- `FREEZE_VALIDATION`: 5 paired repetitions, 1,000,000 measured operations per run,
  and 200,000 disposable warm-up operations
- `PUBLICATION`: 10 independent deterministic seeds by default

The same repetition number maps to the same seed for every workload/variant pair, enabling paired statistical comparisons.

Do not change seeds after seeing results.

## 7. Metrics

Run-level metrics:

- throughput (ops/s)
- mean, P50, P95, P99, maximum end-to-end operation latency
- P95 by operation type
- final record count, leaf count, average occupancy
- peak JVM heap observed during the measured run
- run-boundary process/main-thread CPU, main-thread allocation, GC, and JIT-compilation deltas
- maintenance count and maintenance time
- records globally rewritten or locally moved
- leaves touched
- number of full-tree rebuilds
- LARS controller time
- empirical monitor-only throughput/P99 tax relative to `STATIC_BPLUS` on identical seeds
- adaptation events, commits, rollbacks, abstentions, no-trial commits, failed-trial candidates kept by the no-rollback ablation

Epoch-level metrics:

- throughput and latency by phase over fixed-size epochs
- maintenance/event counters over time

Adaptation-event metrics:

- diagnosis/action/outcome
- repair window
- records moved / leaves touched
- global locality fractions
- action cost
- trial length
- raw/net realized gain
- operation-mix drift
- expected diagnosis label where a diagnostic ground truth exists

## 8. Statistical analysis

The supplied Python analysis performs:

- median and 95% bootstrap confidence intervals across repetitions
- paired comparisons using identical seeds
- Wilcoxon signed-rank tests when enough paired runs exist
- Holm correction across multiple comparisons
- paired median percentage change for throughput and P99
- diagnostic match rates on labeled phases
- stable-control adaptation rate
- phase/epoch plots and workload-shift recovery summaries

Primary conclusions should rely on effect sizes and confidence intervals, not only p-values.

## 9. Calibration rule

`PILOT` may be used to calibrate LARS thresholds and trial sizes. Calibration must be done **before** the primary campaign and then frozen. Do not repeatedly tune parameters against the publication results.

The `PUBLICATION` configuration represented by this release is frozen. The archived source, campaign data, and recorded environment define the evaluated configuration; thresholds, seeds, workloads, and trial parameters must not be retuned after observing publication results.

## 10. Success and failure criteria

The study should be considered supportive only if the evidence jointly shows that LARS-B+ can:

- respond to the labeled local degradation modes with reasonable diagnostic accuracy;
- keep repair footprint materially below global reconstruction;
- avoid excessive false adaptations on the stable control;
- improve or preserve relevant service metrics under at least more than one changing/skewed workload;
- keep monitoring/controller overhead small enough that benefits are not erased;
- use rollback to avoid retaining candidates whose live trial fails.

A negative result is scientifically meaningful. If controller overhead dominates, diagnosis is unstable, or global/static baselines remain consistently superior, the paper must report that rather than tune the evaluation after the fact.

## 11. Reproducibility artifacts

The campaign produces:

- `environment.txt`
- `run_metrics.csv`
- `epoch_metrics.csv`
- `adaptation_events.csv`
- per-run raw CSV files under `raw/`

Keep the exact Git commit / source snapshot used for the final campaign together with these files.
