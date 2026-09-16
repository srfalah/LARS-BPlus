# YCSB-Derived Generator Validation

- Operations generated per profile: 1,000,000
- Initial records: 1,000,000
- Validation seed: 1103515245
- Zipfian theta: 0.99
- Scramble: FNV-64
- Top 1% hottest-key share in the 10,000-key distribution check: 21.3134%
- Same seed produced identical operation fingerprints for every profile.

| Profile | SEARCH | UPDATE | INSERT | RANGE | Unique inserts | Scan min/max | Max uniform-bin deviation | Fingerprint |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| YCSB_A | 50.0634% | 49.9366% | 0.0000% | 0.0000% | 0 | n/a | n/a | `69362f013242f071` |
| YCSB_B | 94.9885% | 5.0115% | 0.0000% | 0.0000% | 0 | n/a | n/a | `af98d7a779519685` |
| YCSB_E | 0.0000% | 0.0000% | 5.0224% | 94.9776% | 50224 | 1/100 | 2.8402% | `726bd58b0c9f54d2` |

Result: `YCSB_GENERATOR_VALIDATION_OK`
