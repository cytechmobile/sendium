"""Offline RDS identity, coarse-bucket coverage and partial collection failure checks."""

import json
import unittest

import collect_rds_metrics as rds


def context(instance_class="db.t4g.small"):
    return {"database": {"identifier": "sendium-perf-run-gh-12345-1", "instanceClass": instance_class,
                "accountId": "123456789012", "partition": "aws", "region": "eu-west-1",
                "repository": "cytechmobile/sendium", "runIdentity": "gh-12345-1"},
            "phases": {"warmup": {"start": "2026-10-23T12:00:10Z", "end": "2026-10-23T12:01:10Z"},
                       "measurement": {"start": "2026-10-23T12:01:10Z", "end": "2026-10-23T12:03:20Z"},
                       "drain": {"start": "2026-10-23T12:03:20Z", "end": "2026-10-23T12:03:50Z"}}}


class FakeAws:
    region = "eu-west-1"

    def __init__(self, request=None, metrics=None):
        database = (request or context())["database"]
        self.description = {"DBInstanceIdentifier": database["identifier"], "DBInstanceClass": database["instanceClass"],
                            "DBInstanceArn": "arn:{partition}:rds:{region}:{accountId}:db:{identifier}".format(**database), "Engine": "postgres"}
        self.tags = [{"Key": key, "Value": value} for key, value in {
            "sendium:managed-by": "performance-tests", "sendium:repository": database["repository"],
            "sendium:run-identity": database["runIdentity"]}.items()]
        self.calls = []
        self.metrics = metrics
        self.counts = {}

    def call(self, service, operation, **parameters):
        self.calls.append((service, operation, parameters))
        if operation == "describe-db-instances":
            return {"DBInstances": [self.description]}
        if operation == "list-tags-for-resource":
            return {"TagList": self.tags}
        if operation != "get-metric-statistics":
            raise AssertionError("Unexpected AWS operation")
        name = parameters["metric_name"]
        self.counts[name] = self.counts.get(name, 0) + 1
        result = self.points(parameters)
        return self.metrics(name, self.counts[name], result) if self.metrics else result

    def points(self, parameters):
        name, period = parameters["metric_name"], parameters["period"]
        result = []
        for timestamp in rds.bucket_starts(rds.utc(parameters["start_time"]), rds.utc(parameters["end_time"]), period):
            point = {"Timestamp": rds.text(timestamp), "Unit": rds.METRICS[name]}
            point.update({statistic: {"Average": 10, "Minimum": 2, "Maximum": 20, "SampleCount": 60, "Sum": 3}[statistic]
                          for statistic in parameters["statistics"]})
            result.append(point)
        return {"Datapoints": list(reversed(result))}


def collect(aws=None, request=None):
    return rds.collect(aws or FakeAws(request), request or context(), sleep=lambda _: None)


