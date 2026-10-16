"""Scheduled, repository-scoped expiry. No application/harness code is packaged here."""

from datetime import datetime, timedelta, timezone
import json
import os
import re
import time

from aws_cli import AwsError
from cleanup import Cleaner, CleanupError, require, values


LIVE_STATUSES = ["CREATE_IN_PROGRESS", "CREATE_FAILED", "CREATE_COMPLETE", "ROLLBACK_IN_PROGRESS",
                 "ROLLBACK_FAILED", "ROLLBACK_COMPLETE", "DELETE_IN_PROGRESS", "DELETE_FAILED",
                 "UPDATE_IN_PROGRESS", "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS", "UPDATE_COMPLETE", "UPDATE_FAILED",
                 "UPDATE_ROLLBACK_IN_PROGRESS", "UPDATE_ROLLBACK_FAILED", "UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS",
                 "UPDATE_ROLLBACK_COMPLETE", "REVIEW_IN_PROGRESS", "IMPORT_IN_PROGRESS", "IMPORT_COMPLETE",
                 "IMPORT_ROLLBACK_IN_PROGRESS", "IMPORT_ROLLBACK_FAILED", "IMPORT_ROLLBACK_COMPLETE"]
IDENTITY = re.compile(r"gh-[0-9]+-[0-9]+")
MAX_PAGES = 100
MARKER_LIMIT = 16384


def utc(value):
    result = value if isinstance(value, datetime) else datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(result.utcoffset() is not None, "INVALID_EXPIRY_TIMESTAMP")
    return result.astimezone(timezone.utc)


def emit(document):
    print(json.dumps(document, allow_nan=False, separators=(",", ":")))


class BotoAws:
    """Bounded SDK equivalent of the CLI transport; boto3 is supplied by Lambda."""
    PARAMETERS = {"stack_name": "StackName", "stack_status_filter": "StackStatusFilter", "next_token": "NextToken",
                  "bucket": "Bucket", "prefix": "Prefix", "delimiter": "Delimiter", "max_keys": "MaxKeys",
                  "continuation_token": "ContinuationToken", "max_uploads": "MaxUploads", "key_marker": "KeyMarker",
                  "upload_id_marker": "UploadIdMarker", "key": "Key", "upload_id": "UploadId", "delete": "Delete", "start_after": "StartAfter"}

    def __init__(self, region):
        import boto3
        self.session = boto3.session.Session(region_name=region)
        self.timeout_seconds = 10
        self.clients = {}

    def call(self, service, operation, **parameters):
        from botocore.config import Config
        from botocore.exceptions import BotoCoreError, ClientError
        # Keep connection+response waits below the caller's remaining budget; no hidden SDK retry loop.
        timeout = max(0.05, min(5, int(self.timeout_seconds * 10 / 3) / 10))
        key = (service, timeout)
        if key not in self.clients:
            self.clients[key] = self.session.client("s3" if service == "s3api" else service, config=Config(connect_timeout=timeout, read_timeout=timeout,
                                                                          retries={"total_max_attempts": 1}))
        try:
            arguments = {self.PARAMETERS[name]: value for name, value in parameters.items()}
            return getattr(self.clients[key], operation.replace("-", "_"))(**arguments)
        except ClientError as error:
            detail = error.response.get("Error", {})
            code = detail.get("Code", "AWS_REQUEST_FAILED")
            if (service == "cloudformation" and operation in ("describe-stacks", "delete-stack") and code == "ValidationError"
                    and detail.get("Message") == "Stack with id " + str(parameters.get("stack_name")) + " does not exist"):
                code = "StackNotFound"
            raise AwsError("Expiry SDK request failed", code=code) from None
        except BotoCoreError:
            raise AwsError("Expiry SDK transport failed", code="AWS_TRANSPORT_FAILED") from None


