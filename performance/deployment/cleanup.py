"""Delete one owned benchmark root stack, then only its temporary S3 run prefix."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import signal
import sys
import time

from aws_cli import AwsCli, AwsError


class CleanupError(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise CleanupError(code)


def values(description, collection, name, value):
    return {item[name]: item[value] for item in description.get(collection, [])}


def validate_identity(plan):
    require(isinstance(plan, dict), "INVALID_PLAN_DOCUMENT")
    require(plan.get("schemaVersion") == 1, "UNSUPPORTED_PLAN_VERSION")
    for key, pattern in (("region", r"[a-z0-9-]{3,32}"), ("runIdentity", r"gh-[0-9]+-[0-9]+"),
                         ("repository", r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"),
                         ("bootstrapStack", r"[A-Za-z][A-Za-z0-9-]{0,127}"),
                         ("runStack", r"[A-Za-z][A-Za-z0-9-]{0,127}")):
        require(isinstance(plan.get(key), str) and re.fullmatch(pattern, plan[key]), "INVALID_RUN_IDENTITY")
    require(len(plan["runIdentity"]) <= 27 and plan["runStack"] != plan["bootstrapStack"], "INVALID_RUN_IDENTITY")


class Cleaner:
    def __init__(self, aws, plan, timeout_seconds=1800, clock=time.monotonic, sleep=time.sleep, poll_seconds=5):
        validate_identity(plan)
        require(0 < timeout_seconds <= 1800 and poll_seconds > 0, "INVALID_CLEANUP_BUDGET")
        self.aws, self.plan = aws, plan
        self.clock, self.sleep, self.poll_seconds = clock, sleep, poll_seconds
        self.deadline = clock() + timeout_seconds
        self.result = {"schemaVersion": 1, "runIdentity": plan["runIdentity"], "runStack": plan["runStack"],
                       "region": plan["region"], "prefix": "runs/" + plan["runIdentity"] + "/",
                       "stackDeleted": False, "artifactsDeleted": False, "stackDeleteRequests": 0,
                       "objectDeleteRequests": 0, "multipartAbortRequests": 0, "issues": [], "exitCode": 1}

    def call(self, service, operation, **parameters):
        remaining = self.deadline - self.clock()
        require(remaining > 0, "CLEANUP_TIMEOUT")
        # CLI transport honors the remaining total budget, including a slow final request.
        if hasattr(self.aws, "timeout_seconds"):
            self.aws.timeout_seconds = min(120, remaining)
        return self.aws.call(service, operation, **parameters)

    def pause(self, seconds=None):
        remaining = self.deadline - self.clock()
        require(remaining > 0, "CLEANUP_TIMEOUT")
        self.sleep(min(self.poll_seconds if seconds is None else seconds, remaining))

    def describe(self, identifier):
        try:
            descriptions = self.call("cloudformation", "describe-stacks", stack_name=identifier)["Stacks"]
        except AwsError as error:
            if error.code == "StackNotFound":
                return None
            raise
        require(len(descriptions) == 1, "AMBIGUOUS_STACK_DESCRIPTION")
        return descriptions[0]

    def bootstrap(self):
        description = self.describe(self.plan["bootstrapStack"])
        require(description is not None, "BOOTSTRAP_UNAVAILABLE")
        output = values(description, "Outputs", "OutputKey", "OutputValue")
        require(output.get("RepositoryTag") == self.plan["repository"], "BOOTSTRAP_REPOSITORY_MISMATCH")
        require(self.plan["runStack"] == output["RunStackPrefix"] + self.plan["runIdentity"], "RUN_STACK_NAMESPACE_MISMATCH")
        require(self.plan.get("bucket", output["ArtifactBucketName"]) == output["ArtifactBucketName"], "ARTIFACT_BUCKET_MISMATCH")
        require(description["StackId"].split(":")[3] == self.plan["region"], "BOOTSTRAP_REGION_MISMATCH")
        self.boot_id = description["StackId"]
        self.role = output["CloudFormationExecutionRoleArn"]
        self.bucket = output["ArtifactBucketName"]
        self.result["bucket"] = self.bucket

    def verify_root(self, description):
        identifier = description["StackId"]
        require(description["StackName"] == self.plan["runStack"] and identifier != self.boot_id,
                "RUN_STACK_NAME_MISMATCH")
        require(identifier.split(":")[:5] == self.boot_id.split(":")[:5], "RUN_STACK_ACCOUNT_OR_REGION_MISMATCH")
        require(not description.get("ParentId") and description.get("RootId", identifier) == identifier, "NESTED_STACK_REFUSED")
        require(description.get("RoleARN") == self.role, "RUN_STACK_ROLE_MISMATCH")
        tags = values(description, "Tags", "Key", "Value")
        require(tags.get("sendium:managed-by") == "performance-tests"
                and tags.get("sendium:repository") == self.plan["repository"]
                and tags.get("sendium:run-identity") == self.plan["runIdentity"], "RUN_STACK_OWNERSHIP_MISMATCH")
        require(self.result.get("stackId", identifier) == identifier, "RUN_STACK_ID_CHANGED")
        self.result["stackId"] = identifier
        return identifier

    def delete_stack(self):
        lookup = self.plan["runStack"]
        requested_at = None
        observed_deleting = False
        absent = False
        while True:
            description = self.describe(lookup)
            if description is None:
                if absent:
                    if lookup != self.plan["runStack"] and not self.confirm_name_deleted():
                        self.pause()
                        continue
                    self.result["stackDeleted"] = True
                    return
                # Confirm initial/request-response absence rather than trusting one observation.
                absent = True
                self.pause()
                continue
            absent = False
            lookup = self.verify_root(description)  # Pin subsequent calls to ARN, avoiding same-name replacement.
            status = description["StackStatus"]
            self.result["lastStackStatus"] = status
            if status == "DELETE_COMPLETE":
                if not self.confirm_name_deleted():
                    self.pause()
                    continue
                self.result["stackDeleted"] = True
                return
            if status == "DELETE_IN_PROGRESS":
                observed_deleting = True
                self.pause()
                continue
            if requested_at is not None:
                # A failed retry's previous status can remain visible briefly after DeleteStack.
                if status != "DELETE_FAILED" or (not observed_deleting and self.clock() - requested_at < 10):
                    self.pause()
                    continue
            require(self.result["stackDeleteRequests"] < 3, "STACK_DELETE_RETRIES_EXHAUSTED")
            try:
                # Reuse the verified attached service role; never replace a stack's role during cleanup.
                self.call("cloudformation", "delete-stack", stack_name=lookup)
            except AwsError as error:
                if error.code == "StackNotFound":
                    self.pause()
                    continue
                if error.code == "ValidationError" and status.endswith("_IN_PROGRESS"):
                    self.pause()  # Let creation/update/rollback settle, then request deletion again.
                    continue
                raise
            self.result["stackDeleteRequests"] += 1
            requested_at = self.clock()
            observed_deleting = False
            self.pause()

    def confirm_name_deleted(self):
        current = self.describe(self.plan["runStack"])
        if current is None:
            return True
        self.verify_root(current)  # A replacement ARN must not inherit the previous run's cleanup.
        return current["StackStatus"] == "DELETE_COMPLETE"

    def check_keys(self, entries):
        require(all(isinstance(entry.get("Key"), str) and entry["Key"].startswith(self.result["prefix"])
                    for entry in entries), "S3_KEY_OUTSIDE_RUN_PREFIX")

    def abort_uploads(self):
        while True:
            page = self.call("s3api", "list-multipart-uploads", bucket=self.bucket, prefix=self.result["prefix"], max_uploads=1000)
            uploads = page.get("Uploads", [])
            require(len(uploads) <= 1000, "MULTIPART_PAGE_EXCEEDS_BUDGET")
            self.check_keys(uploads)
            if not uploads:
                require(not page.get("IsTruncated"), "EMPTY_TRUNCATED_MULTIPART_PAGE")
                return
            failed = False
            for upload in uploads:
                try:
                    self.call("s3api", "abort-multipart-upload", bucket=self.bucket, key=upload["Key"], upload_id=upload["UploadId"])
                    self.result["multipartAbortRequests"] += 1
                except AwsError as error:
                    if error.code != "NoSuchUpload":
                        self.result["issues"].append({"code": "MULTIPART_ABORT_FAILED", "key": upload["Key"]})
                        failed = True
            if failed:
                return
            self.pause(0.1)  # Re-list the first page after mutation; never skip a shifted continuation page.

    def delete_objects(self):
        skip_marker = False
        marker = self.result["prefix"] + "control/run.json"
        while True:
            parameters = {"bucket": self.bucket, "prefix": self.result["prefix"], "max_keys": 1000}
            if skip_marker:
                parameters["start_after"] = marker
            page = self.call("s3api", "list-objects-v2", **parameters)
            entries = page.get("Contents", [])
            require(len(entries) <= 1000, "OBJECT_PAGE_EXCEEDS_BUDGET")
            self.check_keys(entries)
            if not entries:
                require(not page.get("IsTruncated"), "EMPTY_TRUNCATED_OBJECT_PAGE")
                if skip_marker:
                    skip_marker = False
                    continue  # Recheck the full prefix before removing its recovery marker.
                return
            # Retain the expiry identity until all other data/uploads are confirmed removed.
            non_marker = [entry for entry in entries if entry["Key"] != marker]
            if non_marker:
                entries = non_marker
            else:
                if page.get("IsTruncated"):
                    skip_marker = True
                    self.pause(0.1)
                    continue  # S3 may return a short page containing only the retained marker.
                if self.result["issues"]:
                    return
            request = {"Objects": [{"Key": entry["Key"]} for entry in entries], "Quiet": False}
            response = self.call("s3api", "delete-objects", bucket=self.bucket, delete=request)
            self.result["objectDeleteRequests"] += len(entries)
            if response.get("Errors"):
                self.result["issues"].append({"code": "OBJECT_DELETE_PARTIAL_FAILURE", "count": len(response["Errors"])})
                return  # Preserve the error; a later cleanup invocation can retry remaining keys.
            self.pause(0.1)

    def delete_artifacts(self):
        try:
            versioning = self.call("s3api", "get-bucket-versioning", bucket=self.bucket)
        except AwsError as error:
            if error.code != "NoSuchBucket":
                raise
            self.result["artifactsDeleted"] = True
            self.result["artifactBucketAbsent"] = True
            return
        require(versioning.get("Status") not in ("Enabled", "Suspended"), "ARTIFACT_BUCKET_VERSIONING_UNSUPPORTED")
        self.abort_uploads()
        self.delete_objects()
        # Independent verification catches partial operations and concurrent duplicate cleanup safely.
        objects = self.call("s3api", "list-objects-v2", bucket=self.bucket, prefix=self.result["prefix"], max_keys=1)
        uploads = self.call("s3api", "list-multipart-uploads", bucket=self.bucket, prefix=self.result["prefix"], max_uploads=1)
        require(not objects.get("Contents") and not objects.get("IsTruncated")
                and not uploads.get("Uploads") and not uploads.get("IsTruncated"), "ARTIFACT_PREFIX_NOT_EMPTY")
        self.result["artifactsDeleted"] = True

    def run(self):
        try:
            self.bootstrap()
            self.delete_stack()
            self.delete_artifacts()
        except CleanupError as error:
            self.result["issues"].append({"code": str(error)})
        except AwsError as error:
            self.result["issues"].append({"code": "AWS_CLEANUP_REQUEST_FAILED", "awsCode": error.code or "UNKNOWN"})
        except (KeyError, ValueError, TypeError, IndexError):
            self.result["issues"].append({"code": "INVALID_AWS_CLEANUP_RESPONSE"})
        except OSError:
            self.result["issues"].append({"code": "LOCAL_CLEANUP_IO_FAILED"})
        except KeyboardInterrupt:
            self.result["issues"].append({"code": "CLEANUP_INTERRUPTED"})
        self.result["exitCode"] = 0 if self.result["stackDeleted"] and self.result["artifactsDeleted"] and not self.result["issues"] else 1
        self.result["finishedAt"] = datetime.now(timezone.utc).isoformat()
        return self.result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, help="Original plan.json or staged.json; artifacts need not exist locally")
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    args = parser.parse_args()
    try:
        output = Path(args.output)
        require(output.parent.is_dir() and not output.exists(), "CLEANUP_OUTPUT_MUST_BE_NEW")
        plan = json.loads(Path(args.run).read_text(encoding="utf-8"))
        cleaner = Cleaner(AwsCli(plan["region"]), plan, timeout_seconds=args.timeout_seconds)
        def interrupt(*_):
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, interrupt)
        with output.open("x", encoding="utf-8") as stream:
            # Establish that the outcome file can be created before destructive requests.
            result = cleaner.run()
            json.dump(result, stream, indent=2, allow_nan=False)
            stream.write("\n")
        return result["exitCode"]
    except (OSError, ValueError, KeyError, TypeError, CleanupError):
        print("CLEANUP_INPUT_OR_OUTPUT_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
