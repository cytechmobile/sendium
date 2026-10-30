"""Shared AWS/local workload must not silently become a reduced smoke test."""

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

import prepare
from test_prepare import ROOT, PRESET


class LoadLimitsTests(unittest.TestCase):
    def test_default_is_thirty_offered_with_unlimited_workers(self):
        self.assertEqual(PRESET["workload"]["offeredMessagesPerSecond"], 30)
        self.assertEqual(PRESET["workload"]["workerTps"], 0)
        self.assertEqual([PRESET["workload"][field] for field in ("warmupSeconds", "measurementSeconds", "drainTimeoutSeconds")], [120, 600, 120])
        self.assertEqual([PRESET["workload"][field] for field in ("providerResponseDelayMillis", "providerDlrDelayMillis")], [5, 1000])

    def test_local_changes_only_protocol_and_dlr_not_load_limits_or_delays(self):
        local = ROOT / "performance/local"
        sys.path.insert(0, str(local))
        spec = importlib.util.spec_from_file_location("local_load_lab", local / "lab.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        lab = Mock(source={"baseCommit": "ab" * 20}, workload_overrides={})
        for scenario, mode, dlr in (("http-dlr", "http", True), ("http-no-dlr", "http", False),
                                    ("smpp-dlr", "smpp", True), ("smpp-no-dlr", "smpp", False)):
            with self.subTest(scenario=scenario):
                result = module.request_for(lab, "gh-12345-1", scenario)
                expected = {**PRESET["workload"], "ingressMode": mode, "dlrEnabled": dlr}
                self.assertEqual(result["preset"]["workload"], expected)
                self.assertEqual(result["preset"]["measurement"], PRESET["measurement"])

        result = module.request_for(lab, "gh-12345-1", "http-no-dlr", offered_rate=100)
        expected = {**PRESET["workload"], "offeredMessagesPerSecond": 100, "ingressMode": "http", "dlrEnabled": False}
        self.assertEqual(result["preset"]["workload"], expected)
        self.assertEqual(module.request_for(lab, "gh-12345-1", "http-dlr")["preset"]["workload"], PRESET["workload"])


if __name__ == "__main__":
    unittest.main()
