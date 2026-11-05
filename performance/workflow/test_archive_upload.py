"""Permanent-upload identity, retry, completion marker and failure handling."""

import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import archive_package
import archive_upload
import publish
import test_prepare
from aws_cli import AwsError
import test_archive_package


ROLE = "arn:aws:iam::123456789012:role/sendium-perf-archive"
BUCKET = "sendium-results-archive"


class FakeAws:
    region = "eu-west-1"

    def __init__(self):
        self.objects = {}
        self.puts = []
        self.fail_key = None
        self.bad_checksum = False
        self.outputs = {"RepositoryTag": "cytechmobile/sendium", "ArchiveRoleArn": ROLE,
                        "ResultsArchiveBucketName": BUCKET, "ArtifactBucketName": "temporary-run-artifacts"}

    def call(self, service, operation, **parameters):
        if service == "cloudformation":
            return {"Stacks": [{"StackId": "arn:aws:cloudformation:eu-west-1:123456789012:stack/bootstrap/id",
                                "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in self.outputs.items()]}]}
        if service == "sts":
            return {"Account": "123456789012", "Arn": "arn:aws:sts::123456789012:assumed-role/sendium-perf-archive/session"}
        key = parameters["key"]
        if operation == "put-object":
            self.puts.append(key)
            if key == self.fail_key:
                raise AwsError("Upload interrupted", "AccessDenied")
            if key in self.objects:
                raise AwsError("Already exists", "PreconditionFailed")
            assert parameters["if_none_match"] == "*"
            content = Path(parameters["body"]).read_bytes()
            assert parameters["checksum_sha256"] == base64.b64encode(hashlib.sha256(content).digest()).decode()
            self.objects[key] = content
            return {}
        if operation == "head-object":
            content = self.objects[key]
            return {"ContentLength": len(content), "ChecksumSHA256": "wrong" if self.bad_checksum else
                    base64.b64encode(hashlib.sha256(content).digest()).decode()}
        raise AssertionError((service, operation))

    def get(self, bucket, key, path):
        Path(path).write_bytes(self.objects[key])


class ArchiveUploadTests(unittest.TestCase):
    def fixture(self, root):
        req, source = test_archive_package.ArchivePackageTests().fixture(root)
        package = root / "package"
        manifest = archive_package.package(req, source, package)
        return req, source, package, manifest

    def test_uploads_verified_objects_and_commits_manifest_last(self):
        with tempfile.TemporaryDirectory() as directory:
            req, _, package, manifest = self.fixture(Path(directory))
            aws = FakeAws()
            outcome = archive_upload.upload(aws, req, package, "bootstrap", ROLE)
            self.assertEqual(outcome["status"], "archived")
            self.assertEqual(aws.puts[-1], manifest["prefix"] + "manifest.json")
            self.assertEqual(outcome["location"], "s3://" + BUCKET + "/" + aws.puts[-1])
            for item in [*manifest["files"], manifest["bundle"]]:
                self.assertEqual(aws.objects[manifest["prefix"] + item["path"]], (package / item["path"]).read_bytes())

    def test_native_failed_setup_and_partial_reports_are_archived_without_reclassification(self):
        for status in ("failed", "inconclusive"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                req = test_prepare.request({"application_runtime": "native", "ingress_mode": "smpp"})
                report = publish.unavailable(req, "SETUP_FAILURE")
                if status == "inconclusive":
                    report["validity"] = {"status": status, "exitCode": 0, "reasons": []}
                req, source = test_archive_package.ArchivePackageTests().fixture(root, req, report)
                package = root / "package"
                manifest = archive_package.package(req, source, package)
                aws = FakeAws()
                self.assertEqual(archive_upload.upload(aws, req, package, "bootstrap", ROLE)["status"], "archived")
                stored = json.loads(aws.objects[manifest["prefix"] + "results.json"])
                self.assertEqual(stored, report)
                self.assertEqual(stored["validity"]["status"], status)

    def test_identical_retry_and_repackaging_reuse_original_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, source, package, manifest = self.fixture(root)
            aws = FakeAws()
            archive_upload.upload(aws, req, package, "bootstrap", ROLE)
            original = dict(aws.objects)
            retry = root / "retry"
            archive_package.package(req, source, retry)
            self.assertEqual(archive_upload.upload(aws, req, retry, "bootstrap", ROLE)["exitCode"], 0)
            self.assertEqual(aws.objects, original)

    def test_partial_upload_has_no_completion_marker_and_can_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            req, _, package, manifest = self.fixture(Path(directory))
            aws = FakeAws()
            aws.fail_key = manifest["prefix"] + "evidence.tar.gz"
            with self.assertRaises(AwsError):
                archive_upload.upload(aws, req, package, "bootstrap", ROLE)
            self.assertNotIn(manifest["prefix"] + "manifest.json", aws.objects)
            aws.fail_key = None
            self.assertEqual(archive_upload.upload(aws, req, package, "bootstrap", ROLE)["status"], "archived")

    def test_conflicting_object_or_bad_remote_checksum_never_marks_completion(self):
        for corruption in ("object", "checksum"):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as directory:
                req, _, package, manifest = self.fixture(Path(directory))
                aws = FakeAws()
                if corruption == "object":
                    aws.objects[manifest["prefix"] + manifest["files"][0]["path"]] = b"conflict"
                else:
                    aws.bad_checksum = True
                with self.assertRaises(ValueError):
                    archive_upload.upload(aws, req, package, "bootstrap", ROLE)
                self.assertNotIn(manifest["prefix"] + "manifest.json", aws.objects)

    def test_altered_package_or_metadata_is_rejected_before_aws_access(self):
        for mutation in ("bytes", "prefix", "status"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                req, _, package, manifest = self.fixture(Path(directory))
                if mutation == "bytes":
                    (package / "summary.md").write_bytes(b"tampered")
                else:
                    manifest["prefix" if mutation == "prefix" else "observationStatus"] = "wrong"
                    (package / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                aws = FakeAws()
                with self.assertRaises(ValueError):
                    archive_upload.upload(aws, req, package, "bootstrap", ROLE)
                self.assertEqual(aws.puts, [])

    def test_rejects_wrong_role_repository_region_or_temporary_bucket(self):
        for mutation in ("role", "repository", "region", "bucket"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                req, _, package, _ = self.fixture(Path(directory))
                aws = FakeAws()
                role = ROLE
                if mutation == "role":
                    role = ROLE + "-other"
                elif mutation == "repository":
                    aws.outputs["RepositoryTag"] = "another/repository"
                elif mutation == "region":
                    aws.region = "us-east-1"
                else:
                    aws.outputs["ResultsArchiveBucketName"] = aws.outputs["ArtifactBucketName"]
                with self.assertRaises(ValueError):
                    archive_upload.upload(aws, req, package, "bootstrap", role)
                self.assertEqual(aws.puts, [])

    def test_conflicting_completed_manifest_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            req, _, package, manifest = self.fixture(Path(directory))
            aws = FakeAws()
            key = manifest["prefix"] + "manifest.json"
            conflicting = dict(manifest, observationStatus="valid")
            aws.objects[key] = json.dumps(conflicting).encode()
            with self.assertRaisesRegex(ValueError, "Conflicting archived manifest"):
                archive_upload.upload(aws, req, package, "bootstrap", ROLE)
            self.assertEqual(json.loads(aws.objects[key]), conflicting)

    def test_cli_writes_failure_outcome_without_exposing_exception_details(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "outcome.json"
            with patch("sys.argv", ["archive_upload.py", "--request", "missing", "--package", directory, "--output", str(path)]), \
                    patch.object(archive_upload.prepare, "read", side_effect=ValueError("private detail")), \
                    patch("sys.stderr"):
                self.assertEqual(archive_upload.main(), 1)
            outcome = json.loads(path.read_text())
            self.assertEqual(outcome["status"], "failed")
            self.assertNotIn("private detail", path.read_text())

    def test_workflow_runs_after_publication_even_when_it_fails(self):
        job = test_prepare.WORKFLOW["jobs"]["archive"]
        self.assertEqual(job["needs"], ["validate", "publish"])
        self.assertIn("always()", job["if"])
        self.assertNotIn("needs.publish.result == 'success'", job["if"])
        self.assertEqual(job["environment"], "sendium-performance-recovery")
        steps = job["steps"]
        packaging = next(index for index, step in enumerate(steps) if "archive_package.py" in step.get("run", ""))
        credentials = next(index for index, step in enumerate(steps) if "configure-aws" in step.get("uses", ""))
        self.assertLess(packaging, credentials)
        self.assertEqual(next(step for step in steps if step.get("name") == "Publish archive outcome")["if"], "always()")


if __name__ == "__main__":
    unittest.main()