class RdsMetricsTests(unittest.TestCase):
    def test_verifies_identity_and_tags_before_querying_exact_database_dimensions(self):
        aws = FakeAws()
        result = collect(aws)
        self.assertEqual([call[1] for call in aws.calls[:2]], ["describe-db-instances", "list-tags-for-resource"])
        self.assertTrue(result["database"]["verified"])
        self.assertEqual(result["namespace"], "AWS/RDS")
        self.assertEqual(len(result["metrics"]), 16)
        self.assertEqual(result["collectionIssues"], [])
        for item in result["metrics"]:
            self.assertEqual(item["attempts"], 1)
            self.assertEqual(item["collectionIssues"], [])
            self.assertEqual(item["coverage"]["missingBucketStarts"], [])
        for service, operation, parameters in aws.calls[2:]:
            self.assertEqual((service, operation), ("cloudwatch", "get-metric-statistics"))
            self.assertEqual(parameters["namespace"], "AWS/RDS")
            self.assertEqual(parameters["dimensions"], [{"Name": "DBInstanceIdentifier", "Value": context()["database"]["identifier"]}])

    def test_coarse_buckets_preserve_phase_boundaries_units_and_statistics(self):
        result = collect()
        self.assertEqual(result["phases"], context()["phases"])
        metrics = {item["name"]: item for item in result["metrics"]}
        cpu, credits = metrics["CPUUtilization"], metrics["CPUCreditUsage"]
        self.assertEqual((cpu["periodSeconds"], credits["periodSeconds"]), (60, 300))
        self.assertEqual((cpu["queryStart"], cpu["queryEnd"]), ("2026-10-23T12:00:00Z", "2026-10-23T12:04:00Z"))
        self.assertEqual((credits["queryStart"], credits["queryEnd"]), ("2026-10-23T12:00:00Z", "2026-10-23T12:05:00Z"))
        self.assertEqual(cpu["coverage"]["phases"]["measurement"]["expectedBuckets"], 3)
        self.assertEqual(credits["coverage"]["expectedBuckets"], 1)
        self.assertEqual(credits["coverage"]["phases"]["measurement"]["completeBuckets"], 1)
        self.assertEqual(credits["datapoints"], [{"timestamp": "2026-10-23T12:00:00Z", "sum": 3}])
        self.assertEqual(metrics["ReadLatency"]["unit"], "Seconds")
        self.assertEqual(metrics["FreeableMemory"]["unit"], "Bytes")
        self.assertEqual([point["timestamp"] for point in cpu["datapoints"]], sorted(point["timestamp"] for point in cpu["datapoints"]))

    def test_missing_samples_remain_missing_with_bounded_retries(self):
        aws = FakeAws(metrics=lambda *_: {"Datapoints": []})
        waits = []
        result = rds.collect(aws, context(), sleep=waits.append)
        self.assertEqual(waits, [30, 30])
        self.assertEqual(len(aws.calls), 2 + len(rds.METRICS) * rds.MAX_ATTEMPTS)
        cpu = result["metrics"][0]
        self.assertEqual(cpu["datapoints"], [])
        self.assertEqual(cpu["coverage"]["completeBuckets"], 0)
        self.assertEqual(len(cpu["coverage"]["missingBucketStarts"]), 4)
        self.assertEqual(cpu["collectionIssues"], ["RDS_METRIC_MISSING_BUCKETS"])

    def test_delayed_points_retry_only_incomplete_metrics_and_recover(self):
        aws = FakeAws(metrics=lambda name, attempt, result: {"Datapoints": []}
                      if name == "CPUUtilization" and attempt == 1 else result)
        result = collect(aws)
        self.assertEqual(aws.counts["CPUUtilization"], 2)
        self.assertTrue(all(count == 1 for name, count in aws.counts.items() if name != "CPUUtilization"))
        self.assertEqual(result["metrics"][0]["collectionIssues"], [])

    def test_partial_api_failures_preserve_good_metrics_and_sanitize_errors(self):
        def response(name, attempt, result):
            if name == "WriteLatency":
                raise RuntimeError("AWS stderr with private-database-password")
            return result
        result = collect(FakeAws(metrics=response))
        metrics = {item["name"]: item for item in result["metrics"]}
        self.assertTrue(metrics["ReadLatency"]["datapoints"])
        self.assertEqual(metrics["WriteLatency"]["datapoints"], [])
        self.assertIn("RDS_METRIC_COLLECTION_FAILED", metrics["WriteLatency"]["collectionIssues"])
        self.assertNotIn("private-database-password", json.dumps(result))

    def test_recovered_api_failure_retains_history_without_claiming_a_current_gap(self):
        def response(name, attempt, result):
            if name == "CPUUtilization" and attempt == 1:
                raise RuntimeError("Transient service failure")
            return result
        cpu = collect(FakeAws(metrics=response))["metrics"][0]
        self.assertEqual(cpu["collectionIssues"], [])
        self.assertEqual(cpu["coverage"]["missingBucketStarts"], [])
        self.assertEqual(cpu["attemptIssues"], [{"attempt": 1, "issues": ["RDS_METRIC_COLLECTION_FAILED"]}])

    def test_identity_and_ownership_mismatches_cannot_query_cloudwatch(self):
        for field, value in (("DBInstanceIdentifier", "other"), ("DBInstanceClass", "db.m7g.large"),
                             ("DBInstanceArn", "arn:aws:rds:eu-west-1:999999999999:db:other"), ("Engine", "mysql")):
            with self.subTest(field=field):
                aws = FakeAws()
                aws.description[field] = value
                result = collect(aws)
                self.assertEqual(result["collectionIssues"], ["RDS_IDENTITY_MISMATCH"])
                self.assertFalse(result["database"]["verified"])
                self.assertFalse(aws.counts)
        for tags in ([], FakeAws().tags[:-1], FakeAws().tags + [FakeAws().tags[0]],
                     [{"Key": "sendium:run-identity", "Value": "gh-999-1"}]):
            aws = FakeAws()
            aws.tags = tags
            self.assertEqual(collect(aws)["collectionIssues"], ["RDS_IDENTITY_MISMATCH"])
            self.assertFalse(aws.counts)

    def test_inaccessible_database_does_not_emit_unverified_samples(self):
        class Unavailable(FakeAws):
            def call(self, *_args, **_parameters):
                raise RuntimeError("AccessDenied: private information")
        result = collect(Unavailable())
        self.assertEqual(result["collectionIssues"], ["RDS_IDENTITY_UNAVAILABLE"])
        self.assertEqual(result["metrics"], [])
        self.assertNotIn("private information", json.dumps(result))

    def test_non_burstable_and_t2_database_credit_applicability(self):
        for instance_class in ("db.m7g.large", "db.t2.small"):
            with self.subTest(instance_class=instance_class):
                request = context(instance_class)
                aws = FakeAws(request)
                metrics = {item["name"]: item for item in collect(aws, request)["metrics"]}
                for name, item in metrics.items():
                    expected = not ("Credit" in name and instance_class == "db.m7g.large") and not (
                        name.startswith("CPUSurplus") and instance_class == "db.t2.small")
                    self.assertEqual(item["applicable"], expected)
                    self.assertEqual(item["attempts"], 1 if expected else 0)
                    self.assertEqual(item["collectionIssues"], [])
                    if not expected:
                        self.assertNotIn(name, aws.counts)
                        self.assertEqual(item["coverage"]["expectedBuckets"], 0)

    def test_unknown_burstable_family_is_explicit(self):
        request = context("db.t9.small")
        result = collect(FakeAws(request), request)
        self.assertEqual(result["collectionIssues"], ["RDS_CREDIT_APPLICABILITY_UNKNOWN"])
        self.assertIsNone(next(item for item in result["metrics"] if item["name"] == "CPUCreditBalance")["applicable"])

    def test_interrupt_retains_collected_samples_and_explicit_coverage(self):
        def response(name, _attempt, result):
            if name == "WriteLatency":
                raise KeyboardInterrupt
            return result
        result = collect(FakeAws(metrics=response))
        self.assertEqual(result["collectionIssues"], ["RDS_COLLECTION_INTERRUPTED"])
        self.assertTrue(result["metrics"][0]["datapoints"])
        self.assertTrue(result["metrics"][-1]["coverage"]["missingBucketStarts"])

    def test_interrupt_during_retry_wait_retains_partial_evidence(self):
        def stop(_seconds):
            raise KeyboardInterrupt
        aws = FakeAws(metrics=lambda name, _attempt, result: {"Datapoints": []} if name == "CPUUtilization" else result)
        result = rds.collect(aws, context(), sleep=stop)
        self.assertEqual(result["collectionIssues"], ["RDS_COLLECTION_INTERRUPTED"])
        self.assertTrue(result["metrics"][1]["datapoints"])
        self.assertEqual(result["metrics"][0]["coverage"]["completeBuckets"], 0)

    def test_later_partial_response_does_not_erase_a_complete_bucket(self):
        def response(name, attempt, result):
            if name == "CPUUtilization":
                if attempt == 1:
                    result["Datapoints"].pop()
                else:
                    del result["Datapoints"][0]["Average"]
            return result
        cpu = collect(FakeAws(metrics=response))["metrics"][0]
        self.assertEqual(cpu["coverage"]["completeBuckets"], 4)
        self.assertEqual(len(cpu["datapoints"]), 4)

    def test_invalid_context_is_rejected_before_aws_access(self):
        for change in (lambda req: req["database"].update(identifier="other-database"),
                       lambda req: req["database"].update(accountId="bad-account"),
                       lambda req: req["phases"]["measurement"].update(start="2026-10-23T12:01:11Z"),
                       lambda req: req["phases"]["drain"].update(end="2026-10-25T12:03:50Z"),
                       lambda req: req["phases"]["warmup"].update(start="2026-10-23T12:00:10")):
            aws, request = FakeAws(), context()
            change(request)
            with self.assertRaises(ValueError):
                collect(aws, request)
            self.assertEqual(aws.calls, [])
        aws = FakeAws()
        aws.region = "us-east-1"
        with self.assertRaisesRegex(ValueError, "region mismatch"):
            collect(aws)

    def test_zero_duration_optional_phases_have_no_expected_buckets(self):
        request = context()
        request["phases"]["warmup"]["start"] = request["phases"]["warmup"]["end"]
        request["phases"]["drain"]["end"] = request["phases"]["drain"]["start"]
        result = collect(request=request)
        for item in result["metrics"]:
            self.assertEqual(item["coverage"]["phases"]["warmup"]["expectedBuckets"], 0)
            self.assertEqual(item["coverage"]["phases"]["drain"]["expectedBuckets"], 0)

    def test_deadline_stops_new_queries_and_keeps_collected_evidence(self):
        now = [0]
        def response(_name, _attempt, result):
            now[0] += 100
            return result
        aws = FakeAws(metrics=response)
        result = rds.collect(aws, context(), clock=lambda: now[0], sleep=lambda _: None)
        self.assertEqual(sum(aws.counts.values()), 2)
        self.assertEqual(result["collectionIssues"], ["RDS_COLLECTION_DEADLINE"])
        self.assertTrue(result["metrics"][0]["datapoints"])
        self.assertEqual(result["metrics"][-1]["datapoints"], [])

    def test_malformed_samples_preserve_good_buckets_and_explicit_gaps(self):
        for invalid in (float("nan"), float("inf"), -1, 101, True, "10", None):
            with self.subTest(invalid=invalid):
                def response(name, _attempt, result):
                    if name == "CPUUtilization":
                        result["Datapoints"][0]["Average"] = invalid
                    return result
                cpu = collect(FakeAws(metrics=response))["metrics"][0]
                self.assertEqual(cpu["coverage"]["completeBuckets"], 3)
                self.assertEqual(len(cpu["datapoints"]), 4)
                self.assertIn("RDS_METRIC_INVALID_DATAPOINT", cpu["collectionIssues"])
                json.dumps(cpu, allow_nan=False)

    def test_bad_unit_outside_window_and_conflicting_duplicates_are_not_samples(self):
        for mutation in (lambda points: points[0].update(Unit="Bytes"),
                         lambda points: points[0].update(Timestamp="2026-10-23T11:59:00Z"),
                         lambda points: points[0].update(Timestamp="2026-10-23T12:00:30Z"),
                         lambda points: points.append({**points[0], "Average": 11})):
            def response(name, _attempt, result):
                if name == "CPUUtilization":
                    mutation(result["Datapoints"])
                return result
            cpu = collect(FakeAws(metrics=response))["metrics"][0]
            self.assertEqual(cpu["coverage"]["completeBuckets"], 3)
            self.assertEqual(len(cpu["datapoints"]), 3)
            self.assertTrue(cpu["collectionIssues"])

    def test_zero_values_are_preserved_as_observations(self):
        def response(_name, _attempt, result):
            for point in result["Datapoints"]:
                for key in ("Average", "Minimum", "Maximum", "Sum"):
                    if key in point:
                        point[key] = 0
            return result
        result = collect(FakeAws(metrics=response))
        self.assertEqual(result["metrics"][0]["datapoints"][0]["average"], 0)
        self.assertTrue(all(not item["collectionIssues"] for item in result["metrics"]))

    def test_invalid_response_shape_and_point_budget_are_bounded(self):
        for response in ({}, {"Datapoints": "wrong shape"}, {"Datapoints": [{}] * (rds.MAX_POINTS + 1)}):
            cpu = collect(FakeAws(metrics=lambda *_: response))["metrics"][0]
            self.assertEqual(cpu["datapoints"], [])
            self.assertIn("RDS_METRIC_COLLECTION_FAILED", cpu["collectionIssues"])


if __name__ == "__main__":
    unittest.main()
