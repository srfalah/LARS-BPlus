import unittest

import numpy as np
import pandas as pd

import analyze_ycsb_validation as analysis


class YcsbAnalysisTest(unittest.TestCase):
    def test_effect_signs_are_explicit_and_correct(self):
        rows = []
        for workload in analysis.WORKLOADS:
            for seed in range(10):
                rows.extend([
                    {"workload": workload, "seed": seed, "variant": "STATIC_BPLUS",
                     "throughput_ops_s": 100.0, "p99_latency_ns": 100.0},
                    {"workload": workload, "seed": seed, "variant": "LARS_MONITOR_ONLY",
                     "throughput_ops_s": 90.0, "p99_latency_ns": 120.0},
                    {"workload": workload, "seed": seed, "variant": "LARS_FULL",
                     "throughput_ops_s": 80.0, "p99_latency_ns": 125.0},
                ])
        result = analysis.paired_effects(pd.DataFrame(rows))
        full_static = result[(result["comparison"] == "FULL_vs_STATIC")
                             & (result["workload"] == "YCSB_A")]
        self.assertAlmostEqual(-20.0, full_static[full_static["metric"] == "throughput_ops_s"].iloc[0]
                               ["paired_median_effect_pct"])
        self.assertAlmostEqual(-25.0, full_static[full_static["metric"] == "p99_latency_ns"].iloc[0]
                               ["paired_median_effect_pct"])
        monitor_static = result[(result["comparison"] == "MONITOR_vs_STATIC")
                                & (result["workload"] == "YCSB_A")]
        self.assertAlmostEqual(10.0, monitor_static[monitor_static["metric"] == "throughput_ops_s"].iloc[0]
                               ["paired_median_effect_pct"])
        self.assertAlmostEqual(20.0, monitor_static[monitor_static["metric"] == "p99_latency_ns"].iloc[0]
                               ["paired_median_effect_pct"])

    def test_holm_adjustment_is_monotone_in_sorted_order(self):
        raw = [0.01, 0.04, 0.03]
        adjusted = analysis.holm_adjust(raw)
        self.assertTrue(all(0.0 <= value <= 1.0 for value in adjusted))
        order = np.argsort(raw)
        sorted_adjusted = [adjusted[index] for index in order]
        self.assertEqual(sorted(sorted_adjusted), sorted_adjusted)


if __name__ == "__main__":
    unittest.main()
