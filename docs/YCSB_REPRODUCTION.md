# Reproducing the LARS-B+ YCSB-Derived Extension

For the closest runtime reproduction, use the recorded Windows 11 / Azul OpenJDK 21 environment in `data/ycsb/environment.txt`. Fresh JVM timing results are not expected to be nanosecond-identical; the archived CSVs are the preserved publication evidence.

## 1. Maven regression gate

```text
mvn test
```

Do not continue unless all tests pass.

## 2. Preflight main class

Run:

```text
edu.jouf.larsbplus.experiment.YcsbValidationPreflight
```

Program arguments:

```text
--out=docs/YCSB_GENERATOR_VALIDATION.md
```

The final line must be:

```text
YCSB_PREFLIGHT_OK
```

## 3. Ninety-run campaign

Main class:

```text
edu.jouf.larsbplus.experiment.ExperimentCampaign
```

Program arguments:

```text
--profile=YCSB_VALIDATION --focus=ycsb-validation --out=data/ycsb --heap=4g --controls=false --resume=false
```

Before launch, the campaign prints:

```text
YCSB validation workloads: 3
variants: 3
seeds: 10
expected measured runs: 90
```

Successful completion requires:

```text
YCSB_VALIDATION_OK
completed runs: 90/90
```

Do not edit thresholds, seeds, workloads, or variants after observing results.

## 4. Required result files

The isolated `data/ycsb/` directory contains:

```text
run_metrics.csv
epoch_metrics.csv
adaptation_events.csv
post_commit_region_metrics.csv
environment.txt
YCSB_VALIDATION_MANIFEST.txt
```

## 5. Analysis

```text
python source/analysis/analyze_ycsb_validation.py --input-dir=data/ycsb --output-dir=data/ycsb/analysis --expected-runs=90
```

Do not update the manuscript until the integrity checks finish with `YCSB_ANALYSIS_OK` and the results have been reviewed.
