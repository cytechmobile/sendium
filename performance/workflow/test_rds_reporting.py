"""Fallback rendering of independently validated JVM-generated database reports."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import publish
from rds_markdown import database_markdown

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "resources"))
import collect_rds_metrics
import test_rds_metrics


def fixture(label):
    paths = list((Path(__file__).resolve().parent.parent / "harness/target/report-fixtures").glob("rds-" + label + "-*/results.json"))
    if not paths:
        raise unittest.SkipTest("Generate RDS report fixtures with Maven harness verification first")
    return json.loads(max(paths, key=lambda path: path.stat().st_mtime_ns).read_text(encoding="utf-8"))


class RdsReportingTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("SENDIUM_PUBLISH_JAR"), "Packaged harness is required for collector/import interoperability")
    def test_collector_output_is_accepted_by_packaged_finalizer_without_changing_workload(self):
        report = fixture("weighted")
        source = report["resources"]["database"]
        context = {"database": source["database"], "phases": source["phases"]}
        raw = collect_rds_metrics.collect(test_rds_metrics.FakeAws(context), context, sleep=lambda _: None)
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            original, evidence, inputs, output = (directory / name for name in ("original.json", "rds.json", "resource-inputs.json", "final"))
            original.write_text(json.dumps(report), encoding="utf-8")
            evidence.write_text(json.dumps(raw), encoding="utf-8")
            inputs.write_text(json.dumps({"schemaVersion": 1, "sampleBudget": 8192, "rdsMetrics": str(evidence)}), encoding="utf-8")
            completed = subprocess.run(["java", "-jar", os.environ["SENDIUM_PUBLISH_JAR"], "finalize-report", str(original), str(inputs), str(output)],
                                       capture_output=True, text=True, timeout=30)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            final = publish.read(output / "results.json")
            publish.validate_results(final)
            for field in ("provenance", "configuration", "overall", "cohortIntervals", "eventIntervals", "drain"):
                self.assertEqual(final[field], report[field], field)
            self.assertEqual(final["resources"]["database"]["metrics"][0]["summary"]["measurement"]["average"], 10)
            self.assertIn("## Database resources (RDS)", (output / "summary.md").read_text(encoding="utf-8"))

    def test_missing_evidence_is_na_and_legacy_reports_do_not_gain_a_database_claim(self):
        report = {"resources": {}, "configuration": {"infrastructure": {"database": {"resourceMetricsRequired": True}}}}
        self.assertIn("Utilization is **n/a**, not zero", database_markdown(report))
        report["configuration"]["infrastructure"]["database"].clear()
        self.assertEqual(database_markdown(report), "")

    def test_fallback_preserves_calculated_statistics_and_converts_only_display_units(self):
        report = fixture("weighted")
        original = json.dumps(report, sort_keys=True)
        markdown = publish.fallback_markdown(report)
        self.assertIn("CPUUtilization | % | 60 | 23.333 | 8.000 | 32.000 | n/a | 3/3 | 3 | 0", markdown)
        self.assertIn("FreeableMemory | MiB | 60 | 1.000 | 0.500 | 1.500", markdown)
        self.assertIn("ReadLatency | ms | 60 | 2.000 | 1.000 | 3.000", markdown)
        self.assertEqual(json.dumps(report, sort_keys=True), original)
        rows = markdown.split("## Database resources (RDS)", 1)[1].split("<details>", 1)[0].splitlines()
        self.assertTrue(all(row.count("|") == 11 for row in rows if row.startswith("|")))

    def test_boundary_credit_buckets_are_visible_without_a_fabricated_phase_sum(self):
        markdown = database_markdown(fixture("boundary"))
        self.assertIn("CPUCreditUsage | credits | 300 | n/a | n/a | n/a | n/a | 1/1 | 0 | 1", markdown)
        self.assertIn("warmup, measurement", markdown)
        self.assertIn("Boundary buckets are excluded", markdown)
        self.assertIn("not SQL-query latency", markdown)

    def test_partial_collection_issues_and_ownership_are_visible(self):
        report = fixture("partial")
        report["resources"]["database"]["metrics"][0]["collectionIssues"].append("RDS_METRIC_MISSING_BUCKETS")
        markdown = database_markdown(report)
        self.assertIn("Ownership verified: true", markdown)
        self.assertIn("2/3", markdown)
        self.assertIn("CPUUtilization: RDS_METRIC_MISSING_BUCKETS", markdown)

    def test_timeline_size_is_bounded_and_links_readers_to_complete_json(self):
        report = fixture("weighted")
        metric = report["resources"]["database"]["metrics"][0]
        metric["datapoints"] *= 100
        markdown = database_markdown(report)
        self.assertIn("Showing 240 of ", markdown)
        rows = markdown.split("<details><summary>Database timeline", 1)[1].splitlines()
        self.assertEqual(sum(row.startswith("|") for row in rows), 242)


if __name__ == "__main__":
    unittest.main()
