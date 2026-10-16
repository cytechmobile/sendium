"""Protected artifact/SSM controller. Never execute application or harness code here."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import signal
import sys
import tempfile
import time

from aws_cli import AwsCli, AwsError
from check_expiry import verify_expiry


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "contracts"))
from preset_validation import validate_preset
from jsonschema import Draft202012Validator, ValidationError


class DeploymentError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise DeploymentError(message)


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_new(path, document):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(document, stream, indent=2, allow_nan=False)
        stream.write("\n")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_plan(plan, preset):
    require(plan.get("schemaVersion") == 1, "Unsupported deployment plan version")
    for field, pattern in (("region", r"[a-z0-9-]{3,32}"), ("runIdentity", r"gh-[0-9]+-[0-9]+"),
                           ("bootstrapStack", r"[a-zA-Z][a-zA-Z0-9-]{0,127}"),
                           ("runStack", r"[a-zA-Z][a-zA-Z0-9-]{0,127}")):
        require(isinstance(plan.get(field), str) and re.fullmatch(pattern, plan[field]), "Invalid " + field)
    require(len(plan["runIdentity"]) <= 27, "Run identity exceeds IAM name budget")
    effective = json.loads(json.dumps(preset))
    effective["workload"].update(plan.get("workload", {}))
    validate_preset(effective, Draft202012Validator(read(HERE.parent / "contracts/run-config.schema.json")))
    baseline = read(HERE.parent / "presets/aws-private-jvm-v1.json")
    require(effective["network"] == baseline["network"], "Preset network differs from the reviewed V1 template")
    require(effective["aws"]["burstableCreditMode"] == "standard", "V1 template requires Standard CPU credits")
    for section, fields in (("deployment", ("httpPort", "smppPort", "providerPort", "callbackPort")),
                            ("runtime", ("installRoot", "runRoot")),
                            ("database", ("engine", "majorVersion", "port", "allocatedStorageGiB", "storageType", "caBundlePath"))):
        require(all(effective[section][field] == baseline[section][field] for field in fields),
                "Preset " + section + " differs from the V1 deployment contract")
    provenance = plan["provenance"]
    for name in ("applicationCommit", "harnessRevision"):
        require(re.fullmatch(r"[a-f0-9]{40}", provenance.get(name, ""))
                and len(set(provenance[name])) > 1, "Exact build revision required")
    require(isinstance(provenance.get("branch"), str) and provenance["branch"], "Selected branch required")
    require(set(plan["artifacts"]) == {"application", "harness", "sendiumJre", "harnessJre", "rdsCa"},
            "Expected application, harness, two architecture-compatible JREs and RDS CA")
    for artifact in plan["artifacts"].values():
        require(re.fullmatch(r"[a-f0-9]{64}", artifact.get("sha256", "")), "Artifact SHA-256 required")
        require(Path(artifact["path"]).is_file() and sha256(artifact["path"]) == artifact["sha256"],
                "Local artifact digest mismatch")
        require(0 < Path(artifact["path"]).stat().st_size <= 1024 ** 3, "Artifact exceeds 1 GiB transport budget")
    return effective


def stack(aws, name):
    stacks = aws.call("cloudformation", "describe-stacks", stack_name=name)["Stacks"]
    require(len(stacks) == 1, "Expected exactly one stack")
    return stacks[0]


def outputs(description):
    return {entry["OutputKey"]: entry["OutputValue"] for entry in description.get("Outputs", [])}


def check_bootstrap(description, plan, operational=False):
    result = outputs(description)
    require(description["StackStatus"] in ("CREATE_COMPLETE", "UPDATE_COMPLETE"), "Bootstrap is not ready")
    require(plan["runStack"] == result["RunStackPrefix"] + plan["runIdentity"], "Unexpected run stack namespace")
    require(result["RepositoryTag"] == plan["repository"], "Bootstrap repository differs")
    if operational:
        require(result.get("ExpiryHandlerInstalled") == "true", "Independent expiry handler is not installed")
    return result


def stage(aws, plan, preset, directory):
    effective = validate_plan(plan, preset)
    boot = check_bootstrap(stack(aws, plan["bootstrapStack"]), plan)
    require(int(boot.get("RunResourceTtlMinutes", effective["lifecycle"]["resourceTtlMinutes"])) == effective["lifecycle"]["resourceTtlMinutes"],
            "Bootstrap and preset run TTL differ")
    bucket = boot["ArtifactBucketName"]
    prefix = "runs/" + plan["runIdentity"] + "/"
    descriptors = {}

    def upload(name, path, key):
        descriptors[name] = {"key": prefix + key, "sha256": sha256(path), "size": Path(path).stat().st_size}
        aws.put(bucket, prefix + key, path, immutable=True)

    created = datetime.now(timezone.utc).replace(microsecond=0)
    expiry = created + timedelta(minutes=effective["lifecycle"]["resourceTtlMinutes"])
    marker = {key: plan[key] for key in ("schemaVersion", "region", "runIdentity", "runStack", "bootstrapStack", "repository")}
    marker.update(createdAt=created.isoformat().replace("+00:00", "Z"), expiresAt=expiry.isoformat().replace("+00:00", "Z"))
    marker_path = Path(directory) / "run-marker.json"
    write_new(marker_path, marker)
    # Publish recovery identity before large inputs: partial staging is independently expirable.
    upload("control", marker_path, "control/run.json")
    for name, artifact in plan["artifacts"].items():
        location = {"application": "sendium", "harness": "common", "sendiumJre": "sendium",
                    "harnessJre": "harness", "rdsCa": "common"}[name]
        upload(name, artifact["path"], "inputs/" + location + "/" + name)
    for name, path, key in (
            ("node", HERE / "node.py", "inputs/common/node.py"),
            ("transport", HERE / "aws_cli.py", "inputs/common/aws_cli.py"),
            ("sampler", HERE.parent / "resources/sample_process.py", "inputs/common/sample_process.py"),
            ("network", HERE.parent / "infrastructure/network.yaml", "templates/network.yaml")):
        upload(name, path, key)
    effective_path = Path(directory) / "preset.json"
    write_new(effective_path, effective)
    upload("preset", effective_path, "inputs/common/preset.json")
    # No local artifact paths or credentials are needed after staging.
    return {key: plan[key] for key in ("schemaVersion", "region", "runIdentity", "runStack", "bootstrapStack", "repository", "provenance")} | {
        "bucket": bucket, "artifacts": descriptors, "preset": effective,
        "createdAt": marker["createdAt"], "expiresAt": marker["expiresAt"]}


def inspect_run(aws, staged):
    boot = check_bootstrap(stack(aws, staged["bootstrapStack"]), staged, operational=True)
    try:
        verify_expiry(aws, boot, staged["bootstrapStack"])
    except (RuntimeError, ValueError, KeyError):
        raise DeploymentError("Independent expiry function/schedule verification failed") from None
    require(int(boot["RunResourceTtlMinutes"]) == staged["preset"]["lifecycle"]["resourceTtlMinutes"], "Bootstrap and preset run TTL differ")
    require(staged["bucket"] == boot["ArtifactBucketName"], "Artifact bucket differs from bootstrap")
    description = stack(aws, staged["runStack"])
    require(description["StackStatus"] == "CREATE_COMPLETE", "Run stack is not create-complete")
    require("ParentId" not in description, "Execution requires a root stack")
    require(description.get("RoleARN") == boot["CloudFormationExecutionRoleArn"], "Unexpected run execution role")
    tags = {tag["Key"]: tag["Value"] for tag in description.get("Tags", [])}
    expected = {"sendium:managed-by": "performance-tests", "sendium:repository": staged["repository"],
                "sendium:run-identity": staged["runIdentity"]}
    require(all(tags.get(key) == value for key, value in expected.items()), "Run ownership mismatch")
    result = outputs(description)
    require(result["RunIdentity"] == staged["runIdentity"] and result["ArtifactRunPrefix"] == "runs/" + staged["runIdentity"] + "/",
            "Run outputs differ from artifact identity")
    require(result["ExpiresAt"] == tags.get("sendium:expires-at"), "Stack expiry output/tag mismatch")
    require(staged.get("expiresAt", result["ExpiresAt"]) == result["ExpiresAt"], "Stack and staging expiry differ")
    expiry = datetime.fromisoformat(tags["sendium:expires-at"].replace("Z", "+00:00"))
    workload = staged["preset"]["workload"]
    timeout = sum(workload[key] for key in ("warmupSeconds", "measurementSeconds", "drainTimeoutSeconds")) + staged["preset"]["measurement"]["readinessTimeoutSeconds"] + 120
    require(timeout / staged["preset"]["measurement"]["sampleIntervalSeconds"] + 2 <= staged["preset"]["measurement"]["sampleBudgetPerSource"],
            "Node sampler cannot cover the execution budget")
    remaining = (expiry - datetime.now(timezone.utc)).total_seconds() if expiry.tzinfo is not None else 0
    require(timeout + 300 < remaining <= staged["preset"]["lifecycle"]["resourceTtlMinutes"] * 60,
            "Insufficient unexpired run lifetime")
    instances = aws.call("ec2", "describe-instances", instance_ids=[result["SendiumInstanceId"], result["HarnessInstanceId"]])
    actual = {item["InstanceId"]: item for reservation in instances["Reservations"] for item in reservation["Instances"]}
    require(len(actual) == 2, "Expected distinct benchmark instances")
    type_info = aws.call("ec2", "describe-instance-types", instance_types=sorted({item["InstanceType"] for item in actual.values()}))
    types = {item["InstanceType"]: item for item in type_info["InstanceTypes"]}
    for node in ("Sendium", "Harness"):
        instance = actual[result[node + "InstanceId"]]
        require(instance["State"]["Name"] == "running" and not instance.get("PublicIpAddress"), "Instance is not private/running")
        require(instance["PrivateIpAddress"] == result[node + "PrivateAddress"] and instance["SubnetId"] == result["PrimarySubnetId"],
                "Instance placement mismatch")
        instance_tags = {tag["Key"]: tag["Value"] for tag in instance.get("Tags", [])}
        require(all(instance_tags.get(key) == value for key, value in expected.items()), "Instance ownership mismatch")
        require(types[instance["InstanceType"]]["MemoryInfo"]["SizeInMiB"] >= staged["preset"]["aws"]["minimumMemoryMiB"][node.lower()],
                "Insufficient instance memory")
        burstable = types[instance["InstanceType"]]["BurstablePerformanceSupported"]
        require(type(burstable) is bool and (not burstable or instance["InstanceType"].split(".")[0] in ("t2", "t3", "t3a", "t4g")),
                "Unsupported burstable family")
        instance["burstable"] = burstable
        require(instance["Architecture"] in staged["preset"]["aws"]["supportedArchitectures"], "Unsupported architecture")
        require(instance["MetadataOptions"]["HttpTokens"] == "required" and instance["MetadataOptions"]["HttpPutResponseHopLimit"] == 1,
                "Instance metadata settings differ")
        mappings = instance["BlockDeviceMappings"]
        require(len(mappings) == 1 and mappings[0]["DeviceName"] == instance["RootDeviceName"]
                and mappings[0]["Ebs"]["DeleteOnTermination"], "Unexpected persistent instance disks")
        volume, = aws.call("ec2", "describe-volumes", volume_ids=[mappings[0]["Ebs"]["VolumeId"]])["Volumes"]
        require(volume["Encrypted"] and volume["VolumeType"] == "gp3", "Unexpected root disk settings")
        instance["resolvedRootVolume"] = {key: volume[key] for key in ("VolumeId", "VolumeType", "Size", "Encrypted")}
    database, = aws.call("rds", "describe-db-instances", db_instance_identifier=result["DatabaseIdentifier"])["DBInstances"]
    require(database["DBInstanceStatus"] == "available" and not database["PubliclyAccessible"]
            and database["StorageEncrypted"] and not database["MultiAZ"] and database["BackupRetentionPeriod"] == 0,
            "Database is not ready/private/disposable")
    require(database["Endpoint"]["Address"] == result["DatabaseAddress"]
            and database["AvailabilityZone"] == result["PrimaryAvailabilityZone"], "Database placement differs")
    require(database["Engine"] == "postgres" and database["EngineVersion"].startswith("17.")
            and database["StorageType"] == "gp3" and database["AllocatedStorage"] == 20,
            "Database engine/storage differs from V1 contract")
    result["resolvedDatabase"] = {key: database[key] for key in ("Engine", "EngineVersion", "DBInstanceClass", "AllocatedStorage", "StorageType", "AvailabilityZone")}
    return result, actual, timeout


def documents(staged, result, instances, timeout):
    preset = staged["preset"]
    infrastructure = {"preset": preset["id"], "region": staged["region"], "database": {
        **preset["database"], "identifier": result["DatabaseIdentifier"], "availabilityZone": result["PrimaryAvailabilityZone"],
        "resolved": result["resolvedDatabase"], "version": result["resolvedDatabase"]["EngineVersion"]}}
    for source in ("sendium", "harness"):
        instance = instances[result[source.title() + "InstanceId"]]
        infrastructure[source] = {"instanceId": instance["InstanceId"], "instanceType": instance["InstanceType"],
                                  "architecture": instance["Architecture"], "availabilityZone": instance["Placement"]["AvailabilityZone"],
                                  "imageId": instance["ImageId"], "rootDeviceName": instance["RootDeviceName"],
                                  "blockDeviceMappings": instance["BlockDeviceMappings"],
                                  "rootVolume": instance["resolvedRootVolume"], "execution": "ec2-jvm",
                                  "creditMode": "standard" if instance["burstable"] else "not-applicable"}
    execution = {"schemaVersion": 1, "readinessTimeoutSeconds": preset["measurement"]["readinessTimeoutSeconds"],
                 "retentionSeconds": preset["measurement"]["retentionSeconds"], "intervalBudget": preset["measurement"]["intervalBudget"],
                 "context": {"provenance": {**staged["provenance"], "runIdentity": staged["runIdentity"],
                     "artifacts": [{"name": name, "sha256": artifact["sha256"]} for name, artifact in staged["artifacts"].items()]},
                     "application": {"jvmArguments": preset["runtime"]["java"]["sendiumArguments"],
                                     "connections": {"outgoingTransceivers": 1}, "dlrStorage": "postgresql"},
                     "infrastructure": infrastructure, "diagnostics": ["lifecycle.jsonl", "process.log"]},
                 "resources": {"schemaVersion": 1, "sendiumSamples": None, "harnessSamples": None,
                               "cpuCredits": None, "sampleBudget": preset["measurement"]["sampleBudgetPerSource"]}, "sampling": None}
    db = preset["database"]
    deployment = {**preset["deployment"], "runIdentity": staged["runIdentity"], "preset": preset["id"],
                  "sendiumHost": result["SendiumPrivateAddress"], "harnessHost": result["HarnessPrivateAddress"],
                  "database": {"jdbcUrl": "jdbc:postgresql://" + result["DatabaseAddress"] + ":" + result["DatabasePort"] + "/" + result["DatabaseName"]
                               + "?sslmode=verify-full&sslrootcert=" + db["caBundlePath"],
                               **{key: db[key] for key in ("poolMinSize", "poolMaxSize", "acquisitionTimeoutMillis", "claimDurationSeconds")}}}
    result_documents = {}
    for source in ("sendium", "harness"):
        artifact_names = {"harness": "harness", "sampler": "sampler", "rdsCa": "rdsCa", "jre": source + "Jre"}
        if source == "sendium":
            artifact_names["application"] = "application"
        result_documents[source] = {"schemaVersion": 1, "runIdentity": staged["runIdentity"], "region": staged["region"],
            "bucket": staged["bucket"], "source": source, "artifacts": {name: staged["artifacts"][key] for name, key in artifact_names.items()},
            "jvmArguments": preset["runtime"]["java"][source + "Arguments"], "timeoutSeconds": timeout,
            "sampleIntervalSeconds": preset["measurement"]["sampleIntervalSeconds"], "sampleBudget": preset["measurement"]["sampleBudgetPerSource"]}
    result_documents["sendium"].update(databaseSecretArn=result["DatabaseCredentialsArn"], deployment=deployment, workload=preset["workload"])
    result_documents["harness"]["execution"] = execution
    return result_documents


class Controller:
    def __init__(self, aws, staged, result, directory, sleep=time.sleep, clock=time.monotonic):
        self.aws, self.staged, self.result = aws, staged, result
        self.directory, self.sleep, self.clock = Path(directory), sleep, clock
        self.commands = []
        self.started = set()

    def await_agents(self):
        expected = {self.result[node + "InstanceId"] for node in ("Sendium", "Harness")}
        deadline = self.clock() + 300
        while self.clock() < deadline:
            response = self.aws.call("ssm", "describe-instance-information", filters=[{"Key": "InstanceIds", "Values": sorted(expected)}])
            online = {item["InstanceId"] for item in response.get("InstanceInformationList", [])
                      if item.get("PingStatus") == "Online" and item.get("PlatformType") == "Linux"
                      and tuple(int(part) for part in item.get("AgentVersion", "0").split(".")) >= (3, 3, 40, 0)}
            if online == expected:
                return
            self.sleep(5)
        raise DeploymentError("Compatible SSM agents did not register")

    def command(self, source, phase, background=False):
        staged = self.staged
        work = "/var/lib/sendium-benchmark/" + staged["runIdentity"] + "/control-" + source
        prefix = "runs/" + staged["runIdentity"] + "/"
        shell = ["set -eu", "umask 077", "mkdir -p " + shlex.quote(work), "cd " + shlex.quote(work)]
        for name, key, expected in (
                ("node.py", staged["artifacts"]["node"]["key"], staged["artifacts"]["node"]["sha256"]),
                ("aws_cli.py", staged["artifacts"]["transport"]["key"], staged["artifacts"]["transport"]["sha256"]),
                ("node.json", prefix + "inputs/" + source + "/node.json", self.staged["nodeDigests"][source])):
            shell.append(shlex.join(["aws", "s3api", "get-object", "--region", staged["region"], "--bucket", staged["bucket"], "--key", key, name]) + " >/dev/null")
            shell.append("printf '%s  %s\\n' " + shlex.quote(expected) + " " + shlex.quote(name) + " | sha256sum -c - >/dev/null")
        command = ["python3", work + "/node.py", phase, "--document", work + "/node.json"]
        if background:
            self.started.add(source)
            command = ["systemd-run", "--quiet", "--unit", "sendium-perf-" + staged["runIdentity"] + "-" + source,
                       "--property=RuntimeMaxSec=" + str(staged["timeoutSeconds"] + 120), "--property=TimeoutStopSec=120",
                       "--property=KillMode=mixed", *command]
        shell.append(shlex.join(command))
        response = self.aws.call("ssm", "send-command", instance_ids=[self.result[source.title() + "InstanceId"]],
                                 document_name="AWS-RunShellScript", timeout_seconds=120,
                                 parameters={"commands": ["\n".join(shell)], "executionTimeout": ["600"]})
        command_id = response["Command"]["CommandId"]
        self.commands.append({"source": source, "phase": phase, "commandId": command_id})
        deadline = self.clock() + 660
        while self.clock() < deadline:
            try:
                invocation = self.aws.call("ssm", "get-command-invocation", command_id=command_id,
                                           instance_id=self.result[source.title() + "InstanceId"])
            except AwsError:
                self.sleep(2)
                continue  # SSM invocation propagation can lag SendCommand.
            status = invocation["Status"]
            if status == "Success":
                return
            if status in ("Failed", "Cancelled", "TimedOut", "Cancelling"):
                raise DeploymentError("SSM " + source + " " + phase + " failed")
            self.sleep(2)
        raise DeploymentError("SSM invocation deadline exceeded")

    def node_status(self, source):
        path = self.directory / (source + "-status.json")
        self.aws.get(self.staged["bucket"], "runs/" + self.staged["runIdentity"] + "/outputs/" + source + "/node-status.json", path)
        value = read(path)
        require(value["runIdentity"] == self.staged["runIdentity"] and value["source"] == source, "Node status identity mismatch")
        return value

    def wait(self, source, phases, seconds):
        deadline = self.clock() + seconds
        while self.clock() < deadline:
            try:
                status = self.node_status(source)
                if status["phase"] in phases:
                    return status
                if status["phase"] in ("failed", "finished"):
                    raise DeploymentError("Node stopped before requested phase")
            except AwsError:
                pass
            self.sleep(2)
        raise DeploymentError("Node phase deadline exceeded")

    def run(self):
        outcome = {"schemaVersion": 1, "runIdentity": self.staged["runIdentity"], "exitCode": 1, "issues": [], "commands": self.commands}
        try:
            self.await_agents()
            self.command("sendium", "install")
            self.command("harness", "install")
            self.command("sendium", "configure")
            self.command("harness", "supervise", background=True)
            self.wait("harness", {"listening"}, 60)
            self.command("sendium", "supervise", background=True)
            status = self.wait("harness", {"finished"}, self.staged["timeoutSeconds"] + 120)
            outcome["exitCode"] = status["exitCode"]
            if status.get("retrievalIssues"):
                outcome["issues"].append("NODE_EVIDENCE_UPLOAD_FAILED")
                outcome["exitCode"] = 1
        except (RuntimeError, ValueError, KeyError, KeyboardInterrupt) as error:
            outcome["issues"].append("DEPLOYMENT_OR_EXECUTION_FAILED")
            if isinstance(error, DeploymentError):
                outcome["diagnostic"] = str(error)  # Only controller-authored messages, never AWS stderr.
        finally:
            for source in ("harness", "sendium"):
                if source not in self.started:
                    continue
                try:
                    self.command(source, "stop")
                    stopped = self.wait(source, {"finished", "failed"}, 120)
                    if stopped.get("exitCode", 1) != 0 or stopped.get("retrievalIssues"):
                        outcome["issues"].append(source.upper() + "_NODE_FAILED")
                except (RuntimeError, ValueError, KeyError):
                    outcome["issues"].append(source.upper() + "_STOP_UNCONFIRMED")
            retrieval = retrieve(self.aws, self.staged, self.directory / "retrieved")
            outcome["retrieval"] = retrieval
            if outcome["issues"] or retrieval["issues"]:
                outcome["exitCode"] = 1
            write_new(self.directory / "deployment-outcome.json", outcome)
        return outcome


def retrieve(aws, staged, directory):
    """Exact public-output keys only; preserve every available source after failures."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    result = {"schemaVersion": 1, "retrieved": [], "issues": []}
    names = {"harness": ("results.json", "summary.md", "state.json", "lifecycle.jsonl", "harness-resources.jsonl", "process.log", "node-status.json"),
             "sendium": ("sendium-resources.jsonl", "process.log", "node-status.json")}
    for source, files in names.items():
        (directory / source).mkdir()
        for name in files:
            relative = source + "/" + name
            try:
                aws.get(staged["bucket"], "runs/" + staged["runIdentity"] + "/outputs/" + relative, directory / relative)
                result["retrieved"].append(relative)
            except AwsError:
                result["issues"].append("UNAVAILABLE:" + relative)
    write_new(directory / "retrieval.json", result)
    write_new(directory / "resource-inputs.json", {"schemaVersion": 1,
        "sendiumSamples": "sendium/sendium-resources.jsonl" if "sendium/sendium-resources.jsonl" in result["retrieved"] else None,
        "harnessSamples": "harness/harness-resources.jsonl" if "harness/harness-resources.jsonl" in result["retrieved"] else None,
        "cpuCredits": None, "sampleBudget": staged["preset"]["measurement"]["sampleBudgetPerSource"]})
    return result


