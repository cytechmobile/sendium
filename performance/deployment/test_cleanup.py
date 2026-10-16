"""Cleanup lifecycle tests with a stateful AWS substitute and a deterministic deadline."""

from copy import deepcopy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from aws_cli import AwsCli, AwsError
from cleanup import Cleaner, main


PLAN = {"schemaVersion": 1, "region": "eu-west-1", "repository": "cytechmobile/sendium",
        "runIdentity": "gh-12345-1", "bootstrapStack": "bootstrap", "runStack": "sendium-perf-run-gh-12345-1"}
PREFIX = "runs/gh-12345-1/"
BOOT_ID = "arn:aws:cloudformation:eu-west-1:123456789012:stack/bootstrap/bootstrap-id"
ROOT_ID = "arn:aws:cloudformation:eu-west-1:123456789012:stack/" + PLAN["runStack"] + "/root-id"
ROLE = "arn:aws:iam::123456789012:role/sendium-perf-cloudformation"


class Clock:
    def __init__(self):
        self.now = 0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakeAws:
    def __init__(self, status="CREATE_COMPLETE"):
        self.root = {"StackId": ROOT_ID, "StackName": PLAN["runStack"], "StackStatus": status,
                     "RoleARN": ROLE, "Tags": [{"Key": key, "Value": value} for key, value in {
                         "sendium:managed-by": "performance-tests", "sendium:repository": PLAN["repository"],
                         "sendium:run-identity": PLAN["runIdentity"]}.items()]} if status else None
        self.boot = {"StackId": BOOT_ID, "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in {
            "RepositoryTag": PLAN["repository"], "RunStackPrefix": "sendium-perf-run-",
            "ArtifactBucketName": "benchmark-artifacts", "CloudFormationExecutionRoleArn": ROLE}.items()]}
        self.objects = {PREFIX + "templates/network.yaml", PREFIX + "outputs/harness/results.json",
                        "runs/gh-12345-10/inputs/untouched", "runs/gh-999-1/inputs/untouched"}
        self.uploads = {(PREFIX + "inputs/partial", "upload-1"), ("runs/gh-999-1/inputs/partial", "upload-2")}
        self.calls = []
        self.versioning = {}
        self.delete_error = None
        self.describe_error = None
        self.delete_failures = 0
        self.delete_ticks = 0
        self.block_delete = False
        self.busy_once = False
        self.partial_delete = False
        self.abort_error = False
        self.deleted = False
        self.timeout_seconds = 120
        self.timeouts = []
        self.replacement = None

    def call(self, service, operation, **parameters):
        self.calls.append((service, operation, deepcopy(parameters)))
        self.timeouts.append(self.timeout_seconds)
        if service == "cloudformation":
            if operation == "describe-stacks":
                if parameters["stack_name"] == PLAN["bootstrapStack"]:
                    return {"Stacks": [deepcopy(self.boot)]}
                if self.describe_error:
                    raise self.describe_error
                if self.root and self.root["StackStatus"] == "DELETE_IN_PROGRESS" and not self.block_delete:
                    self.delete_ticks -= 1
                    if self.delete_ticks <= 0:
                        if self.delete_failures:
                            self.delete_failures -= 1
                            self.root["StackStatus"] = "DELETE_FAILED"
                        else:
                            self.root = None
                            self.deleted = True
                if parameters["stack_name"] == PLAN["runStack"] and self.deleted and self.replacement:
                    return {"Stacks": [deepcopy(self.replacement)]}
                if self.root is None:
                    raise AwsError("Missing", code="StackNotFound")
                return {"Stacks": [deepcopy(self.root)]}
            if operation == "delete-stack":
                if self.delete_error:
                    raise self.delete_error
                if self.busy_once:
                    self.busy_once = False
                    self.root["StackStatus"] = "ROLLBACK_COMPLETE"
                    raise AwsError("Busy", code="ValidationError")
                self.root["StackStatus"] = "DELETE_IN_PROGRESS"
                self.delete_ticks = 2
                return {}
        # No S3 mutation/listing is allowed before the owned stack has gone.
        assert service == "s3api"
        assert self.root is None or self.root["StackStatus"] == "DELETE_COMPLETE"
        assert parameters["bucket"] == "benchmark-artifacts"
        if operation == "get-bucket-versioning":
            return self.versioning
        if operation == "list-multipart-uploads":
            items = sorted(item for item in self.uploads if item[0].startswith(parameters["prefix"]))
            limit = parameters["max_uploads"]
            return {"Uploads": [{"Key": key, "UploadId": upload} for key, upload in items[:limit]], "IsTruncated": len(items) > limit}
        if operation == "abort-multipart-upload":
            if self.abort_error:
                raise AwsError("Denied", code="AccessDenied")
            item = (parameters["key"], parameters["upload_id"])
            if item not in self.uploads:
                raise AwsError("Already gone", code="NoSuchUpload")
            self.uploads.remove(item)
            return {}
        if operation == "list-objects-v2":
            keys = sorted(key for key in self.objects if key.startswith(parameters["prefix"]))
            limit = parameters["max_keys"]
            return {"Contents": [{"Key": key} for key in keys[:limit]], "IsTruncated": len(keys) > limit}
        if operation == "delete-objects":
            keys = [item["Key"] for item in parameters["delete"]["Objects"]]
            if self.partial_delete:
                self.objects.difference_update(keys[:-1])
                return {"Errors": [{"Key": keys[-1], "Code": "AccessDenied"}]}
            self.objects.difference_update(keys)
            return {"Deleted": [{"Key": key} for key in keys]}
        raise AssertionError("Unexpected operation: " + operation)


