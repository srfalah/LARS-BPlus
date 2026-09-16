# LARS-B+ Reproducibility Package v1.0.0

This repository preserves the frozen source code, publication datasets, analysis scripts, and derived evidence for:

**LARS-B+: Empirically Guarded Local B+-Tree Restructuring and the Cost of Continuous Monitoring**

**Author:** Sultan Alfalah  
**ORCID:** https://orcid.org/0009-0005-3240-9446  
**Affiliation:** Department of Computer Science, College of Computer and Information Sciences, Jouf University, Sakaka, Saudi Arabia

## Contents

- `source/` — frozen Java implementation, tests, Maven project file, and analysis scripts.
- `data/main/` — main publication campaign: 730 runs, 21,520 epochs, 2,385 adaptation events, 1,064 post-commit rows.
- `data/ycsb/` — YCSB-derived extension: 90 runs, 2,700 epochs, 20 adaptation events, 0 post-commit rows.
- `results/derived/` — recomputed statistical tables, integrity checks, runtime diagnostics, and figures.
- `docs/` — experiment protocol, YCSB reproduction procedure, generator validation, and source provenance.
- `reproduce.py` — recomputes the manuscript evidence from the archived CSV inputs; it does not rerun the benchmarks.
- `CITATION.cff` — citation metadata.
- `SHA256SUMS.txt` — SHA-256 checksums for the release contents.

The main and YCSB-derived campaigns are intentionally kept separate.

## Recompute the reported evidence

Install the Python dependencies:

```bash
python -m pip install -r source/analysis/requirements.txt
```

Then run from the repository root:

```bash
python reproduce.py
```

A successful run ends with:

```text
MANUSCRIPT_EVIDENCE_OK
```

The script verifies campaign dimensions and integrity properties, recomputes the paired main/YCSB effects, reconstructs the documented post-commit follow-up proxy, and refreshes outputs under `results/derived/`.

## Recompute YCSB runtime/sensitivity diagnostics

```bash
python source/analysis/deep_ycsb_review.py \
  --input-dir data/ycsb \
  --paired-effects results/derived/ycsb_paired_effects.csv \
  --output-dir results/derived/ycsb_runtime_diagnostics
```

A successful run ends with:

```text
YCSB_DEEP_REVIEW_OK
```

## Java regression tests

From `source/`:

```bash
mvn test
```

## Benchmark reruns

The archived CSVs are the preserved publication evidence. Fresh benchmark reruns are not required to reproduce the reported numerical analysis and are not expected to reproduce nanosecond timing identically because JVM/runtime effects remain active.

For the exact publication campaign and YCSB-derived extension procedures, see:

- `docs/EXPERIMENT_PROTOCOL.md`
- `docs/YCSB_REPRODUCTION.md`
- `data/main/environment.txt`
- `data/ycsb/environment.txt`
- `data/ycsb/campaign_manifest.txt`

## Scope

The YCSB extension is YCSB-derived rather than a full database-binding conformance run. The package preserves the manuscript's separation of the main and extension analysis families.

## License

No software or data license is asserted in this release. A license should be added only if the author explicitly chooses one.
