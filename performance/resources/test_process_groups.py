"""Process-group accounting and live Linux CLI verification."""

import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import sample_process as sampler


def group_state(first=100, second=200, host=1000, busy=500):
    members = [{"name": "bearerbox", "pid": 7, "startTicks": 42, "processTicks": first, "rssBytes": 4096},
               {"name": "smsbox", "pid": 8, "startTicks": 43, "processTicks": second, "rssBytes": 8192}]
    return {"pid": None, "startTicks": None, "processTicks": first + second, "rssBytes": 12288,
            "processes": members, "hostTicks": host, "hostBusyTicks": busy,
            "hostMemoryUsedBytes": 600, "hostMemoryTotalBytes": 1000, "cpuCount": 2}


class ProcessGroupTests(unittest.TestCase):
    def test_cpu_and_rss_are_summed_with_one_host_observation_and_null_baseline(self):
        baseline = sampler.make_sample(group_state(), None, "start", None, 0, 100)
        self.assertIsNone(baseline["cpuPercent"])
        self.assertTrue(all(member["cpuPercent"] is None for member in baseline["processes"]))
        sample = sampler.make_sample(group_state(150, 350, 1400, 800), group_state(), "end", "start", 1, 100)
        self.assertEqual([50, 150], [member["cpuPercent"] for member in sample["processes"]])
        self.assertEqual(200, sample["cpuPercent"])
        self.assertEqual(12288, sample["rssBytes"])
        self.assertEqual(75, sample["hostCpuPercent"])
        self.assertEqual(600, sample["hostMemoryUsedBytes"])
        self.assertIsNone(sample["pid"])
        self.assertIsNone(sample["processStartTicks"])

    def test_every_members_identity_and_cpu_counter_are_checked(self):
        for field, value in (("pid", 9), ("startTicks", 99), ("name", "replacement")):
            changed = group_state(150, 350, 1400, 800)
            changed["processes"][1][field] = value
            with self.assertRaisesRegex(ValueError, "identity"):
                sampler.make_sample(changed, group_state(), "end", "start", 1, 100)
        # An increasing aggregate must not hide one member's regressing counter.
        with self.assertRaisesRegex(ValueError, "regressed"):
            sampler.make_sample(group_state(1000, 190, 1400, 800), group_state(), "end", "start", 1, 100)

    def test_explicit_members_are_bounded_unique_and_normalized(self):
        self.assertEqual([("bearerbox", 7), ("smsbox", 8)], sampler.normalize_members([("smsbox", 8), ("bearerbox", 7)]))
        for members in ([], [("bearerbox", 7)], [("same", 7), ("same", 8)], [("one", 7), ("two", 7)],
                        [("one", 0), ("two", 8)], [("UPPER", 7), ("two", 8)],
                        [("worker" + str(i), i + 1) for i in range(17)]):
            with self.assertRaises(ValueError):
                sampler.collect(members, 1, 1, 2, io.StringIO())

    def test_proc_read_does_not_drop_missing_or_dead_members_or_duplicate_host_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary)
            for pid in (7, 8):
                (proc / str(pid)).mkdir()
                fields = ["S"] + ["0"] * 21
                fields[11], fields[19], fields[21] = str(pid * 10), str(pid * 100), str(pid - 6)
                (proc / str(pid) / "stat").write_text(f"{pid} (worker (with) spaces) " + " ".join(fields))
            (proc / "stat").write_text("cpu 100 20 30 400 10 5 6 7 99 88\n")
            (proc / "meminfo").write_text("MemTotal: 1000 kB\nMemAvailable: 400 kB\n")
            with patch.object(sampler.os, "sysconf", return_value=4096, create=True), \
                    patch.object(sampler, "read_host", wraps=sampler.read_host) as host:
                state = sampler.read_group_state([("smsbox", 8), ("bearerbox", 7)], proc)
                self.assertEqual(1, host.call_count)
                self.assertEqual(150, state["processTicks"])
                self.assertEqual(3 * 4096, state["rssBytes"])
                self.assertEqual(600 * 1024, state["hostMemoryUsedBytes"])
                fields[0] = "Z"
                (proc / "8" / "stat").write_text("8 (zombie) " + " ".join(fields))
                with self.assertRaises(ValueError):
                    sampler.read_group_state([("bearerbox", 7), ("smsbox", 8)], proc)
                (proc / "8" / "stat").unlink()
                with self.assertRaises(OSError):
                    sampler.read_group_state([("bearerbox", 7), ("smsbox", 8)], proc)

    def test_group_stop_keeps_a_final_complete_interval_and_sample_budget(self):
        clock = [0]
        output = io.StringIO()
        with patch.object(sampler.time, "monotonic", lambda: clock[0]), \
                patch.object(sampler.time, "sleep", lambda delay: clock.__setitem__(0, clock[0] + delay)), \
                patch.object(sampler.os, "sysconf", return_value=100, create=True), \
                patch.object(sampler, "read_group_state", side_effect=[group_state(), group_state(150, 350, 1400, 800)]):
            self.assertEqual(2, sampler.collect([("bearerbox", 7), ("smsbox", 8)], 100, 1, 101, output, lambda: clock[0] >= 1))
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertIsNone(rows[0]["cpuPercent"])
        self.assertEqual(200, rows[1]["cpuPercent"])
        with self.assertRaises(ValueError):
            sampler.collect([("bearerbox", 7), ("smsbox", 8)], 10, 1, 2, io.StringIO())


@unittest.skipUnless(sys.platform.startswith("linux"), "Live /proc checks require Linux")
class LiveProcessGroupTests(unittest.TestCase):
    def test_cli_samples_two_live_processes_and_preserves_the_single_pid_format(self):
        processes = [subprocess.Popen([sys.executable, "-c", "import time; data=bytearray(1048576); time.sleep(30)"])
                     for _ in range(2)]
        try:
            command = [sys.executable, str(Path(sampler.__file__)), "--duration-seconds", "1",
                       "--interval-seconds", "0.1", "--max-samples", "12", "--output", "-"]
            result = subprocess.run([*command, "--process", f"bearerbox:{processes[0].pid}",
                                     "--process", f"smsbox:{processes[1].pid}"], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            rows = [json.loads(line) for line in result.stdout.splitlines()]
            self.assertGreaterEqual(len(rows), 2)
            self.assertTrue(all({member["pid"] for member in row["processes"]} == {process.pid for process in processes} for row in rows))
            self.assertTrue(all(row["rssBytes"] == sum(member["rssBytes"] for member in row["processes"]) for row in rows))
            result = subprocess.run([*command, "--pid", str(processes[0].pid)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(all("processes" not in json.loads(line) for line in result.stdout.splitlines()))
            processes[1].terminate(); processes[1].wait(timeout=3)
            result = subprocess.run([*command, "--process", f"bearerbox:{processes[0].pid}",
                                     "--process", f"smsbox:{processes[1].pid}"], capture_output=True, text=True, timeout=10)
            self.assertEqual(1, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertIn("RESOURCE_SAMPLING_FAILED", result.stderr)
        finally:
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=3)


if __name__ == "__main__":
    unittest.main()
