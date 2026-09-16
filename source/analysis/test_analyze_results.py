import unittest
import pandas as pd
from analyze_results import normalize_columns, event_summary, monitoring_impact_summary


class AnalysisRegressionTest(unittest.TestCase):
    def test_action_time_survives_normalization_and_sums(self):
        events = pd.DataFrame([
            dict(workload='MIXED', variant='LARS_FULL', seed=1, outcome='COMMIT', action_nanos=123),
            dict(workload='MIXED', variant='LARS_FULL', seed=1, outcome='ROLLBACK', action_nanos=456),
            dict(workload='MIXED', variant='LARS_FULL', seed=1, outcome='ABSTAIN', action_nanos=0),
        ])
        normalized = normalize_columns(events)
        self.assertIn('action_nanos', normalized)
        summary, _ = event_summary(normalized, pd.DataFrame({'workload': ['MIXED']}))
        self.assertEqual(summary.iloc[0].restructuring_nanos, 579)

    def test_old_cpu_share_name_is_not_empirical_overhead(self):
        normalized = normalize_columns(pd.DataFrame({'monitoring_overhead_pct': [1.0]}))
        self.assertEqual(normalized.iloc[0].controller_time_share_pct, 1.0)
        self.assertNotIn('monitoring_overhead_pct', normalized)

    def test_empirical_taxes(self):
        rows = [dict(workload='MIXED', variant=v, seed=1, throughput_ops_s=t, p99_latency_ns=p)
                for v, t, p in [('STATIC_BPLUS', 100, 100), ('LARS_MONITOR_ONLY', 80, 150)]]
        summary = monitoring_impact_summary(pd.DataFrame(rows)).iloc[0]
        self.assertEqual(summary.empirical_monitoring_throughput_tax_pct, 20)
        self.assertEqual(summary.empirical_monitoring_p99_tax_pct, 50)


if __name__ == '__main__':
    unittest.main()
