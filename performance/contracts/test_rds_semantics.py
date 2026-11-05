"""Cross-language validation: tamper with actual JVM-produced RDS report fixtures."""

from copy import deepcopy
import json
from pathlib import Path
import unittest

from rds_semantics import validate_rds_resources
from semantics import validate_results


FIXTURES = Path(__file__).resolve().parent.parent / "harness/target/report-fixtures"


def fixture(label):
    paths = list(FIXTURES.glob("rds-" + label + "-*/results.json"))
    if not paths:
        raise unittest.SkipTest("Generate RDS report fixtures with Maven harness verification first")
    return json.loads(max(paths, key=lambda path: path.stat().st_mtime_ns).read_text(encoding="utf-8"))


class RdsSemanticTests(unittest.TestCase):
    def test_actual_jvm_outputs_validate_independently(self):
        for label in ("weighted", "boundary", "credit-sums", "partial", "wrong-identity", "unavailable"):
            with self.subTest(label=label):
                validate_results(fixture(label))

    def test_weighted_mean_cannot_be_replaced_by_mean_of_bucket_averages(self):
        report = fixture("weighted")
        report["resources"]["database"]["metrics"][0]["summary"]["measurement"]["average"] = 20
        with self.assertRaisesRegex(ValueError, "phase-only summary"):
            validate_results(report)

    def test_missing_buckets_cannot_be_claimed_complete(self):
        report = fixture("partial")
        cpu = report["resources"]["database"]["metrics"][0]
        cpu["coverage"]["completeBuckets"] = cpu["coverage"]["expectedBuckets"]
        cpu["coverage"]["missingBucketStarts"] = []
        with self.assertRaisesRegex(ValueError, "coverage counts"):
            validate_results(report)

    def test_missing_metrics_require_inconclusive_resource_reason(self):
        report = fixture("partial")
        report["validity"].update(status="valid", reasons=[])
        with self.assertRaisesRegex(ValueError, "RDS_RESOURCE_INCOMPLETE"):
            validate_results(report)

    def test_wrong_database_cannot_be_published_as_valid_or_merely_inconclusive(self):
        report = fixture("wrong-identity")
        report["validity"].update(status="inconclusive", reasons=[])
        with self.assertRaisesRegex(ValueError, "RDS_IDENTITY_MISMATCH"):
            validate_results(report)

    def test_bucket_crossing_phase_boundary_cannot_be_hidden(self):
        report = fixture("boundary")
        report["resources"]["database"]["metrics"][0]["datapoints"][1]["crossesPhaseBoundary"] = False
        with self.assertRaisesRegex(ValueError, "phase attribution"):
            validate_results(report)

    def test_no_fully_contained_credit_bucket_means_null_sum_not_zero(self):
        report = fixture("boundary")
        credits = report["resources"]["database"]["metrics"][13]["summary"]["measurement"]
        self.assertEqual(credits["completeBuckets"], credits["expectedBuckets"])
        self.assertEqual(credits["containedBuckets"], 0)
        self.assertIsNone(credits["sum"])
        credits["sum"] = 0
        with self.assertRaisesRegex(ValueError, "phase-only summary"):
            validate_results(report)

    def test_unit_statistic_and_percentage_corruption_is_rejected(self):
        original = fixture("weighted")
        for field, value in (("unit", "Bytes"), ("periodSeconds", 300), ("statistics", ["Sum"])):
            report = deepcopy(original)
            report["resources"]["database"]["metrics"][0][field] = value
            with self.assertRaises(ValueError):
                validate_results(report)
        report = deepcopy(original)
        report["resources"]["database"]["metrics"][0]["datapoints"][0]["maximum"] = 101
        with self.assertRaisesRegex(ValueError, "CPU percentage"):
            validate_results(report)

    def test_measurement_envelope_precision_is_bounded_and_directional(self):
        report = fixture("weighted")
        report["measurementMetadata"].update(measurementStartUtc="2026-10-23T12:01:00.000000123Z", observedDurationNanos=180_000_000_000 - 123 - 456)
        validate_rds_resources(report)
        report["measurementMetadata"]["measurementStartUtc"] = "2026-10-23T12:01:00.000003123Z"
        report["measurementMetadata"]["observedDurationNanos"] -= 3000
        with self.assertRaisesRegex(ValueError, "RDS_WINDOW_MISMATCH"):
            validate_rds_resources(report)

    def test_required_missing_source_is_explicit_while_legacy_reports_remain_supported(self):
        report = fixture("weighted")
        report["resources"].pop("database")
        with self.assertRaisesRegex(ValueError, "RDS_RESOURCES_MISSING"):
            validate_results(report)
        report["configuration"]["infrastructure"]["database"].pop("resourceMetricsRequired")
        validate_results(report)


if __name__ == "__main__":
    unittest.main()
