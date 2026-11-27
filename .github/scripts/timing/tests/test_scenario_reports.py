"""Packaged timing records remain advisory and do not sum nested intervals."""

from pathlib import Path
import csv
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import scenario_reports


class ScenarioReportTests(unittest.TestCase):
    def collect(self, content):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "scenarios.tsv"
            path.write_text(content, encoding="utf-8")
            return scenario_reports.collect(path, ["memory", "file"])

    def test_backend_identity_and_nested_intervals(self):
        data = self.collect(
            "jvm\tmemory\tpackaged\tstarted\t\n"
            "jvm\tmemory\tcapacity\tstarted\t\n"
            "jvm\tmemory\tcapacity\tpassed\t2.0\n"
            "jvm\tmemory\tpackaged\tpassed\t4.0\n"
            "jvm\tfile\tpackaged\tstarted\t\n"
            "jvm\tfile\tpackaged\tpassed\t3.0\n")
        self.assertEqual(data["issues"], [])
        self.assertEqual(len(data["scenarios"]), 3)
        self.assertEqual(data["scenarios"][0]["backend"], "memory")
        text = scenario_reports.summary(data)
        self.assertIn("overlap", text)
        self.assertNotIn("9.000", text)

    def test_failed_interrupted_and_unstarted_modes_are_not_passed(self):
        data = self.collect(
            "jvm\tmemory\tpackaged\tstarted\t\n"
            "jvm\tmemory\tcapacity\tstarted\t\n"
            "jvm\tmemory\tcapacity\tfailed\t2.0\n")
        self.assertEqual([row["outcome"] for row in data["scenarios"]], ["incomplete", "failed"])
        self.assertEqual({issue["reason"] for issue in data["issues"]},
                         {"unfinished_scenario", "missing_backend"})
        self.assertIsNone(data["scenarios"][0]["seconds"])

    def test_malformed_records_are_visible(self):
        for content in ("bad", "jvm\tmemory\tcapacity\tpassed\tNaN\n",
                        "jvm\tmemory\tcapacity\tstarted\t\njvm\tmemory\tcapacity\tpassed\t-1\n",
                        "jvm\tmemory\tcapacity\tpassed\t2\n"):
            with self.subTest(content=content):
                data = self.collect(content)
                self.assertTrue(data["issues"])
                self.assertNotIn("passed", [row["outcome"] for row in data["scenarios"]])

    def test_missing_report_is_not_zero_success(self):
        data = scenario_reports.collect(Path("not-present-scenario-report.tsv"), ["memory"])
        self.assertEqual(data["scenarios"], [])
        self.assertIn("missing_reports", [issue["reason"] for issue in data["issues"]])

    def test_cli_reconciles_outputs_without_publishing_untrusted_text(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            source = root / "input.tsv"
            source.write_text("jvm\tmemory\tpackaged\tstarted\t\n"
                              "jvm\tmemory\tpackaged\tpassed\t4.0\n"
                              "SECRET_PAYLOAD\n", encoding="utf-8")
            output = root / "output"
            step_summary = root / "summary.md"
            result = subprocess.run(
                [sys.executable, "-B", str(Path(scenario_reports.__file__)),
                 "--input", str(source), "--output", str(output), "--expected-backend", "memory"],
                env={**os.environ, "GITHUB_STEP_SUMMARY": str(step_summary)},
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            self.assertIn("::warning::", result.stdout)
            data = json.loads((output / "scenarios.json").read_text(encoding="utf-8"))
            rows = list(csv.DictReader(io.StringIO((output / "scenarios.csv").read_text(encoding="utf-8"))))
            self.assertEqual(len(rows), len(data["scenarios"]))
            self.assertEqual(float(rows[0]["seconds"]), data["scenarios"][0]["seconds"])
            self.assertEqual(step_summary.read_text(), (output / "scenarios.md").read_text())
            self.assertNotIn("SECRET_PAYLOAD", "".join(path.read_text() for path in output.iterdir()))
            self.assertIn("identity", data)

    def test_cli_output_failure_is_advisory(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            output = root / "existing-file"
            output.write_text("existing", encoding="utf-8")
            result = subprocess.run(
                [sys.executable, "-B", str(Path(scenario_reports.__file__)),
                 "--input", str(root / "missing.tsv"), "--output", str(output)],
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            self.assertIn("::warning::", result.stdout)
            self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