def clean(aws, timeout=100):
    clock = Clock()
    return Cleaner(aws, PLAN, timeout_seconds=timeout, clock=clock, sleep=clock.sleep, poll_seconds=1).run()


def codes(result):
    return [issue["code"] for issue in result["issues"]]


class CleanupTests(unittest.TestCase):
    def test_success_is_ordered_and_only_removes_this_runs_objects_and_uploads(self):
        aws = FakeAws()
        result = clean(aws)
        self.assertEqual(result["exitCode"], 0)
        self.assertTrue(result["stackDeleted"] and result["artifactsDeleted"])
        self.assertEqual(result["stackDeleteRequests"], 1)
        self.assertEqual(result["objectDeleteRequests"], 2)
        self.assertEqual(result["multipartAbortRequests"], 1)
        self.assertEqual(aws.objects, {"runs/gh-12345-10/inputs/untouched", "runs/gh-999-1/inputs/untouched"})
        self.assertEqual(aws.uploads, {("runs/gh-999-1/inputs/partial", "upload-2")})
        delete = [parameters for _, operation, parameters in aws.calls if operation == "delete-stack"]
        self.assertEqual(delete, [{"stack_name": ROOT_ID}])

    def test_repeated_cleanup_of_missing_stack_and_empty_prefix_succeeds(self):
        aws = FakeAws()
        self.assertEqual(clean(aws)["exitCode"], 0)
        result = clean(aws)
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["stackDeleteRequests"], 0)
        self.assertEqual(result["objectDeleteRequests"], 0)

    def test_partial_staging_with_no_stack_still_purges_run_artifacts(self):
        aws = FakeAws(status=None)
        self.assertEqual(clean(aws)["exitCode"], 0)
        self.assertFalse(any(operation == "delete-stack" for _, operation, _ in aws.calls))

    def test_partial_create_waits_out_busy_response_then_deletes(self):
        aws = FakeAws(status="CREATE_IN_PROGRESS")
        aws.busy_once = True
        self.assertEqual(clean(aws)["exitCode"], 0)
        self.assertEqual(len([entry for entry in aws.calls if entry[1] == "delete-stack"]), 2)

    def test_failed_delete_is_retried_without_retaining_or_force_deleting_resources(self):
        aws = FakeAws(status="ROLLBACK_FAILED")
        aws.delete_failures = 1
        result = clean(aws)
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["stackDeleteRequests"], 2)
        for _, operation, parameters in aws.calls:
            if operation == "delete-stack":
                self.assertEqual(set(parameters), {"stack_name"})

    def test_persistent_delete_failure_preserves_artifacts_and_surfaces_failure(self):
        aws = FakeAws(status="DELETE_FAILED")
        aws.delete_failures = 5
        result = clean(aws)
        self.assertIn("STACK_DELETE_RETRIES_EXHAUSTED", codes(result))
        self.assertEqual(result["stackDeleteRequests"], 3)
        self.assertFalse(result["artifactsDeleted"])
        self.assertFalse(any(service == "s3api" for service, _, _ in aws.calls))

    def test_existing_delete_is_observed_without_duplicate_delete_request(self):
        aws = FakeAws(status="DELETE_IN_PROGRESS")
        aws.delete_ticks = 2
        result = clean(aws)
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["stackDeleteRequests"], 0)

    def test_ownership_role_account_and_nested_stack_mismatches_are_refused(self):
        for mutation, expected in (
                (lambda root: root["Tags"].clear(), "RUN_STACK_OWNERSHIP_MISMATCH"),
                (lambda root: root.update(RoleARN="wrong-role"), "RUN_STACK_ROLE_MISMATCH"),
                (lambda root: root.update(ParentId="parent"), "NESTED_STACK_REFUSED"),
                (lambda root: root.update(StackId=ROOT_ID.replace("123456789012", "999999999999")), "RUN_STACK_ACCOUNT_OR_REGION_MISMATCH")):
            with self.subTest(expected=expected):
                aws = FakeAws()
                mutation(aws.root)
                result = clean(aws)
                self.assertIn(expected, codes(result))
                self.assertFalse(any(operation == "delete-stack" or service == "s3api" for service, operation, _ in aws.calls))

    def test_same_name_replacement_cannot_lose_its_artifacts(self):
        aws = FakeAws()
        aws.replacement = {**deepcopy(aws.root), "StackId": ROOT_ID.replace("root-id", "replacement-id")}
        result = clean(aws)
        self.assertIn("RUN_STACK_ID_CHANGED", codes(result))
        self.assertFalse(any(service == "s3api" for service, _, _ in aws.calls))

    def test_access_denied_is_not_stack_absence(self):
        aws = FakeAws()
        aws.describe_error = AwsError("Not permitted", code="AccessDenied")
        result = clean(aws)
        self.assertEqual(result["issues"], [{"code": "AWS_CLEANUP_REQUEST_FAILED", "awsCode": "AccessDenied"}])
        self.assertFalse(result["stackDeleted"])
        self.assertFalse(any(service == "s3api" for service, _, _ in aws.calls))

    def test_timeout_preserves_prefix_and_bounds_each_api_call(self):
        aws = FakeAws(status="DELETE_IN_PROGRESS")
        aws.block_delete = True
        result = clean(aws, timeout=3)
        self.assertIn("CLEANUP_TIMEOUT", codes(result))
        self.assertFalse(result["artifactsDeleted"])
        self.assertLessEqual(max(aws.timeouts), 3)

    def test_s3_pagination_does_not_skip_keys_or_uploads_after_mutation(self):
        aws = FakeAws(status=None)
        aws.objects.update(PREFIX + "object-" + str(index) for index in range(2005))
        aws.uploads.update((PREFIX + "multipart-" + str(index), str(index)) for index in range(1005))
        result = clean(aws)
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["objectDeleteRequests"], 2007)
        self.assertEqual(result["multipartAbortRequests"], 1006)

    def test_partial_object_failure_can_be_retried_in_a_new_cleanup(self):
        aws = FakeAws(status=None)
        aws.partial_delete = True
        first = clean(aws)
        self.assertIn("OBJECT_DELETE_PARTIAL_FAILURE", codes(first))
        self.assertFalse(first["artifactsDeleted"])
        aws.partial_delete = False
        self.assertEqual(clean(aws)["exitCode"], 0)

    def test_multipart_failure_remains_visible_while_completed_objects_are_removed(self):
        aws = FakeAws(status=None)
        aws.abort_error = True
        result = clean(aws)
        self.assertIn("MULTIPART_ABORT_FAILED", codes(result))
        self.assertFalse(result["artifactsDeleted"])
        self.assertFalse(any(key.startswith(PREFIX) for key in aws.objects))

    def test_versioned_bucket_does_not_report_delete_markers_as_erasure(self):
        aws = FakeAws(status=None)
        aws.versioning = {"Status": "Enabled"}
        result = clean(aws)
        self.assertIn("ARTIFACT_BUCKET_VERSIONING_UNSUPPORTED", codes(result))
        self.assertFalse(result["artifactsDeleted"])

    def test_wrong_prefix_in_service_response_is_never_deleted(self):
        aws = FakeAws(status=None)
        original = aws.call
        def call(service, operation, **parameters):
            if operation == "list-objects-v2":
                return {"Contents": [{"Key": "runs/another-run/important"}]}
            return original(service, operation, **parameters)
        with patch.object(aws, "call", side_effect=call):
            result = clean(aws)
        self.assertIn("S3_KEY_OUTSIDE_RUN_PREFIX", codes(result))
        self.assertFalse(any(operation == "delete-objects" for _, operation, _ in aws.calls))

    def test_interruption_is_reported_without_deleting_artifacts(self):
        aws = FakeAws()
        with patch.object(aws, "call", side_effect=KeyboardInterrupt):
            result = clean(aws)
        self.assertIn("CLEANUP_INTERRUPTED", codes(result))
        self.assertEqual(result["exitCode"], 1)

    def test_concurrent_cleanup_can_remove_upload_before_our_abort(self):
        aws = FakeAws(status=None)
        original = aws.call
        def call(service, operation, **parameters):
            if operation == "abort-multipart-upload":
                aws.uploads.discard((parameters["key"], parameters["upload_id"]))
            return original(service, operation, **parameters)
        with patch.object(aws, "call", side_effect=call):
            self.assertEqual(clean(aws)["exitCode"], 0)

    def test_concurrent_stack_delete_between_describe_and_request_is_rechecked(self):
        aws = FakeAws()
        original = aws.call
        def call(service, operation, **parameters):
            if operation == "delete-stack":
                aws.root = None
                raise AwsError("Already gone", code="StackNotFound")
            return original(service, operation, **parameters)
        with patch.object(aws, "call", side_effect=call):
            self.assertEqual(clean(aws)["exitCode"], 0)

    def test_absent_bucket_is_already_clean(self):
        aws = FakeAws(status=None)
        original = aws.call
        def call(service, operation, **parameters):
            if operation == "get-bucket-versioning":
                raise AwsError("Already deleted", code="NoSuchBucket")
            return original(service, operation, **parameters)
        with patch.object(aws, "call", side_effect=call):
            result = clean(aws)
        self.assertEqual(result["exitCode"], 0)
        self.assertTrue(result["artifactBucketAbsent"])

    def test_cli_records_failed_cleanup_and_refuses_existing_output_before_aws(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory) / "plan.json"
            plan.write_text(json.dumps(PLAN), encoding="utf-8")
            output = Path(directory) / "outcome.json"
            aws = FakeAws()
            aws.describe_error = AwsError("Denied", code="AccessDenied")
            clock = Clock()
            cleaner = Cleaner(aws, PLAN, clock=clock, sleep=clock.sleep)
            arguments = ["cleanup.py", "--run", str(plan), "--output", str(output)]
            with patch("sys.argv", arguments), patch("cleanup.Cleaner", return_value=cleaner):
                self.assertEqual(main(), 1)
            self.assertEqual(json.loads(output.read_text())["issues"][0]["awsCode"], "AccessDenied")
            with patch("sys.argv", arguments), patch("cleanup.AwsCli") as transport, patch("sys.stderr"):
                self.assertEqual(main(), 1)
                transport.assert_not_called()


