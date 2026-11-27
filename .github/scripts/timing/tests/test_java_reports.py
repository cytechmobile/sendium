"""Java timing extraction reads reports, never test output or environment properties."""

from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import java_reports


class JavaReportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = Path("core/target/surefire-reports")
        (self.root / self.directory).mkdir(parents=True)

    def write(self, name, seconds="10", extra="", counts='tests="2" failures="0" errors="0" skipped="1"'):
        (self.root / self.directory / f"TEST-{name}.xml").write_text(
            f'<testsuite name="{name}" time="{seconds}" {counts}>'
            f'<testcase classname="{name}" name="method[private-argument]" time="3"/>'
            f'<testcase classname="{name}" name="other" time="1"><skipped/></testcase>'
            f"{extra}</testsuite>", encoding="utf-8")

    def test_suite_totals_and_top_entries(self):
        self.write("example.First", "10")
        self.write("example.Second", "20")
        result = java_reports.collect(self.root, [self.directory])
        self.assertEqual(result["aggregate_suite_seconds"], 30)
        self.assertEqual(result["counts"], {"tests": 4, "failures": 0, "errors": 0, "skipped": 2})
        self.assertEqual(len(result["testcases"]), 4)
        self.assertEqual(result["suites"][0]["phase"], "surefire")
        self.assertEqual(result["issues"], [])
        self.assertNotIn("private-argument", str(result))

    def test_missing_and_malformed_reports_are_incomplete(self):
        self.write("example.Valid")
        (self.root / self.directory / "TEST-bad.xml").write_text("<testsuite", encoding="utf-8")
        result = java_reports.collect(self.root, [self.directory, Path("app/target/surefire-reports")])
        self.assertEqual(len(result["suites"]), 1)
        self.assertEqual({issue["reason"] for issue in result["issues"]},
                         {"invalid_report", "missing_reports"})
        self.assertEqual(result["counts"]["tests"], 2)

    def test_invalid_numbers_are_not_silently_zero(self):
        for value in ("-1", "NaN", "Infinity", "unknown", "1,2"):
            with self.subTest(value=value):
                self.write("example.Invalid", value)
                result = java_reports.collect(self.root, [self.directory])
                self.assertFalse(result["suites"])
                self.assertEqual(result["issues"][0]["reason"], "invalid_report")

    def test_thousands_separator_and_nested_suites_do_not_double_count(self):
        (self.root / self.directory / "TEST-nested.xml").write_text(
            '<testsuites time="999"><testsuite name="example.Suite" time="1,234.5" '
            'tests="1" failures="1" errors="0" skipped="0">'
            '<testcase classname="example.Suite" name="fails" time="1"><failure>private body</failure>'
            '</testcase></testsuite></testsuites>', encoding="utf-8")
        result = java_reports.collect(self.root, [self.directory])
        self.assertEqual(result["aggregate_suite_seconds"], 1234.5)
        self.assertEqual(result["counts"]["failures"], 1)
        self.assertEqual(result["testcases"][0]["outcome"], "failed")
        self.assertNotIn("private body", str(result))

    def test_dtd_is_rejected_without_expanding_entities(self):
        for encoding in ("utf-8", "utf-16"):
            with self.subTest(encoding=encoding):
                (self.root / self.directory / "TEST-entity.xml").write_text(
                    '<!DOCTYPE testsuite [<!ENTITY payload "private">]><testsuite/>', encoding=encoding)
                result = java_reports.collect(self.root, [self.directory])
                self.assertEqual(result["issues"][0]["reason"], "invalid_report")

    def test_required_counts_and_negative_counts_are_rejected(self):
        for counts in ('tests="2"', 'tests="-1" failures="0" errors="0" skipped="0"'):
            with self.subTest(counts=counts):
                self.write("example.Invalid", counts=counts)
                self.assertFalse(java_reports.collect(self.root, [self.directory])["suites"])

    def test_no_reports_are_unavailable_not_zero_success(self):
        result = java_reports.collect(self.root, [self.directory])
        self.assertIsNone(result["aggregate_suite_seconds"])
        self.assertEqual(result["counts"], {})
        self.assertEqual(result["issues"][0]["reason"], "missing_reports")

    def test_cli_missing_reports_warns_and_publishes_partial_data(self):
        output = self.root / "output"
        result = subprocess.run(
            [sys.executable, "-B", str(Path(java_reports.__file__)), "--root", str(self.root),
             "--reports", str(self.directory), "--output", str(output)],
            env={**os.environ, "GITHUB_STEP_SUMMARY": str(self.root / "step-summary.md")},
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("::warning::", result.stdout)
        data = json.loads((output / "timing.json").read_text())
        self.assertIsNone(data["build"]["elapsed_seconds"])
        self.assertIsNone(data["aggregate_suite_seconds"])
        self.assertIn("incomplete", (self.root / "step-summary.md").read_text())

    def test_cli_output_failure_is_advisory(self):
        output = self.root / "not-a-directory"
        output.write_text("existing", encoding="utf-8")
        result = subprocess.run(
            [sys.executable, "-B", str(Path(java_reports.__file__)), "--root", str(self.root),
             "--reports", str(self.directory), "--output", str(output)],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("::warning::", result.stdout)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
