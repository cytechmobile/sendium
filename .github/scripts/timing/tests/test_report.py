"""Timing outputs are bounded, labelled and restricted to report metadata."""

import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import java_reports
import report


class ReportTests(unittest.TestCase):
    def test_allowlist_and_escaping(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            directory = root / "core/target/surefire-reports"
            directory.mkdir(parents=True)
            (directory / "TEST-example.xml").write_text(
                '<testsuite name="example|Suite" tests="1" failures="0" errors="0" skipped="0" time="1">'
                '<properties><property name="password" value="NEVER_PUBLISH"/></properties>'
                '<system-out>MESSAGE_PAYLOAD</system-out>'
                '<testcase classname="example.Suite" name="method(secret)" time="1"/>'
                '</testsuite>', encoding="utf-8")
            data = java_reports.collect(root, [Path("core/target/surefire-reports")])
            data["identity"] = report.identity({
                "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SHA": "a" * 40,
                "GITHUB_REPOSITORY": "owner/repo", "GITHUB_JOB": "tests",
                "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "SECRET_TOKEN": "NEVER_PUBLISH",
            })
            output = root / "output"
            report.write_report(data, output)
            all_output = "\n".join(p.read_text(encoding="utf-8") for p in output.iterdir())
            for sensitive in ("NEVER_PUBLISH", "MESSAGE_PAYLOAD", "method(secret)", "SECRET_TOKEN"):
                self.assertNotIn(sensitive, all_output)
            self.assertIn("example&#124;Suite", (output / "summary.md").read_text(encoding="utf-8"))
            self.assertEqual(json.loads((output / "timing.json").read_text())["identity"]["run_attempt"], "2")
            with (output / "suites.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual(list(csv.DictReader(stream))[0]["suite"], "example|Suite")

    def test_summary_is_bounded_and_aggregate_is_not_wall_time(self):
        suites = [{"module": "core", "phase": "surefire", "suite": f"Suite{i}", "seconds": i,
                   "tests": 1, "failures": 0, "errors": 0, "skipped": 0} for i in range(30)]
        data = {"suites": suites, "testcases": [], "counts": {"tests": 30, "failures": 0, "errors": 0, "skipped": 0},
                "issues": [], "aggregate_suite_seconds": 435,
                "build": {"elapsed_seconds": 100, "outcome": "success"}}
        text = report.summary(data)
        self.assertIn("Suite29", text)
        self.assertNotIn("| core | surefire | Suite4 |", text)
        self.assertIn("Aggregate suite execution", text)
        self.assertIn("Maven verify elapsed (build + tests)", text)
        self.assertIn("partial", text.lower())

    def test_missing_build_and_reports_are_visible(self):
        text = report.summary({"suites": [], "testcases": [], "counts": {},
                               "issues": [{"source": "core", "reason": "missing_reports"}],
                               "aggregate_suite_seconds": 0})
        self.assertIn("incomplete", text.lower())
        self.assertIn("unavailable", text.lower())
        self.assertIn("missing_reports", text)

    def test_csv_cells_cannot_be_spreadsheet_formulas(self):
        self.assertEqual(report.csv_cell("=HYPERLINK(\"bad\")"), "'=HYPERLINK(\"bad\")")
        self.assertEqual(report.csv_cell("example.Test"), "example.Test")


if __name__ == "__main__":
    unittest.main()
