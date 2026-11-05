"""Protected, data-only build consumption and create-only AWS/SSM orchestration."""

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import signal
import sys
import time

import prepare
import resolve_aws
import runtime_assets

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "deployment"))
from aws_cli import AwsCli, AwsError
from check_expiry import verify_expiry
import deploy


def require(condition, message):
    if not condition:
        raise ValueError(message)


def preflight(aws, request, settings):
    require(re.fullmatch(r"[a-z0-9-]{3,32}", settings["region"])
            and re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,127}", settings["bootstrapStack"]), "Invalid protected region/bootstrap configuration")
    description = deploy.stack(aws, settings["bootstrapStack"])
    boot = deploy.outputs(description)
    plan = {"schemaVersion": 1, "region": settings["region"], "repository": request["repository"],
            "bootstrapStack": settings["bootstrapStack"], "runIdentity": request["runIdentity"],
            "runStack": boot["RunStackPrefix"] + request["runIdentity"]}
    deploy.check_bootstrap(description, plan, operational=True)
    require(boot["OrchestratorRoleArn"] == settings["roleArn"] and boot["GitHubEnvironmentName"] == request["preset"]["aws"]["githubEnvironment"]
            and int(boot["RunResourceTtlMinutes"]) == request["preset"]["lifecycle"]["resourceTtlMinutes"], "Protected bootstrap/role/preset mismatch")
    partition, account = description["StackId"].split(":")[1], description["StackId"].split(":")[4]
    require(description["StackId"].split(":")[3] == settings["region"], "Bootstrap region mismatch")
    caller = aws.call("sts", "get-caller-identity")
    role_name = boot["OrchestratorRoleArn"].split("/")[-1]
    require(caller["Account"] == account and caller["Arn"].startswith("arn:" + partition + ":sts::" + account + ":assumed-role/" + role_name + "/"),
            "Protected controller requires the configured assumed orchestration role")
    verify_expiry(aws, boot, settings["bootstrapStack"])
    require(not aws.call("s3api", "get-bucket-versioning", bucket=boot["ArtifactBucketName"]).get("Status"), "Temporary artifact bucket must be unversioned")
    try:
        deploy.stack(aws, plan["runStack"])
    except AwsError as error:
        if error.code != "StackNotFound":
            raise
    else:
        raise ValueError("Run stack already exists; use a fresh workflow attempt")
    return plan, boot, partition


def create_stack(aws, plan, staged, resolved, boot, partition, directory, clock=time.monotonic, sleep=time.sleep):
    prefix = boot["RunStackPrefix"]
    require(prefix.endswith("-run-"), "Unexpected bootstrap resource namespace")
    parameters = {"ResourcePrefix": prefix[:-5], "Repository": plan["repository"], "RunIdentity": plan["runIdentity"],
                  "ExpiresAt": staged["expiresAt"], "ArtifactBucketName": staged["bucket"], "RuntimeBoundaryArn": boot["RuntimeBoundaryArn"],
                  "PrimaryAvailabilityZone": resolved["placement"]["primary"], "SecondaryAvailabilityZone": resolved["placement"]["secondary"],
                  "S3PrefixListId": resolved["s3PrefixListId"], "DatabaseInstanceType": resolved["database"]["instanceType"],
                  "DatabaseEngineVersion": resolved["database"]["engineVersion"]}
    for source, node in resolved["nodes"].items():
        for suffix, field in (("ImageId", "imageId"), ("InstanceType", "instanceType"), ("RootDeviceName", "rootDeviceName"), ("RootVolumeGiB", "rootVolumeGiB")):
            parameters[source.title() + suffix] = node[field]
    tags = {"sendium:managed-by": "performance-tests", "sendium:repository": plan["repository"],
            "sendium:run-identity": plan["runIdentity"], "sendium:expires-at": staged["expiresAt"]}
    suffix = "amazonaws.com.cn" if partition == "aws-cn" else "amazonaws.com"
    template_url = "https://" + staged["bucket"] + ".s3." + plan["region"] + "." + suffix + "/" + staged["artifacts"]["runTemplate"]["key"]
    response = aws.call("cloudformation", "create-stack", stack_name=plan["runStack"], template_url=template_url,
                        parameters=[{"ParameterKey": key, "ParameterValue": str(value)} for key, value in sorted(parameters.items())],
                        tags=[{"Key": key, "Value": value} for key, value in tags.items()],
                        capabilities=["CAPABILITY_NAMED_IAM"], role_arn=boot["CloudFormationExecutionRoleArn"],
                        timeout_in_minutes=staged["preset"]["lifecycle"]["provisioningTimeoutMinutes"],
                        on_failure="ROLLBACK", client_request_token=plan["runIdentity"])
    identifier = response["StackId"]
    prepare.write(Path(directory) / "stack-creation.json", {"schemaVersion": 1, "stackId": identifier, "parameters": parameters, "tags": tags})
    deadline = clock() + staged["preset"]["lifecycle"]["provisioningTimeoutMinutes"] * 60
    while clock() < deadline:
        try:
            current = deploy.stack(aws, identifier)
        except AwsError as error:
            if error.code != "StackNotFound":
                raise
            sleep(5)
            continue
        require(current["StackId"] == identifier and current.get("RoleARN") == boot["CloudFormationExecutionRoleArn"], "Created stack identity/role mismatch")
        actual_tags = {item["Key"]: item["Value"] for item in current["Tags"]}
        require(all(actual_tags.get(key) == value for key, value in tags.items()), "Created stack ownership mismatch")
        if current["StackStatus"] == "CREATE_COMPLETE":
            return identifier
        require(current["StackStatus"] == "CREATE_IN_PROGRESS", "CloudFormation provisioning failed or rolled back")
        sleep(5)
    raise ValueError("CloudFormation provisioning deadline exceeded")


