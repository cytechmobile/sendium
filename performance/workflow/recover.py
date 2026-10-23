"""Protected read-only diagnostics or owned-run cleanup, never report/JVM execution."""

import argparse
from datetime import timedelta
import os
from pathlib import Path
import signal
import sys

import prepare
from semantics import validate_results

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "deployment"))
sys.path.insert(0, str(HERE.parent / "resources"))
from aws_cli import AwsCli
from cleanup import Cleaner
import collect_cpu_credits
import collect_rds_metrics
import deploy


def require(condition, message):
    if not condition:
        raise ValueError(message)


def ownership(aws, request, region, bootstrap, role, diagnostic):
    description = deploy.stack(aws, bootstrap)
    outputs = deploy.outputs(description)
    expected_role = outputs["DiagnosticsRoleArn" if diagnostic else "CleanupRoleArn"]
    require(outputs["RepositoryTag"] == request["repository"] and expected_role == role
            and description["StackId"].split(":")[3] == region, "Recovery bootstrap/role/region mismatch")
    caller = aws.call("sts", "get-caller-identity")
    partition, account = description["StackId"].split(":")[1], description["StackId"].split(":")[4]
    require(caller["Account"] == account and caller["Arn"].startswith("arn:" + partition + ":sts::" + account
            + ":assumed-role/" + role.split("/")[-1] + "/"), "Recovery requires the configured assumed role")
    return {"schemaVersion": 1, "region": region, "repository": request["repository"], "bootstrapStack": bootstrap,
            "runIdentity": request["runIdentity"], "runStack": outputs["RunStackPrefix"] + request["runIdentity"],
            "bucket": outputs["ArtifactBucketName"], "preset": request["preset"], "accountId": account, "partition": partition}