class TransportTests(unittest.TestCase):
    def test_only_exact_stack_absence_is_normalized_without_exposing_stderr(self):
        for error_code, message, expected in (
                ("ValidationError", "Stack with id test does not exist", "StackNotFound"),
                ("ValidationError", "Stack with id another does not exist", "ValidationError"),
                ("AccessDenied", "Stack with id test does not exist", "AccessDenied")):
            stderr = "An error occurred (" + error_code + ") when calling DescribeStacks: " + message + " private-value"
            failure = subprocess.CalledProcessError(1, ["aws"], stderr=stderr)
            for operation in ("describe-stacks", "delete-stack"):
                with self.subTest(expected=expected, operation=operation), patch("aws_cli.subprocess.run", side_effect=failure):
                    with self.assertRaises(AwsError) as caught:
                        AwsCli("eu-west-1").call("cloudformation", operation, stack_name="test")
                    self.assertEqual(caught.exception.code, expected)
                    self.assertNotIn("private-value", str(caught.exception))

    def test_listing_disables_cli_auto_pagination_and_uses_remaining_deadline(self):
        aws = AwsCli("eu-west-1")
        aws.timeout_seconds = 2.5
        with patch("aws_cli.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout="{}")) as run:
            aws.call("s3api", "list-objects-v2", bucket="bucket", prefix=PREFIX, max_keys=1000)
        command = run.call_args.args[0]
        self.assertIn("--no-paginate", command)
        self.assertEqual(run.call_args.kwargs["timeout"], 2.5)

    def test_large_delete_batches_use_temporary_parameter_files(self):
        request = {"Objects": [{"Key": PREFIX + "x" * 900 + str(index)} for index in range(1000)], "Quiet": False}
        used = []
        def request_call(command, **parameters):
            parameter = command[command.index("--delete") + 1]
            self.assertTrue(parameter.startswith("file://"))
            path = Path(parameter[len("file://"):])
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), request)
            used.append(path)
            return subprocess.CompletedProcess(command, 0, stdout="{}")
        with patch("aws_cli.subprocess.run", side_effect=request_call):
            AwsCli("eu-west-1").call("s3api", "delete-objects", bucket="bucket", delete=request)
        self.assertFalse(used[0].exists())


if __name__ == "__main__":
    unittest.main()