def execute(aws, staged, directory):
    directory = Path(directory)
    directory.mkdir(exist_ok=False)
    try:
        result, instances, timeout = inspect_run(aws, staged)
        node_documents = documents(staged, result, instances, timeout)
        staged = {**staged, "nodeDigests": {}, "timeoutSeconds": timeout}
        for source, document in node_documents.items():
            path = directory / (source + "-node.json")
            write_new(path, document)
            staged["nodeDigests"][source] = sha256(path)
            aws.put(staged["bucket"], "runs/" + staged["runIdentity"] + "/inputs/" + source + "/node.json", path, immutable=True)
    except (RuntimeError, ValueError, KeyError, KeyboardInterrupt) as error:
        outcome = {"schemaVersion": 1, "runIdentity": staged["runIdentity"], "exitCode": 1,
                   "issues": ["EXECUTION_PREFLIGHT_FAILED"], "commands": [],
                   "retrieval": retrieve(aws, staged, directory / "retrieved")}
        if isinstance(error, DeploymentError):
            outcome["diagnostic"] = str(error)
        write_new(directory / "deployment-outcome.json", outcome)
        return outcome
    return Controller(aws, staged, result, directory).run()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("stage")
    prepare.add_argument("--plan", required=True)
    prepare.add_argument("--preset", default=str(HERE.parent / "presets/aws-private-jvm-v1.json"))
    prepare.add_argument("--output", required=True)
    for name in ("execute", "retrieve"):
        command = commands.add_parser(name)
        command.add_argument("--staged", required=True)
        command.add_argument("--output", required=True)
    args = parser.parse_args()
    def interrupt(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    try:
        if args.command == "stage":
            plan = read(args.plan)
            with tempfile.TemporaryDirectory() as temporary:
                result = stage(AwsCli(plan["region"]), plan, read(args.preset), temporary)
            write_new(args.output, result)
            return 0
        staged = read(args.staged)
        aws = AwsCli(staged["region"])
        if args.command == "execute":
            return execute(aws, staged, args.output)["exitCode"]
        result = retrieve(aws, staged, args.output)
        return 1 if result["issues"] else 0
    except (OSError, RuntimeError, ValueError, KeyError, ValidationError) as error:
        print("DEPLOYMENT_" + args.command.upper() + "_FAILED", file=sys.stderr)
        if isinstance(error, DeploymentError):
            print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
