"""Upload a checked evidence package using only the dedicated archive role."""

import argparse
import base64
import os
from pathlib import Path
import re
import sys
import tempfile

import archive_package
import prepare
import publish

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "deployment"))
from aws_cli import AwsCli, AwsError
import deploy


def destination(aws, request, bootstrap, role):
    stack = deploy.stack(aws, bootstrap)
    outputs = deploy.outputs(stack)
    parts = stack["StackId"].split(":")
    prepare.require(outputs["RepositoryTag"] == request["repository"] and outputs["ArchiveRoleArn"] == role
                    and parts[3] == aws.region, "Archive bootstrap/role/region mismatch")
    caller = aws.call("sts", "get-caller-identity")
    prepare.require(caller["Account"] == parts[4] and caller["Arn"].startswith(
        "arn:" + parts[1] + ":sts::" + parts[4] + ":assumed-role/" + role.split("/")[-1] + "/"),
        "Archive requires configured assumed role")
    bucket = outputs["ResultsArchiveBucketName"]
    prepare.require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket)
                    and bucket != outputs["ArtifactBucketName"], "Invalid permanent archive bucket")
    return bucket


def checked_manifest(request, directory):
    directory = Path(directory)
    prepare.require(directory.is_dir() and not directory.is_symlink(), "Invalid archive directory")
    prepare.require(not (directory / "manifest.json").is_symlink(), "Invalid archive manifest")
    manifest = prepare.read(directory / "manifest.json")
    match = re.fullmatch(r"gh-([1-9][0-9]*)-([1-9][0-9]*)", request["runIdentity"])
    prepare.require(match is not None and manifest["schemaVersion"] == 1
                    and manifest["repository"] == request["repository"] and manifest["runIdentity"] == request["runIdentity"]
                    and manifest["runId"] == match[1] and manifest["runAttempt"] == match[2]
                    and manifest["prefix"] == "runs/" + request["repository"] + "/" + match[1] + "/" + match[2] + "/",
                    "Archive package identity mismatch")
    # Validate the same public files again after the artifact boundary.
    paths = archive_package.published_files(directory)
    actual = [archive_package.descriptor(path, path.relative_to(directory).as_posix()) for path in paths]
    prepare.require(actual == manifest["files"], "Archive evidence checksum mismatch")
    prepare.require((directory / "evidence.tar.gz").is_file() and not (directory / "evidence.tar.gz").is_symlink()
                    and (directory / "evidence.tar.gz").stat().st_size <= archive_package.MAX_TOTAL_BYTES + 1024 * 1024,
                    "Invalid archive bundle")
    prepare.require(archive_package.descriptor(directory / "evidence.tar.gz", "evidence.tar.gz") == manifest["bundle"],
                    "Archive bundle checksum mismatch")
    report = publish.read(directory / "results.json")
    publish.validate(report, request)
    prepare.require(manifest["provenance"] == report["provenance"]
                    and manifest["applicationRuntime"] == report["configuration"]["application"].get("runtime", "jvm")
                    and manifest["ingressMode"] == report["configuration"]["workload"]["ingressMode"]
                    and manifest["observationStatus"] == report["validity"]["status"]
                    and manifest["resultSchemaVersion"] == report["schemaVersion"]
                    and manifest["observationAvailable"] == report["measurementMetadata"].get("observationAvailable", True)
                    and manifest["missingFiles"] == [name for name in archive_package.TOP_LEVEL if not (directory / name).is_file()],
                    "Archive metadata differs from report")
    return manifest


def upload_object(aws, bucket, key, path, item):
    checksum = base64.b64encode(bytes.fromhex(item["sha256"])).decode("ascii")
    try:
        aws.call("s3api", "put-object", bucket=bucket, key=key, body=str(path), if_none_match="*",
                 checksum_sha256=checksum, content_type=item["contentType"], server_side_encryption="AES256")
    except AwsError as error:
        if error.code != "PreconditionFailed":
            raise
    head = aws.call("s3api", "head-object", bucket=bucket, key=key, checksum_mode="ENABLED")
    prepare.require(head.get("ChecksumSHA256") == checksum and head["ContentLength"] == item["sizeBytes"],
                    "Stored archive checksum mismatch")


def upload(aws, request, directory, bootstrap, role):
    directory = Path(directory)
    manifest = checked_manifest(request, directory)
    bucket = destination(aws, request, bootstrap, role)
    prefix = manifest["prefix"]
    for item in [*manifest["files"], manifest["bundle"]]:
        upload_object(aws, bucket, prefix + item["path"], directory / item["path"], item)
    # The manifest is the completion marker and must be committed last.
    item = archive_package.descriptor(directory / "manifest.json", "manifest.json")
    try:
        upload_object(aws, bucket, prefix + "manifest.json", directory / "manifest.json", item)
    except ValueError:
        # Repackaging changes only packagedAtUtc. Never overwrite the existing manifest.
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            aws.get(bucket, prefix + "manifest.json", path)
            previous = prepare.read(path)
            current = dict(manifest)
            previous.pop("packagedAtUtc", None)
            current.pop("packagedAtUtc", None)
            prepare.require(previous == current, "Conflicting archived manifest")
            stored = archive_package.descriptor(path, "manifest.json")
            head = aws.call("s3api", "head-object", bucket=bucket, key=prefix + "manifest.json", checksum_mode="ENABLED")
            prepare.require(head.get("ChecksumSHA256") == base64.b64encode(bytes.fromhex(stored["sha256"])).decode("ascii")
                            and head["ContentLength"] == stored["sizeBytes"], "Stored manifest checksum mismatch")
    return {"schemaVersion": 1, "runIdentity": request["runIdentity"], "status": "archived", "exitCode": 0,
            "location": "s3://" + bucket + "/" + prefix + "manifest.json", "issues": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = {"schemaVersion": 1, "status": "failed", "exitCode": 1, "issues": ["ARCHIVE_UPLOAD_FAILED"]}
    try:
        request = prepare.read(args.request)
        expected = prepare.normalize(prepare.read_inputs(), prepare.github_context(os.environ["CONTROLLER_REVISION"]),
                                     prepare.read(Path(__file__).resolve().parent.parent / "presets/aws-private-jvm-v1.json"))
        prepare.require(request == expected, "Archive request differs from dispatch")
        result["runIdentity"] = request["runIdentity"]
        aws = AwsCli(os.environ["AWS_REGION"])
        aws.timeout_seconds = 30
        result = upload(aws, request, args.package, os.environ["AWS_BOOTSTRAP_STACK"], os.environ["AWS_ARCHIVE_ROLE_ARN"])
    except Exception:
        print("PERFORMANCE_ARCHIVE_UPLOAD_FAILED", file=sys.stderr)
    prepare.write(args.output, result)
    return result["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
