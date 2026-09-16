# LARS-B+ YCSB Extension — Source Provenance

Baseline: `LARS-BPlus-Pilot-v6.4-Full-Monitoring-Fast`  
Baseline ZIP SHA-256: `1547ae8902c26968b68e9b10e4488ec1a22999868afb5b0dbf84c7a029d058a2`

## Frozen scientific components

| Component | Changed | SHA-256 (baseline and extension) |
|---|---:|---|
| `LarsConfig.java` | NO | `f2ee0058f9c4aadaca95fc7882d4025e79ae0fcb7b436c1e5984418d43ddb1a8` |
| `LarsController.java` | NO | `866c975b30ae347d8215f51e46afea2408df210767670be53085017f77247721` |
| `BPlusTree.java` | NO | `45920c3be15fc50ac289322d250274cd71ca5f402f03260e0479fe32792483d5` |
| `LarsBPlusIndex.java` | NO | `8e640a5c5dcf35a10d2e741f65539f715fa0117dec0049055e63300ce78ab554` |

Consequently:

```text
LarsConfig changed: NO
LarsController changed: NO
BPlusTree restructuring semantics changed: NO
LARS monitoring semantics changed: NO
LARS trial/commit/rollback semantics changed: NO
Original seven workloads changed: NO
```

## Modified production files

- `CampaignProfile.java`: adds the isolated `YCSB_VALIDATION` run-size profile; existing enum order and values are preserved.
- `ExperimentCampaign.java`: adds the explicit YCSB focus, 90-run matrix, manifest, completion marker, and run-integrity gate.
- `WorkloadName.java`: adds `YCSB_A`, `YCSB_B`, and `YCSB_E` at the end of the enum.
- `WorkloadGenerator.java`: delegates only the three new workload names; the definitions of the original seven workloads are byte-for-byte unchanged within the method.

## New production files

- `YcsbWorkloadProfile.java`
- `ScrambledZipfianLongGenerator.java`
- `UniformScanLengthGenerator.java`
- `YcsbDerivedWorkloadGenerator.java`
- `YcsbGeneratorValidation.java`
- `YcsbValidationPreflight.java`

## Test changes

- Modified `ExperimentCampaignTest.java` to validate the frozen run size and reject duplicated or unresolved YCSB data.
- Added `YcsbDerivedWorkloadGeneratorTest.java`.
- Added `analysis/test_analyze_ycsb_validation.py`.

## Analysis and documentation additions

- `source/analysis/analyze_ycsb_validation.py`
- `YCSB_IMPLEMENTATION_REPORT.md`
- `YCSB_REGRESSION_AND_SMOKE_REPORT.md`
- `docs/YCSB_GENERATOR_VALIDATION.md`
- `docs/YCSB_REPRODUCTION.md`

No original PUBLICATION result file is included or overwritten by this extension.