class ExpiryCleaner(Cleaner):
    def __init__(self, *args, now, **kwargs):
        super().__init__(*args, **kwargs)
        self.now = now

    def verify_root(self, description):
        identifier = super().verify_root(description)
        tags = values(description, "Tags", "Key", "Value")
        require(utc(tags["sendium:expires-at"]) <= self.now, "RUN_NOT_EXPIRED")
        return identifier

    def pause(self, seconds=None):
        if not self.result["stackDeleted"] and (self.result["stackDeleteRequests"] or self.result.get("lastStackStatus") == "DELETE_IN_PROGRESS"):
            # Deletion continues server-side. The next sweep confirms completion and removes artifacts.
            raise CleanupError("STACK_DELETE_PENDING")
        super().pause(seconds)


class Sweeper:
    def __init__(self, aws, settings, budget_seconds, now=None, clock=time.monotonic, sleep=time.sleep, log=emit):
        self.aws, self.settings = aws, settings
        self.clock, self.sleep, self.log = clock, sleep, log
        self.now = now or datetime.now(timezone.utc)
        self.deadline = clock() + budget_seconds
        self.ttl = timedelta(seconds=int(settings["ttlSeconds"]))
        require(0 < budget_seconds <= 810 and self.ttl.total_seconds() > 0, "INVALID_SWEEP_BUDGET")
        self.report = {"schemaVersion": 1, "startedAt": self.now.isoformat(), "considered": 0,
                       "deleted": 0, "pending": 0, "skipped": 0, "failed": 0, "deferred": 0, "issues": []}
        self.multipart_latest = {}
        self.multipart_scan_complete = False

    def call(self, service, operation, **parameters):
        remaining = self.deadline - self.clock()
        require(remaining > 0, "SWEEP_BUDGET_EXHAUSTED")
        self.aws.timeout_seconds = min(10, remaining)
        return self.aws.call(service, operation, **parameters)

    def issue(self, code, identity=None):
        self.report["failed"] += 1
        entry = {"code": code}
        if identity:
            entry["runIdentity"] = identity
        if len(self.report["issues"]) < 100:
            self.report["issues"].append(entry)
        self.log({"event": "expiry_issue", **entry})

    def pages(self, service, operation, output_token, input_token, **parameters):
        seen = set()
        for _ in range(MAX_PAGES):
            page = self.call(service, operation, **parameters)
            yield page
            token = page.get(output_token)
            if not token:
                require(not page.get("IsTruncated"), "MISSING_SCAN_CONTINUATION")
                return
            require(token not in seen, "REPEATED_SCAN_CONTINUATION")
            seen.add(token)
            parameters[input_token] = token
        raise CleanupError("SCAN_PAGE_BUDGET_EXCEEDED")

    @staticmethod
    def identity(value):
        return value if isinstance(value, str) and len(value) <= 27 and IDENTITY.fullmatch(value) else None

    def roots(self):
        candidates = set()
        try:
            for page in self.pages("cloudformation", "list-stacks", "NextToken", "next_token", stack_status_filter=LIVE_STATUSES):
                for stack in page.get("StackSummaries", []):
                    name = stack["StackName"]
                    if name.startswith(self.prefix):
                        identity = self.identity(name[len(self.prefix):])
                        if identity:
                            candidates.add(identity)
        except (AwsError, CleanupError, ValueError, KeyError, TypeError):
            self.issue("ROOT_SCAN_INCOMPLETE")
        return sorted(candidates)

    def orphans(self):
        candidates = set()
        try:
            for page in self.pages("s3api", "list-objects-v2", "NextContinuationToken", "continuation_token",
                                   bucket=self.bucket, prefix="runs/", delimiter="/", max_keys=1000):
                for item in page.get("CommonPrefixes", []):
                    match = re.fullmatch(r"runs/(gh-[0-9]+-[0-9]+)/", item["Prefix"])
                    if match and self.identity(match[1]):
                        candidates.add(match[1])
        except (AwsError, CleanupError, ValueError, KeyError, TypeError):
            self.issue("PREFIX_SCAN_INCOMPLETE")
        try:
            self.scan_uploads(candidates)
        except (AwsError, CleanupError, ValueError, KeyError, TypeError):
            self.issue("MULTIPART_SCAN_INCOMPLETE")
        return sorted(candidates)

    def scan_uploads(self, candidates):
        parameters = {"bucket": self.bucket, "prefix": "runs/", "max_uploads": 1000}
        seen = set()
        for _ in range(MAX_PAGES):
            page = self.call("s3api", "list-multipart-uploads", **parameters)
            for upload in page.get("Uploads", []):
                match = re.match(r"runs/(gh-[0-9]+-[0-9]+)/", upload["Key"])
                if match and self.identity(match[1]):
                    identity = match[1]
                    candidates.add(identity)
                    stamp = utc(upload["Initiated"])
                    self.multipart_latest[identity] = max(stamp, self.multipart_latest.get(identity, stamp))
            if not page.get("IsTruncated"):
                self.multipart_scan_complete = True
                return
            marker = (page["NextKeyMarker"], page["NextUploadIdMarker"])
            require(marker not in seen, "REPEATED_MULTIPART_CONTINUATION")
            seen.add(marker)
            parameters.update(key_marker=marker[0], upload_id_marker=marker[1])
        raise CleanupError("SCAN_PAGE_BUDGET_EXCEEDED")

    def marker_expired(self, identity):
        key = "runs/" + identity + "/control/run.json"
        listing = self.call("s3api", "list-objects-v2", bucket=self.bucket, prefix=key, max_keys=1)
        if not any(item.get("Key") == key for item in listing.get("Contents", [])):
            return None  # Prefix-scoped ListBucket reliably distinguishes absence from denied GetObject.
        try:
            response = self.call("s3api", "get-object", bucket=self.bucket, key=key)
        except AwsError as error:
            if error.code in ("NoSuchKey", "404", "NotFound"):
                return None
            raise
        body = response["Body"]
        try:
            require(response.get("ContentLength", MARKER_LIMIT + 1) <= MARKER_LIMIT, "INVALID_RUN_MARKER")
            raw = body.read(MARKER_LIMIT + 1)
            require(len(raw) <= MARKER_LIMIT, "INVALID_RUN_MARKER")
            marker = json.loads(raw)
        finally:
            body.close()
        require(marker.get("schemaVersion") == 1 and marker.get("runIdentity") == identity
                and marker.get("repository") == self.settings["repository"]
                and marker.get("bootstrapStack") == self.settings["bootstrapStack"]
                and marker.get("runStack") == self.prefix + identity and marker.get("region") == self.settings["region"],
                "INVALID_RUN_MARKER")
        created, expiry = utc(marker["createdAt"]), utc(marker["expiresAt"])
        require(timedelta(0) < expiry - created <= self.ttl and created <= self.now, "INVALID_RUN_MARKER_LIFETIME")
        return expiry <= self.now

    def legacy_expired(self, identity):
        if not self.multipart_scan_complete:
            return False  # A failed scan cannot prove that there are no recent unfinished writes.
        prefix = "runs/" + identity + "/"
        latest = self.multipart_latest.get(identity)
        for page in self.pages("s3api", "list-objects-v2", "NextContinuationToken", "continuation_token",
                               bucket=self.bucket, prefix=prefix, max_keys=1000):
            for item in page.get("Contents", []):
                require(item["Key"].startswith(prefix), "S3_KEY_OUTSIDE_RUN_PREFIX")
                stamp = utc(item["LastModified"])
                latest = max(stamp, latest) if latest else stamp
        return latest is not None and latest + self.ttl <= self.now

    def consider(self, identity):
        self.report["considered"] += 1
        name = self.prefix + identity
        try:
            root, = self.call("cloudformation", "describe-stacks", stack_name=name)["Stacks"]
        except AwsError as error:
            if error.code != "StackNotFound":
                raise
            root = None
        if root and root["StackStatus"] != "DELETE_COMPLETE":
            tags = values(root, "Tags", "Key", "Value")
            if (root.get("ParentId") or tags.get("sendium:managed-by") != "performance-tests"
                    or tags.get("sendium:repository") != self.settings["repository"] or tags.get("sendium:run-identity") != identity):
                self.report["skipped"] += 1
                return
            if utc(tags["sendium:expires-at"]) > self.now:
                self.report["skipped"] += 1
                return
        else:
            expired = self.marker_expired(identity)
            if expired is False or (expired is None and not self.legacy_expired(identity)):
                self.report["skipped"] += 1
                return
        plan = {"schemaVersion": 1, "region": self.settings["region"], "repository": self.settings["repository"],
                "bootstrapStack": self.settings["bootstrapStack"], "runIdentity": identity, "runStack": name, "bucket": self.bucket}
        remaining = self.deadline - self.clock()
        require(remaining >= 5, "SWEEP_BUDGET_EXHAUSTED")
        result = ExpiryCleaner(self.aws, plan, timeout_seconds=min(30, remaining), now=self.now,
                               clock=self.clock, sleep=self.sleep, poll_seconds=1).run()
        codes = [issue["code"] for issue in result["issues"]]
        if result["exitCode"] == 0:
            outcome = "deleted"
        elif codes == ["STACK_DELETE_PENDING"]:
            outcome = "pending"
        elif codes == ["RUN_NOT_EXPIRED"]:
            outcome = "skipped"  # A root can appear/change after orphan discovery.
        else:
            outcome = "failed"
        self.report[outcome] += 1
        self.log({"event": "expiry_run", "runIdentity": identity, "outcome": outcome,
                  "stackDeleted": result["stackDeleted"], "artifactsDeleted": result["artifactsDeleted"], "issues": codes,
                  "awsCodes": [issue["awsCode"] for issue in result["issues"] if "awsCode" in issue]})
        if root and root["StackStatus"] == "DELETE_FAILED":
            self.issue("EXPIRED_STACK_DELETE_FAILED", identity)  # Retrying must not hide an existing deletion failure.

    def run(self):
        try:
            boot, = self.call("cloudformation", "describe-stacks", stack_name=self.settings["bootstrapStack"])["Stacks"]
            output = values(boot, "Outputs", "OutputKey", "OutputValue")
            require(output["RepositoryTag"] == self.settings["repository"] and output["ArtifactBucketName"] == self.settings["bucket"]
                    and int(output["RunResourceTtlMinutes"]) * 60 == self.ttl.total_seconds(), "EXPIRY_BOOTSTRAP_MISMATCH")
            self.prefix, self.bucket = output["RunStackPrefix"], output["ArtifactBucketName"]
            processed = set()
            # Prioritize billed infrastructure; an S3 scan failure must not block root deletion.
            for discover in (self.roots, self.orphans):
                if self.deadline - self.clock() < 5:
                    self.report["deferred"] += 1
                    break
                try:
                    candidates = [identity for identity in discover() if identity not in processed]
                except (AwsError, CleanupError, ValueError, KeyError, TypeError):
                    self.issue("EXPIRY_SCAN_INCOMPLETE")
                    continue
                for index, identity in enumerate(candidates):
                    if self.deadline - self.clock() < 5:
                        self.report["deferred"] += len(candidates) - index
                        break
                    processed.add(identity)
                    try:
                        self.consider(identity)
                    except (AwsError, CleanupError, ValueError, KeyError, TypeError, AttributeError):
                        self.issue("RUN_EXPIRY_FAILED", identity)
        except (AwsError, CleanupError, ValueError, KeyError, TypeError):
            self.issue("EXPIRY_SCAN_INCOMPLETE")
        self.log({"event": "expiry_summary", **self.report})
        return self.report


def handler(event, context):
    # Scheduled/manual invocation input never selects resources or overrides expiry.
    settings = {"region": os.environ["AWS_REGION"], "repository": os.environ["REPOSITORY"],
                "bootstrapStack": os.environ["BOOTSTRAP_STACK"], "bucket": os.environ["ARTIFACT_BUCKET"],
                "ttlSeconds": int(os.environ["RUN_TTL_MINUTES"]) * 60}
    budget = min(810, context.get_remaining_time_in_millis() / 1000 - 30)
    report = Sweeper(BotoAws(settings["region"]), settings, budget).run()
    if report["failed"]:
        raise RuntimeError("EXPIRY_SWEEP_INCOMPLETE")  # Visible Lambda Errors; next scheduled sweep retries.
    return report
