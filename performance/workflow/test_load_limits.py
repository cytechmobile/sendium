"""The shared AWS workload must not silently become a reduced smoke test."""

import unittest

from test_prepare import PRESET


class LoadLimitsTests(unittest.TestCase):
    def test_default_is_thirty_offered_with_unlimited_workers(self):
        self.assertEqual(PRESET["workload"]["offeredMessagesPerSecond"], 30)
        self.assertEqual(PRESET["workload"]["workerTps"], 0)
        self.assertEqual([PRESET["workload"][field] for field in ("warmupSeconds", "measurementSeconds", "drainTimeoutSeconds")], [120, 600, 120])
        self.assertEqual([PRESET["workload"][field] for field in ("providerResponseDelayMillis", "providerDlrDelayMillis")], [5, 1000])


if __name__ == "__main__":
    unittest.main()
