"""Scheduled expiry, Lambda packaging and operational-preflight contracts without AWS."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

from aws_cli import AwsError
from check_expiry import verify_expiry
from expiry import BotoAws, ExpiryCleaner, Sweeper, handler
from package_expiry import package
from test_cleanup import Clock, FakeAws, PLAN, PREFIX, ROOT_ID, ROLE


NOW = datetime(2026, 10, 16, 12, tzinfo=timezone.utc)
SETTINGS = {"region": PLAN["region"], "repository": PLAN["repository"], "bootstrapStack": PLAN["bootstrapStack"],
            "bucket": "benchmark-artifacts", "ttlSeconds": 14400}


class ExpiryAws(FakeAws):
    def __init__(self, status="CREATE_COMPLETE"):
        super().__init__(status)
        self.boot["Outputs"].append({"OutputKey": "RunResourceTtlMinutes", "OutputValue": "240"})
        if self.root:
            self.root["Tags"].append({"Key": "sendium:expires-at", "Value": (NOW - timedelta(hours=1)).isoformat()})
        self.markers = {PLAN["runIdentity"]: {**PLAN, "createdAt": (NOW - timedelta(hours=5)).isoformat(),
                                           "expiresAt": (NOW - timedelta(hours=1)).isoformat()}}
        self.objects.add(PREFIX + "control/run.json")
        self.stamp = NOW
        self.page_size = 1000
        self.fail_prefix_scan = False

    def call(self, service, operation, **parameters):
        if operation == "list-stacks":
            self.calls.append((service, operation, parameters))
            return {"StackSummaries": [deepcopy(self.root)] if self.root else []}
        if operation == "get-object":
            self.calls.append((service, operation, parameters))
            identity = parameters["key"].split("/")[1]
            if identity not in self.markers:
                raise AwsError("Missing", code="NoSuchKey")
            raw = json.dumps(self.markers[identity]).encode()
            return {"Body": io.BytesIO(raw), "ContentLength": len(raw)}
        if operation == "list-objects-v2":
            self.calls.append((service, operation, parameters))
            keys = sorted(key for key in self.objects if key.startswith(parameters["prefix"]))
            if "start_after" in parameters:
                keys = [key for key in keys if key > parameters["start_after"]]
            limit = min(self.page_size, parameters["max_keys"])
            start = int(parameters.get("continuation_token", 0))
            if parameters.get("delimiter"):
                if self.fail_prefix_scan:
                    raise AwsError("Scan failed", code="AccessDenied")
                prefixes = sorted({"/".join(key.split("/")[:2]) + "/" for key in keys})
                result = {"CommonPrefixes": [{"Prefix": prefix} for prefix in prefixes[start:start + limit]]}
                total = len(prefixes)
            else:
                result = {"Contents": [{"Key": key, "LastModified": self.stamp} for key in keys[start:start + limit]]}
                total = len(keys)
            result["IsTruncated"] = start + limit < total
            if result["IsTruncated"]:
                result["NextContinuationToken"] = str(start + limit)
            return result
        if operation == "list-multipart-uploads":
            self.calls.append((service, operation, parameters))
            entries = sorted(item for item in self.uploads if item[0].startswith(parameters["prefix"]))
            marker = (parameters.get("key_marker", ""), parameters.get("upload_id_marker", ""))
            entries = [item for item in entries if item > marker]
            limit = min(self.page_size, parameters["max_uploads"])
            result = {"Uploads": [{"Key": key, "UploadId": upload, "Initiated": self.stamp} for key, upload in entries[:limit]],
                      "IsTruncated": len(entries) > limit}
            if result["IsTruncated"]:
                result.update(NextKeyMarker=entries[limit - 1][0], NextUploadIdMarker=entries[limit - 1][1])
            return result
        return super().call(service, operation, **parameters)


def sweep(aws, budget=100):
    clock = Clock()
    return Sweeper(aws, SETTINGS, budget, now=NOW, clock=clock, sleep=clock.sleep, log=lambda _: None).run()


def mutations(aws):
    return [operation for _, operation, _ in aws.calls if operation in ("delete-stack", "delete-objects", "abort-multipart-upload")]


class ExpiryTests(unittest.TestCase):
    def test_expired_root_is_requested_then_next_sweep_finishes_and_purges(self):
        aws = ExpiryAws()
        first = sweep(aws)
        self.assertEqual(first["pending"], 1)
        self.assertEqual(first["failed"], 0)
        self.assertIn(PREFIX + "control/run.json", aws.objects)
        second = sweep(aws)
        self.assertEqual(second["deleted"], 1)
        self.assertEqual(second["failed"], 0)
        self.assertFalse(any(key.startswith(PREFIX) for key in aws.objects))
        self.assertEqual(sweep(aws)["deleted"], 0)

    def test_active_root_is_preserved_even_with_expired_marker(self):
        aws = ExpiryAws()
        aws.root["Tags"][-1]["Value"] = (NOW + timedelta(hours=1)).isoformat()
        self.assertEqual(sweep(aws)["failed"], 0)
        self.assertFalse(mutations(aws))

    def test_partial_staging_marker_expires_without_a_stack(self):
        aws = ExpiryAws(status=None)
        self.assertEqual(sweep(aws)["deleted"], 1)
        self.assertNotIn("delete-stack", mutations(aws))

    def test_partial_artifact_cleanup_keeps_marker_for_next_sweep(self):
        aws = ExpiryAws(status=None)
        aws.partial_delete = True
        self.assertGreater(sweep(aws)["failed"], 0)
        self.assertIn(PREFIX + "control/run.json", aws.objects)
        aws.partial_delete = False
        self.assertEqual(sweep(aws)["deleted"], 1)
        self.assertFalse(any(key.startswith(PREFIX) for key in aws.objects))

    def test_future_marker_preserves_an_unprovisioned_attempt(self):
        aws = ExpiryAws(status=None)
        aws.markers[PLAN["runIdentity"]].update(createdAt=(NOW - timedelta(hours=1)).isoformat(), expiresAt=(NOW + timedelta(hours=3)).isoformat())
        aws.stamp = NOW - timedelta(hours=8)
        self.assertEqual(sweep(aws)["failed"], 0)
        self.assertTrue(any(key.startswith(PREFIX) for key in aws.objects))
        self.assertTrue(any(key.startswith(PREFIX) for key, _ in aws.uploads))

    def test_marker_identity_or_lifetime_mismatch_never_falls_back_to_legacy_purge(self):
        for change in ({"repository": "another/repo"}, {"runIdentity": "gh-999-1"},
                       {"expiresAt": (NOW + timedelta(days=2)).isoformat()}):
            with self.subTest(change=change):
                aws = ExpiryAws(status=None)
                aws.stamp = NOW - timedelta(days=2)
                aws.markers[PLAN["runIdentity"]].update(change)
                self.assertGreater(sweep(aws)["failed"], 0)
                self.assertTrue(any(key.startswith(PREFIX) for key in aws.objects))

    def test_legacy_prefix_and_multipart_only_prefix_wait_for_the_latest_write(self):
        aws = ExpiryAws(status=None)
        aws.markers = {}
        self.assertFalse(mutations(aws))
        self.assertEqual(sweep(aws)["deleted"], 0)
        aws.stamp = NOW - timedelta(hours=5)
        self.assertGreaterEqual(sweep(aws)["deleted"], 1)
        aws = ExpiryAws(status=None)
        aws.objects.clear()
        aws.markers.clear()
        aws.stamp = NOW - timedelta(hours=5)
        self.assertGreaterEqual(sweep(aws)["deleted"], 1)
        self.assertFalse(aws.uploads)

    def test_unowned_or_nested_roots_are_never_deleted(self):
        for change in (lambda root: root.update(ParentId="parent"), lambda root: root["Tags"].clear()):
            with self.subTest(change=change):
                aws = ExpiryAws()
                change(aws.root)
                self.assertEqual(sweep(aws)["failed"], 0)
                self.assertFalse(mutations(aws))

    def test_invalid_expiry_is_visible_and_does_not_delete(self):
        aws = ExpiryAws()
        aws.root["Tags"][-1]["Value"] = "not-a-date"
        self.assertGreater(sweep(aws)["failed"], 0)
        self.assertFalse(mutations(aws))

    def test_failed_stack_delete_remains_visible_while_retry_is_requested(self):
        aws = ExpiryAws(status="DELETE_FAILED")
        report = sweep(aws)
        self.assertEqual(report["pending"], 1)
        self.assertTrue(any(issue["code"] == "EXPIRED_STACK_DELETE_FAILED" for issue in report["issues"]))

    def test_s3_discovery_failure_does_not_block_expired_compute_deletion(self):
        aws = ExpiryAws()
        aws.fail_prefix_scan = True
        report = sweep(aws)
        self.assertEqual(report["pending"], 1)
        self.assertIn("delete-stack", mutations(aws))
        self.assertGreater(report["failed"], 0)

    def test_paginated_prefixes_and_uploads_are_processed(self):
        aws = ExpiryAws(status=None)
        aws.page_size = 1
        report = sweep(aws)
        self.assertEqual(report["deleted"], 1)
        self.assertEqual(report["failed"], 0)
        self.assertTrue(any("continuation_token" in parameters for _, _, parameters in aws.calls))
        self.assertTrue(any("key_marker" in parameters for _, _, parameters in aws.calls))

    def test_scheduled_cleaner_rechecks_expiry_against_a_newly_appearing_active_root(self):
        aws = ExpiryAws()
        aws.root["Tags"][-1]["Value"] = (NOW + timedelta(hours=1)).isoformat()
        clock = Clock()
        result = ExpiryCleaner(aws, PLAN, now=NOW, clock=clock, sleep=clock.sleep).run()
        self.assertEqual(result["issues"], [{"code": "RUN_NOT_EXPIRED"}])
        self.assertFalse(mutations(aws))

    def test_partial_stack_scan_still_cleans_already_discovered_roots(self):
        aws = ExpiryAws()
        original = aws.call
        def call(service, operation, **parameters):
            if operation == "list-stacks":
                if parameters.get("next_token"):
                    raise AwsError("Later page failed", code="Throttling")
                return {"StackSummaries": [deepcopy(aws.root)], "NextToken": "later"}
            return original(service, operation, **parameters)
        with patch.object(aws, "call", side_effect=call):
            report = sweep(aws)
        self.assertEqual(report["pending"], 1)
        self.assertTrue(any(issue["code"] == "ROOT_SCAN_INCOMPLETE" for issue in report["issues"]))

    def test_failed_global_upload_scan_preserves_legacy_but_not_expired_marked_prefix(self):
        for marked in (False, True):
            with self.subTest(marked=marked):
                aws = ExpiryAws(status=None)
                aws.stamp = NOW - timedelta(days=2)
                if not marked:
                    aws.markers.clear()
                original = aws.call
                def call(service, operation, **parameters):
                    if operation == "list-multipart-uploads" and parameters["prefix"] == "runs/":
                        raise AwsError("Scan failed", code="AccessDenied")
                    return original(service, operation, **parameters)
                with patch.object(aws, "call", side_effect=call):
                    report = sweep(aws)
                self.assertEqual(any(key.startswith(PREFIX) for key in aws.objects), not marked)
                self.assertGreater(report["failed"], 0)

    def test_low_remaining_budget_defers_work_instead_of_starting_cleanup(self):
        aws = ExpiryAws()
        report = sweep(aws, budget=1)
        self.assertGreater(report["deferred"], 0)
        self.assertFalse(mutations(aws))

    def test_sdk_uses_s3_service_names_and_sanitizes_errors(self):
        from botocore.exceptions import ClientError
        aws = BotoAws.__new__(BotoAws)
        aws.session, aws.clients, aws.timeout_seconds = Mock(), {}, 9
        aws.session.client.return_value.get_object.return_value = {"ContentLength": 0}
        self.assertEqual(aws.call("s3api", "get-object", bucket="bucket", key="key"), {"ContentLength": 0})
        self.assertEqual(aws.session.client.call_args.args[0], "s3")
        aws.session.client.return_value.get_object.assert_called_once_with(Bucket="bucket", Key="key")
        aws.session.client.return_value.describe_stacks.side_effect = ClientError(
            {"Error": {"Code": "ValidationError", "Message": "Stack with id stack does not exist"}}, "DescribeStacks")
        with self.assertRaises(AwsError) as caught:
            aws.call("cloudformation", "describe-stacks", stack_name="stack")
        self.assertEqual(caught.exception.code, "StackNotFound")

    def test_lambda_event_cannot_select_a_target_or_bypass_expiry(self):
        environment = {"AWS_REGION": SETTINGS["region"], "REPOSITORY": SETTINGS["repository"],
                       "BOOTSTRAP_STACK": SETTINGS["bootstrapStack"], "ARTIFACT_BUCKET": SETTINGS["bucket"], "RUN_TTL_MINUTES": "240"}
        with patch.dict("os.environ", environment), patch("expiry.BotoAws"), patch("expiry.Sweeper") as sweeper:
            sweeper.return_value.run.return_value = {"failed": 0}
            context = Mock()
            context.get_remaining_time_in_millis.return_value = 840000
            handler({"forceDelete": True, "runIdentity": "gh-999-1"}, context)
            self.assertEqual(sweeper.call_args.args[1], SETTINGS)
            self.assertEqual(sweeper.call_args.args[2], 810)

    def test_package_is_reproducible_and_contains_only_cleanup_code(self):
        with tempfile.TemporaryDirectory() as directory:
            first = package(Path(directory) / "one")
            second = package(Path(directory) / "two")
            self.assertEqual(first["sha256"], second["sha256"])
            path = Path(directory) / "one" / first["file"]
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), first["sha256"])
            with zipfile.ZipFile(path) as archive:
                self.assertEqual(set(archive.namelist()), {"expiry.py", "cleanup.py", "aws_cli.py"})
                self.assertTrue(all((entry.external_attr >> 16) & 0o777 == 0o644 for entry in archive.infolist()))
                for name in archive.namelist():
                    compile(archive.read(name), name, "exec")
            subprocess.run([sys.executable, "-I", "-c",
                            "import sys; sys.path.insert(0, sys.argv[1]); import expiry; assert callable(expiry.handler)", str(path)],
                           check=True, capture_output=True, timeout=30)


class OperationalTests(unittest.TestCase):
    def configuration(self):
        function = "arn:aws:lambda:eu-west-1:123456789012:function:sendium-perf-expiry"
        rule = "arn:aws:events:eu-west-1:123456789012:rule/sendium-perf-expiry"
        output = {"ExpiryFunctionName": "sendium-perf-expiry", "ExpiryScheduleName": "sendium-perf-expiry",
                  "ExpiryFunctionArn": function, "ExpiryScheduleArn": rule, "ExpiryExpectedCodeSha256": "digest",
                  "ExpiryRoleArn": ROLE, "RepositoryTag": PLAN["repository"], "ArtifactBucketName": "bucket", "RunResourceTtlMinutes": "240"}
        responses = {"get-function-configuration": {"State": "Active", "LastUpdateStatus": "Successful", "CodeSha256": "digest",
            "FunctionArn": function, "Role": ROLE, "Handler": "expiry.handler", "Runtime": "python3.13", "Timeout": 840,
            "Environment": {"Variables": {"BOOTSTRAP_STACK": "bootstrap", "REPOSITORY": PLAN["repository"], "ARTIFACT_BUCKET": "bucket", "RUN_TTL_MINUTES": "240"}}},
            "get-function-concurrency": {}, "get-function-event-invoke-config": {"MaximumRetryAttempts": 0, "MaximumEventAgeInSeconds": 900},
            "describe-rule": {"State": "ENABLED", "ScheduleExpression": "rate(15 minutes)", "Arn": rule},
            "list-targets-by-rule": {"Targets": [{"Id": "Expiry", "Arn": function, "RetryPolicy": {"MaximumRetryAttempts": 0, "MaximumEventAgeInSeconds": 900}}]},
            "get-policy": {"Policy": json.dumps({"Statement": [{"Effect": "Allow", "Action": "lambda:InvokeFunction", "Principal": {"Service": "events.amazonaws.com"},
                 "Resource": function, "Condition": {"ArnLike": {"AWS:SourceArn": rule}, "StringEquals": {"AWS:SourceAccount": "123456789012"}}}]})}}
        return output, responses

    def test_operational_preflight_accepts_exact_deployed_contract(self):
        output, responses = self.configuration()
        verify_expiry(Mock(call=lambda service, operation, **_: responses[operation]), output, "bootstrap")

    def test_disabled_wrong_code_wrong_target_or_missing_permission_are_rejected(self):
        for operation, change in (("describe-rule", {"State": "DISABLED"}), ("get-function-configuration", {"CodeSha256": "wrong"}),
                                  ("list-targets-by-rule", {"Targets": []}), ("get-function-concurrency", {"ReservedConcurrentExecutions": 0}),
                                  ("get-policy", {"Policy": '{"Statement":[]}'})):
            with self.subTest(operation=operation):
                output, responses = self.configuration()
                responses[operation].update(change)
                with self.assertRaisesRegex(RuntimeError, "EXPIRY_NOT_OPERATIONAL"):
                    verify_expiry(Mock(call=lambda service, operation, **_: responses[operation]), output, "bootstrap")


if __name__ == "__main__":
    unittest.main()
