import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import collect_cpu_credits as credits
import sample_process as sampler


class SamplingTests(unittest.TestCase):
    def state(self, ticks=100, host=1000, busy=500, start=42):
        return {"pid": 7, "processTicks": ticks, "startTicks": start, "rssBytes": 4096,
                "hostTicks": host, "hostBusyTicks": busy, "hostMemoryUsedBytes": 600,
                "hostMemoryTotalBytes": 1000, "cpuCount": 2}

    def test_proc_parser_handles_process_names_with_spaces_and_parentheses(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / "7").mkdir()
            fields = ["S"] + ["0"] * 21
            fields[11], fields[12], fields[19], fields[21] = "20", "5", "42", "3"
            (proc / "7" / "stat").write_text("7 (worker (with) spaces) " + " ".join(fields))
            (proc / "stat").write_text("cpu 100 20 30 400 10 5 6 7 99 88\n")
            (proc / "meminfo").write_text("MemTotal: 1000 kB\nMemAvailable: 400 kB\n")
            with patch.object(sampler.os, "sysconf", return_value=4096, create=True):
                result = sampler.read_state(7, proc)
            self.assertEqual(25, result["processTicks"])
            self.assertEqual(42, result["startTicks"])
            self.assertEqual(3 * 4096, result["rssBytes"])
            self.assertEqual(578, result["hostTicks"])
            self.assertEqual(168, result["hostBusyTicks"])
            self.assertEqual(600 * 1024, result["hostMemoryUsedBytes"])

    def test_cpu_denominators_and_missing_baseline_are_explicit(self):
        baseline = sampler.make_sample(self.state(), None, "now", None, 0, 100)
        self.assertIsNone(baseline["cpuPercent"])
        self.assertIsNone(baseline["hostCpuPercent"])
        sample = sampler.make_sample(self.state(ticks=300, host=1400, busy=800), self.state(),
                                     "end", "start", 1, 100)
        self.assertEqual(200, sample["cpuPercent"])  # Two fully occupied cores, not 200% host utilization.
        self.assertEqual(75, sample["hostCpuPercent"])
        self.assertEqual("start", sample["intervalStartTimestamp"])

    def test_pid_reuse_and_bad_counter_intervals_fail_sampling(self):
        with self.assertRaises(ValueError):
            sampler.make_sample(self.state(start=99), self.state(), "end", "start", 1, 100)
        with self.assertRaises(ValueError):
            sampler.make_sample(self.state(ticks=0), self.state(), "end", "start", 1, 100)

    def test_collection_is_bounded_and_emits_baseline_plus_periodic_points(self):
        class Clock:
            value = 0
            def monotonic(self):
                return self.value
            def sleep(self, value):
                self.value += value
        clock = Clock()
        output = io.StringIO()
        states = [self.state(), self.state(ticks=150, host=1200, busy=600), self.state(ticks=200, host=1400, busy=700)]
        with patch.object(sampler.time, "monotonic", clock.monotonic), patch.object(sampler.time, "sleep", clock.sleep), \
                patch.object(sampler.os, "sysconf", return_value=100, create=True), patch.object(sampler, "read_state", side_effect=states):
            self.assertEqual(3, sampler.collect(7, 2, 1, 3, output))
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertIsNone(rows[0]["cpuPercent"])
        self.assertEqual(50, rows[1]["cpuPercent"])
        self.assertEqual(rows[0]["timestamp"], rows[1]["intervalStartTimestamp"])
        with self.assertRaises(ValueError):
            sampler.collect(7, 100, 1, 2, io.StringIO())


class FakeAws:
    def __init__(self, missing=False, fail=False):
        self.calls = []
        self.missing = missing
        self.fail = fail

    def call(self, service, operation, **parameters):
        self.calls.append((service, operation, parameters))
        if operation == "describe-instances":
            return {"Reservations": [{"Instances": [{"InstanceId": "i-11111111", "InstanceType": "t3.small"},
                                                       {"InstanceId": "i-22222222", "InstanceType": "m7i.large"}]}]}
        if operation == "describe-instance-types":
            return {"InstanceTypes": [{"InstanceType": "t3.small", "BurstablePerformanceSupported": True},
                                       {"InstanceType": "m7i.large", "BurstablePerformanceSupported": False}]}
        if operation == "describe-instance-credit-specifications":
            return {"InstanceCreditSpecifications": [{"InstanceId": "i-11111111", "CpuCredits": "unlimited"}]}
        if self.fail:
            raise subprocess.CalledProcessError(1, "aws", stderr="must-not-leak-secret")
        if self.missing or parameters["metric_name"] == "CPUSurplusCreditsCharged":
            return {"Datapoints": []}
        return {"Datapoints": [{"Timestamp": "2026-10-15T10:00:00Z", parameters["statistics"][0]: 12}]}


class CreditTests(unittest.TestCase):
    def collect(self, aws):
        return credits.collect(aws, {"sendium": "i-11111111", "harness": "i-22222222"},
                               credits.utc("2026-10-15T10:01:00Z"), credits.utc("2026-10-15T10:11:00Z"))

    def test_burstable_mode_and_metrics_are_recorded_without_fabricating_missing_values(self):
        aws = FakeAws()
        result = self.collect(aws)
        sendium, harness = result["instances"]
        self.assertEqual("unlimited", sendium["mode"])
        self.assertEqual("not-applicable", harness["mode"])
        self.assertEqual([], harness["samples"])
        self.assertEqual(12, sendium["samples"][0]["balanceAverage"])
        self.assertIsNone(sendium["samples"][0]["surplusChargedSum"])
        calls = [params for _, operation, params in aws.calls if operation == "get-metric-statistics"]
        self.assertEqual(4, len(calls))
        self.assertEqual("2026-10-15T10:00:00+00:00", calls[0]["start_time"])
        self.assertEqual("2026-10-15T10:15:00+00:00", calls[0]["end_time"])
        self.assertTrue(all(call["period"] == 300 for call in calls))

    def test_missing_credit_data_and_api_failures_are_visible(self):
        missing = self.collect(FakeAws(missing=True))
        self.assertIn("CREDIT_BALANCE_MISSING", missing["instances"][0]["collectionIssues"])
        failure = self.collect(FakeAws(fail=True))
        self.assertIn("CREDIT_COLLECTION_FAILED", failure["instances"][0]["collectionIssues"])
        self.assertNotIn("must-not-leak-secret", json.dumps(failure))

    def test_invalid_windows_timezone_and_instance_reuse_are_rejected(self):
        with self.assertRaises(ValueError):
            credits.utc("2026-10-15T10:00:00")
        with self.assertRaises(ValueError):
            credits.collect(FakeAws(), {"sendium": "i-11111111", "harness": "i-11111111"},
                            credits.utc("2026-10-15T10:00:00Z"), credits.utc("2026-10-15T10:10:00Z"))

    def test_partial_metric_results_survive_a_later_collection_error(self):
        class LastMetricFails(FakeAws):
            def call(self, service, operation, **parameters):
                if parameters.get("metric_name") == "CPUSurplusCreditsCharged":
                    raise subprocess.CalledProcessError(1, "aws", stderr="must-not-leak-secret")
                return super().call(service, operation, **parameters)
        result = self.collect(LastMetricFails())["instances"][0]
        self.assertEqual(12, result["samples"][0]["balanceAverage"])
        self.assertIn("CREDIT_COLLECTION_FAILED", result["collectionIssues"])


if __name__ == "__main__":
    unittest.main()