def stack_events(aws, name):
    response = aws.call("cloudformation", "describe-stack-events", stack_name=name)
    # Never publish ResourceProperties or unstructured provider messages containing dynamic inputs.
    return {"schemaVersion": 1, "events": [{"logicalResourceId": event["LogicalResourceId"], "resourceType": event["ResourceType"],
             "status": event["ResourceStatus"], "timestamp": event["Timestamp"]} for event in response.get("StackEvents", [])[:100]]}


def run(aws, downloads, request, inputs, bundles, settings, directory, verify_builds=prepare.verify_bundles):
    directory = Path(directory)
    directory.mkdir(exist_ok=False)
    outcome = {"schemaVersion": 1, "runIdentity": request["runIdentity"], "exitCode": 1, "phase": "build-verification", "issues": []}
    plan, staged = None, None
    try:
        require(verify_builds(request, bundles) == inputs, "Consolidated build inputs differ from downloaded artifacts")
        outcome["phase"] = "bootstrap-preflight"
        cleanup_plan, boot, partition = preflight(aws, request, settings)
        prepare.write(directory / "cleanup-plan.json", cleanup_plan)
        outcome["phase"] = "regional-resolution"
        resolved = resolve_aws.resolve(aws, request["preset"], settings["region"])
        resolved["sourceRevisions"] = {"controller": request["controllerRevision"], "workflow": request["workflowRevision"]}
        outcome["phase"] = "runtime-resolution"
        target = prepare.benchmark_target(request)
        runtime, details = runtime_assets.resolve(downloads, resolved["nodes"], request["preset"], directory / "runtime",
                                                 **({"target": target} if target == "kannel" else {}))
        resolved["runtime"] = details
        prepare.write(directory / "resolution.json", resolved)
        preset = deepcopy(request["preset"])
        preset["database"]["engineVersion"] = resolved["database"]["engineVersion"]
        preset["runtime"]["java"]["release"] = details["java"]["harness"]["release"]
        artifacts = dict(runtime)
        native_build = None
        kannel_build = None
        for source, component in (("sendium", "application"), ("harness", "harness")):
            node = resolved["nodes"][source]
            preset["runtime"]["amiIds"][node["architecture"]] = node["imageId"]
            item, = [item for item in inputs["artifacts"] if item["component"] == component and item["architecture"] == node["architecture"]]
            artifacts[component] = {"path": str((Path(bundles) / item["path"]).resolve()), "sha256": item["sha256"]}
            if component == "application":
                native_build = item.get("nativeBuild")
                kannel_build = item.get("kannelBuild")
        plan = {**cleanup_plan, "provenance": request["provenance"], "artifacts": artifacts, "resolution": resolved}
        if native_build is not None:
            plan["nativeBuild"] = native_build
        if target == "kannel":
            prepare.validate_kannel_build(kannel_build, resolved["nodes"]["sendium"]["architecture"])
            plan.update(benchmarkTarget=target, kannelBuild=kannel_build)
        prepare.write(directory / "deployment-plan.json", plan)
        prepare.write(directory / "resolved-preset.json", preset)
        outcome["phase"] = "staging"
        staged = deploy.stage(aws, plan, preset, directory)
        prepare.write(directory / "staged.json", staged)
        outcome["phase"] = "provisioning"
        create_stack(aws, plan, staged, resolved, boot, partition, directory)
        outcome["phase"] = "execution"
        execution = deploy.execute(aws, staged, directory / "execution")
        outcome["exitCode"] = execution["exitCode"]
        if execution["exitCode"]:
            outcome["issues"].append("BENCHMARK_EXECUTION_FAILED")
        outcome["phase"] = "finished"
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, prepare.ValidationError, KeyboardInterrupt):
        outcome["exitCode"] = 1
        outcome["issues"].append("PROTECTED_CONTROLLER_FAILED")
    finally:
        if staged:
            try:
                prepare.write(directory / "stack-events.json", stack_events(aws, staged["runStack"]))
            except (OSError, RuntimeError, ValueError, KeyError):
                outcome["issues"].append("STACK_EVENTS_UNAVAILABLE")
            if not (directory / "execution/retrieved").is_dir():
                try:
                    deploy.retrieve(aws, staged, directory / "retrieved")
                except (OSError, RuntimeError, ValueError, KeyError):
                    outcome["issues"].append("FALLBACK_RETRIEVAL_FAILED")
        outcome["finishedAt"] = datetime.now(timezone.utc).isoformat()
        prepare.write(directory / "controller-outcome.json", outcome)
    return outcome


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True)
    parser.add_argument("--build-inputs", required=True)
    parser.add_argument("--bundles", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    def interrupt(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    try:
        request = prepare.read(args.request)
        expected = prepare.normalize(prepare.read_inputs(), prepare.github_context(os.environ["CONTROLLER_REVISION"]),
                                     prepare.read(HERE.parent / "presets/aws-private-jvm-v1.json"))
        require(request == expected, "Request does not match this dispatch/controller snapshot")
        settings = {"region": os.environ["AWS_REGION"], "bootstrapStack": os.environ["AWS_BOOTSTRAP_STACK"], "roleArn": os.environ["AWS_ORCHESTRATOR_ROLE_ARN"]}
        return run(AwsCli(settings["region"]), runtime_assets.Downloads(), request, prepare.read(args.build_inputs), args.bundles, settings, args.output)["exitCode"]
    except (OSError, RuntimeError, ValueError, KeyError, TypeError, prepare.ValidationError):
        print("PROTECTED_PERFORMANCE_INPUT_OR_OUTPUT_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