def rds_context(document, plan, request):
    metadata = document["measurementMetadata"]
    start = collect_rds_metrics.utc(metadata["measurementStartUtc"])
    end = start + timedelta(microseconds=(metadata["observedDurationNanos"] + 999) // 1000 + 1)
    warmup = document["configuration"]["workload"]["warmupSeconds"]
    drain = document["drain"]["durationSeconds"]
    database = document["configuration"]["infrastructure"]["database"]
    return {"database": {"identifier": database["identifier"], "instanceClass": database["instanceType"],
            "region": plan["region"], "accountId": plan["accountId"], "partition": plan["partition"],
            "repository": request["repository"], "runIdentity": request["runIdentity"]},
            "phases": {phase: {"start": collect_rds_metrics.text(first), "end": collect_rds_metrics.text(last)}
                       for phase, first, last in (("warmup", start - timedelta(seconds=warmup), start),
                                                 ("measurement", start, end),
                                                 ("drain", end, end + timedelta(seconds=drain)))}}


def diagnostics(aws, credits, plan, request, directory):
    result = {"schemaVersion": 1, "runIdentity": request["runIdentity"], "exitCode": 0, "issues": []}
    result["retrieval"] = deploy.retrieve(aws, plan, Path(directory) / "retrieved")
    report = Path(directory) / "retrieved/harness/results.json"
    if not report.is_file():
        result["issues"].append("REPORT_UNAVAILABLE")
        return result
    try:
        require(report.stat().st_size <= 64 * 1024 * 1024, "Report exceeds size budget")
        import json
        document = json.loads(report.read_text(encoding="utf-8"))
        require(document["provenance"]["runIdentity"] == request["runIdentity"]
                and document["provenance"]["applicationCommit"] == request["provenance"]["applicationCommit"]
                and document["provenance"]["harnessRevision"] == request["provenance"]["harnessRevision"], "Diagnostic report identity mismatch")
        validate_results(document)
        require(document["configuration"]["infrastructure"]["region"] == plan["region"], "Diagnostic report region mismatch")
        metadata = document["measurementMetadata"]
        if not metadata.get("measurementStartUtc") or metadata.get("observedDurationNanos", 0) <= 0:
            result["issues"].append("MEASUREMENT_WINDOW_UNAVAILABLE")
            return result
        start = collect_cpu_credits.utc(metadata["measurementStartUtc"])
        # Java Instant keeps nanoseconds; datetime truncates to microseconds. Expand outward,
        # otherwise an apparently matching credit envelope can end before the exact report window.
        end = start + timedelta(microseconds=(metadata["observedDurationNanos"] + 999) // 1000 + 1)
        # Collect independently: a failed EC2 credit query must not discard database evidence.
        if document["configuration"]["infrastructure"]["database"].get("resourceMetricsRequired", False):
            timeout = aws.timeout_seconds
            try:
                aws.timeout_seconds = 10
                context = rds_context(document, plan, request)
                evidence = collect_rds_metrics.collect(aws, context)
                prepare.write(Path(directory) / "rds-metrics.json", evidence)
            except (Exception, KeyboardInterrupt):
                result["issues"].append("RDS_COLLECTION_UNAVAILABLE")
                result["exitCode"] = 1
            finally:
                aws.timeout_seconds = timeout
        instances = {source: document["configuration"]["infrastructure"][source]["instanceId"] for source in ("sendium", "harness")}
        require(len(set(instances.values())) == 2, "Distinct instance IDs required")
        described = aws.call("ec2", "describe-instances", instance_ids=list(instances.values()))
        actual = {item["InstanceId"]: item for reservation in described["Reservations"] for item in reservation["Instances"]}
        for source, identity in instances.items():
            tags = {item["Key"]: item["Value"] for item in actual[identity]["Tags"]}
            require(tags.get("sendium:managed-by") == "performance-tests" and tags.get("sendium:repository") == request["repository"]
                    and tags.get("sendium:run-identity") == request["runIdentity"] and tags.get("sendium:role") == source,
                    "Diagnostic instance ownership/source mismatch")
            require(actual[identity]["InstanceType"] == document["configuration"]["infrastructure"][source]["instanceType"],
                    "Diagnostic instance type mismatch")
        evidence = collect_cpu_credits.collect(credits, instances, start, end)
        prepare.write(Path(directory) / "cpu-credits.json", evidence)
    except (Exception, KeyboardInterrupt):
        result["issues"].append("CREDIT_COLLECTION_UNAVAILABLE")
        result["exitCode"] = 1
    return result


def run(aws, request, region, bootstrap, role, directory, diagnostic, credits=None):
    directory = Path(directory)
    directory.mkdir(exist_ok=False)
    result = {"schemaVersion": 1, "runIdentity": request["runIdentity"], "exitCode": 1, "issues": []}
    try:
        plan = ownership(aws, request, region, bootstrap, role, diagnostic)
        prepare.write(directory / "cleanup-plan.json", {key: value for key, value in plan.items() if key != "preset"})
        if diagnostic:
            result = diagnostics(aws, credits or collect_cpu_credits.AwsCli(region), plan, request, directory)
        else:
            result = Cleaner(aws, plan, timeout_seconds=request["preset"]["lifecycle"]["cleanupTimeoutMinutes"] * 60).run()
    except (Exception, KeyboardInterrupt):
        result["issues"].append("DIAGNOSTIC_RECOVERY_FAILED" if diagnostic else "CLEANUP_RECOVERY_FAILED")
    prepare.write(directory / ("diagnostics-outcome.json" if diagnostic else "cleanup-outcome.json"), result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("diagnostics", "cleanup"))
    parser.add_argument("--request", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    def interrupt(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    try:
        request = prepare.read(args.request)
        expected = prepare.normalize(prepare.read_inputs(), prepare.github_context(os.environ["CONTROLLER_REVISION"]),
                                     prepare.read(HERE.parent / "presets/aws-private-jvm-v1.json"))
        require(request == expected, "Recovery request differs from dispatch")
        diagnostic = args.mode == "diagnostics"
        role = os.environ["AWS_DIAGNOSTICS_ROLE_ARN" if diagnostic else "AWS_CLEANUP_ROLE_ARN"]
        return run(AwsCli(os.environ["AWS_REGION"]), request, os.environ["AWS_REGION"], os.environ["AWS_BOOTSTRAP_STACK"],
                   role, args.output, diagnostic)["exitCode"]
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, prepare.ValidationError):
        print("PERFORMANCE_RECOVERY_INPUT_OR_OUTPUT_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
