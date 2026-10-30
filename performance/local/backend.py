"""Local S3/Secrets are real; this adapter models EC2/RDS/SSM/CloudFormation metadata."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import secrets
import signal
import subprocess
import tempfile
import threading
import time

from aws_cli import AwsCli, AwsError


ACCOUNT = "000000000000"
REGION = "local"
PREFIX = "local-perf"
BOOT = "local-bootstrap"
ROLE = "arn:aws:iam::" + ACCOUNT + ":role/"
FUNCTION = "arn:aws:lambda:local:" + ACCOUNT + ":function:local-perf-expiry"
RULE = "arn:aws:events:local:" + ACCOUNT + ":rule/local-perf-expiry"


def docker(*arguments, check=True, timeout=120):
    result = subprocess.run(["docker", *map(str, arguments)], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("Local Docker operation failed: " + str(arguments[0]) + " " + result.stderr[-1600:])
    return result


def inspect(name):
    result = docker("inspect", name, check=False)
    return json.loads(result.stdout)[0] if result.returncode == 0 else None


def identifier(value):
    return hashlib.sha256(value.encode()).hexdigest()[:17]


class Lab:
    def __init__(self, session):
        self.session = session
        self.bucket = "sendium-local-" + session
        self.labels = ["--label", "sendium.local.session=" + session]
        self.control_network = "sl-" + session + "-control"
        docker("network", "create", *self.labels, self.control_network)
        docker("network", "connect", self.control_network, os.environ["HOSTNAME"])
        self.store = "sl-" + session + "-objects"
        docker("run", "-d", "--name", self.store, *self.labels, "--network", self.control_network, "--network-alias", "objects",
               "-e", "SERVICES=s3,secretsmanager", "localstack/localstack:4.13")
        os.environ.update(AWS_ACCESS_KEY_ID="test", AWS_SECRET_ACCESS_KEY="test", AWS_DEFAULT_REGION="us-east-1",
                          AWS_EC2_METADATA_DISABLED="true", AWS_ENDPOINT_URL="http://objects:4566")
        self.real = AwsCli("us-east-1")
        for _ in range(60):
            try:
                self.real.call("s3api", "list-buckets")
                break
            except AwsError:
                time.sleep(1)
        self.real.call("s3api", "create-bucket", bucket=self.bucket)
        self.roots, self.commands, self.passwords, self.calls = {}, {}, [], []
        self.architecture = {"x86_64": "x86_64", "aarch64": "arm64"}[platform.machine()]
        self.source = json.loads(Path("/opt/build/source-context.json").read_text())
        self.db_version = docker("run", "--rm", "--network", "none", "--entrypoint", "postgres", "postgres:17-alpine", "--version").stdout.split()[-1]
        self.simulation = {"mode": "local-functional-rehearsal", "source": self.source,
            "real": ["AL2023 nodes", "Java25", "S3/Secrets APIs", "PostgreSQL17/TLS", "HTTP/SMPP", "sampling", "publication"],
            "modeled": ["EC2/RDS/CloudFormation metadata", "RDS/CloudWatch resource metrics", "SSM Docker-exec", "systemd watchdog", "IAM runtime CLI scope", "scheduled clock"],
            "limitations": ["No AWS capacity findings; gp3/encryption/AMI metadata are simulated.",
                            "CPU/memory cgroups constrain nodes; host /proc summaries describe the Docker Linux VM.",
                            "RDS resource series are deterministic local fixtures, not observed database utilization."]}

    def bootstrap(self):
        outputs = {"ArtifactBucketName": self.bucket, "RepositoryTag": "cytechmobile/sendium", "GitHubEnvironmentName": "sendium-performance",
            "RunStackPrefix": PREFIX + "-run-", "OrchestratorRoleArn": ROLE + PREFIX + "-orchestrator", "DiagnosticsRoleArn": ROLE + PREFIX + "-diagnostics",
            "CleanupRoleArn": ROLE + PREFIX + "-cleanup", "GitHubRecoveryEnvironmentName": "sendium-performance-recovery",
            "CloudFormationExecutionRoleArn": ROLE + PREFIX + "-cloudformation", "RuntimeBoundaryArn": "arn:aws:iam::" + ACCOUNT + ":policy/local-boundary",
            "RunResourceTtlMinutes": "240", "ExpiryHandlerInstalled": "true", "ExpiryFunctionName": "local-perf-expiry", "ExpiryFunctionArn": FUNCTION,
            "ExpiryExpectedCodeSha256": "local-modeled-package", "ExpiryRoleArn": ROLE + PREFIX + "-expiry", "ExpiryScheduleName": "local-perf-expiry", "ExpiryScheduleArn": RULE}
        return {"StackId": "arn:aws:cloudformation:local:" + ACCOUNT + ":stack/" + BOOT + "/bootstrap", "StackStatus": "CREATE_COMPLETE",
                "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in outputs.items()]}

    def new_root(self, parameters, tags, fault):
        identity = parameters["RunIdentity"]
        name = PREFIX + "-run-" + identity
        short = "sl-" + identifier(identity)[:10]
        root = {"identity": identity, "name": name, "short": short, "fault": fault, "parameters": parameters,
            "StackId": "arn:aws:cloudformation:local:" + ACCOUNT + ":stack/" + name + "/" + identifier(identity),
            "StackName": name, "RoleARN": ROLE + PREFIX + "-cloudformation", "Tags": tags, "StackStatus": "CREATE_IN_PROGRESS",
            "containers": {}, "networks": {}, "volumes": [], "checks": {}}
        self.roots[identity] = root
        labels = [*self.labels, "--label", "sendium.local.run=" + identity]
        for network in ("traffic", "database", "management"):
            network_name = short + "-" + network
            docker("network", "create", "--internal", *labels, network_name)
            root["networks"][network] = network_name
        docker("network", "connect", "--alias", "objects", root["networks"]["management"], self.store)
        tls = short + "-tls"
        docker("volume", "create", *labels, tls)
        root["volumes"].append(tls)
        private = Path("/state/private") / identity
        docker("run", "--rm", "--network", "none", "--mount", "type=volume,source=" + tls + ",target=/tls",
               "--mount", "type=volume,source=" + os.environ["LOCAL_STATE_VOLUME"] + ",target=/state,readonly",
               "--entrypoint", "sh", "sendium-local:node", "-c", "cp -a /state/private/" + identity + "/tls/. /tls/")
        password = (private / "tls/password").read_text()
        self.passwords.append(password)
        secret_file = private / "secret.json"
        secret_file.write_text(json.dumps({"username": "sendium_perf", "password": password}))
        secret_name = PREFIX + "/runs/" + identity + "/database"
        root["secret"] = self.real.call("secretsmanager", "create-secret", name=secret_name, secret_string="file://" + str(secret_file))["ARN"]
        db = short + "-db"
        data = short + "-data"
        docker("volume", "create", *labels, data)
        root["volumes"].append(data)
        root["containers"]["database"] = db
        docker("run", "-d", "--name", db, *labels, "--network", root["networks"]["database"], "--network-alias", "db",
               "--mount", "type=volume,source=" + data + ",target=/var/lib/postgresql/data",
               "--mount", "type=volume,source=" + tls + ",target=/tls,readonly", "--env", "POSTGRES_USER=sendium_perf",
               "--env", "POSTGRES_DB=sendium_benchmark", "--env", "POSTGRES_PASSWORD_FILE=/tls/password", "postgres:17-alpine",
               "postgres", "-c", "ssl=on", "-c", "ssl_cert_file=/tls/server.pem", "-c", "ssl_key_file=/tls/server.key", "-c", "hba_file=/tls/pg_hba.conf")
        for _ in range(60):
            # The entrypoint's temporary init server accepts Unix-socket probes
            # before initialization finishes. Wait for the final TCP listener.
            ready = docker("exec", db, "pg_isready", "-h", "127.0.0.1", "-U", "sendium_perf", "-d", "sendium_benchmark", check=False)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Local PostgreSQL readiness deadline exceeded")
        root["dbVersion"] = docker("exec", db, "psql", "-U", "sendium_perf", "-d", "sendium_benchmark", "-Atc", "SHOW server_version").stdout.split()[0]
        plain = docker("exec", db, "psql", "host=127.0.0.1 user=sendium_perf dbname=sendium_benchmark sslmode=disable", "-c", "SELECT 1", check=False)
        root["checks"]["plaintextRejected"] = plain.returncode != 0 and any(word in plain.stderr.lower() for word in ("reject", "no encryption", "ssl off"))
        if not root["checks"]["plaintextRejected"]:
            raise RuntimeError("Local PostgreSQL accepted plaintext")
        for source in ("sendium", "harness"):
            node = short + "-" + source
            root["containers"][source] = node
            docker("create", "--name", node, *labels, "--init", "--cpus", "2", "--memory", "2g", "--memory-swap", "2g",
                   "--network", root["networks"]["management"], "--env", "AWS_ACCESS_KEY_ID=test", "--env", "AWS_SECRET_ACCESS_KEY=test",
                   "--env", "AWS_ENDPOINT_URL=http://objects:4566", "--env", "AWS_EC2_METADATA_DISABLED=true",
                   "--env", "LOCAL_NODE_ROLE=" + source, "--env", "LOCAL_RUN_IDENTITY=" + identity, "--env", "LOCAL_BUCKET=" + self.bucket,
                   "--env", "LOCAL_DATABASE_SECRET_ARN=" + root["secret"],
                   "sendium-local:node")
            docker("network", "connect", "--alias", source, root["networks"]["traffic"], node)
            if source == "sendium":
                docker("network", "connect", "--alias", source, root["networks"]["database"], node)
            docker("start", node)
            if fault == "partial-create" and source == "sendium":
                root["StackStatus"] = "CREATE_FAILED"
                raise AwsError("Injected partial creation", code="LocalPartialCreate")
        root["StackStatus"] = "CREATE_COMPLETE"
        return root

    def find(self, name):
        return next((root for root in self.roots.values() if name in (root["name"], root["StackId"])), None)

    def instance(self, root, source):
        node = inspect(root["containers"][source])
        ip = node["NetworkSettings"]["Networks"][root["networks"]["traffic"]]["IPAddress"]
        return {"InstanceId": "i-" + identifier(root["identity"] + source), "InstanceType": "local.small", "Architecture": self.architecture,
            "ImageId": "ami-11111111", "RootDeviceName": "/dev/xvda", "PrivateIpAddress": ip, "SubnetId": "subnet-local-a",
            "Placement": {"AvailabilityZone": "local-a"}, "State": {"Name": "running" if node["State"]["Running"] else "stopped"},
            "Tags": root["Tags"] + [{"Key": "sendium:role", "Value": source}], "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 1},
            "BlockDeviceMappings": [{"DeviceName": "/dev/xvda", "Ebs": {"VolumeId": "vol-" + identifier(root["identity"] + source), "DeleteOnTermination": True}}]}

    def describe(self, root):
        result = {key: root[key] for key in ("StackId", "StackName", "StackStatus", "RoleARN", "Tags")}
        if root["StackStatus"] == "CREATE_COMPLETE":
            sendium, harness = self.instance(root, "sendium"), self.instance(root, "harness")
            output = {"RunIdentity": root["identity"], "ExpiresAt": root["parameters"]["ExpiresAt"], "ArtifactRunPrefix": "runs/" + root["identity"] + "/",
                "PrimarySubnetId": "subnet-local-a", "SecondarySubnetId": "subnet-local-b", "PrimaryAvailabilityZone": "local-a",
                "SendiumInstanceId": sendium["InstanceId"], "HarnessInstanceId": harness["InstanceId"],
                "SendiumPrivateAddress": sendium["PrivateIpAddress"], "HarnessPrivateAddress": harness["PrivateIpAddress"],
                "DatabaseIdentifier": root["name"], "DatabaseAddress": "db", "DatabasePort": "5432", "DatabaseName": "sendium_benchmark",
                "DatabaseCredentialsArn": root["secret"]}
            result["Outputs"] = [{"OutputKey": key, "OutputValue": value} for key, value in output.items()]
        return result

    def delete(self, root):
        if root is None or root["StackStatus"] == "DELETE_COMPLETE":
            return
        root["StackStatus"] = "DELETE_IN_PROGRESS"
        try:
            self.delete_resources(root)
        except Exception:
            root["StackStatus"] = "DELETE_FAILED"
            raise AwsError("Local resource deletion incomplete", code="LocalDeleteFailed") from None
        root["StackStatus"] = "DELETE_COMPLETE"

    def delete_resources(self, root):
        for name in root["containers"].values():
            if inspect(name):
                docker("rm", "-f", "-v", name)
        if root.get("secret"):
            try:
                self.real.call("secretsmanager", "delete-secret", secret_id=root["secret"], force_delete_without_recovery=True)
            except AwsError as error:
                if error.code != "ResourceNotFoundException":
                    raise
        for network in root["networks"].values():
            if docker("network", "inspect", network, check=False).returncode == 0:
                docker("network", "disconnect", network, self.store, check=False)
                docker("network", "rm", network)
        for volume in root["volumes"]:
            if docker("volume", "inspect", volume, check=False).returncode == 0:
                docker("volume", "rm", volume)


class LocalAws:
    execution_kind = "container-jvm"

    def __init__(self, lab, identity, mode="orchestrator", fault=None):
        self.lab, self.identity, self.mode, self.fault = lab, identity, mode, fault
        self.timeout_seconds = 120
        self.simulation = lab.simulation
        self.stage_puts = 0

    def put(self, bucket, key, path, immutable=False):
        if self.mode != "orchestrator":
            raise AwsError("Read-only local role", code="AccessDenied")
        self.stage_puts += 1
        if self.fault == "partial-stage" and self.stage_puts == 3:
            raise AwsError("Injected staging failure", code="LocalStageFailure")
        return self.lab.real.put(bucket, key, path, immutable)

    def get(self, bucket, key, path):
        if self.mode == "cleanup":
            raise AwsError("Cleanup cannot read private objects", code="AccessDenied")
        if self.mode == "diagnostics" and "/outputs/" not in key or self.mode == "expiry" and not key.endswith("/control/run.json"):
            raise AwsError("Local role cannot read private input", code="AccessDenied")
        return self.lab.real.get(bucket, key, path)

    def call(self, service, operation, **p):
        if self.mode == "cleanup" and (service, operation) not in {
                ("sts", "get-caller-identity"), ("cloudformation", "describe-stacks"), ("cloudformation", "delete-stack"),
                ("s3api", "get-bucket-versioning"), ("s3api", "list-objects-v2"), ("s3api", "list-multipart-uploads"),
                ("s3api", "delete-objects"), ("s3api", "abort-multipart-upload")}:
            raise AwsError("Deletion-only cleanup role", code="AccessDenied")
        self.lab.calls.append({"role": self.mode, "service": service, "operation": operation})
        root = self.lab.roots.get(self.identity)
        if service == "s3api":
            if self.mode == "diagnostics" and operation not in ("get-object",):
                raise AwsError("Read-only local diagnostic API", code="AccessDenied")
            if operation == "get-object":
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "object"
                    self.get(p["bucket"], p["key"], path)
                    data = path.read_bytes()
                    return {"Body": io.BytesIO(data), "ContentLength": len(data)}
            return self.lab.real.call(service, operation, **p)
        if service == "sts":
            return {"Account": ACCOUNT, "Arn": "arn:aws:sts::" + ACCOUNT + ":assumed-role/local-perf-" + self.mode + "/local"}
        if service == "cloudformation":
            if operation == "describe-stacks":
                if p["stack_name"] == BOOT:
                    return {"Stacks": [self.lab.bootstrap()]}
                found = self.lab.find(p["stack_name"])
                if found is None or found["StackStatus"] == "DELETE_COMPLETE" and p["stack_name"] == found["name"]:
                    raise AwsError("Missing local stack", code="StackNotFound")
                return {"Stacks": [self.lab.describe(found)]}
            if operation == "list-stacks":
                return {"StackSummaries": [{"StackName": item["name"], "StackStatus": item["StackStatus"]} for item in self.lab.roots.values() if item["StackStatus"] in p["stack_status_filter"]]}
            if operation == "create-stack" and self.mode == "orchestrator":
                found = self.lab.new_root({item["ParameterKey"]: item["ParameterValue"] for item in p["parameters"]}, p["tags"], self.fault)
                if self.fault == "abandoned":
                    raise AwsError("Injected lost controller after creation", code="LocalRunnerLost")
                return {"StackId": found["StackId"]}
            if operation == "delete-stack" and self.mode in ("orchestrator", "cleanup", "expiry"):
                self.lab.delete(self.lab.find(p["stack_name"]))
                return {}
            if operation == "describe-stack-events":
                return {"StackEvents": []}
        if service == "lambda":
            if operation == "get-function-configuration":
                return {"State": "Active", "LastUpdateStatus": "Successful", "FunctionArn": FUNCTION, "CodeSha256": "local-modeled-package", "Role": ROLE + PREFIX + "-expiry",
                    "Handler": "expiry.handler", "Runtime": "python3.13", "Timeout": 840, "Environment": {"Variables": {"BOOTSTRAP_STACK": BOOT,
                    "REPOSITORY": "cytechmobile/sendium", "ARTIFACT_BUCKET": self.lab.bucket, "RUN_TTL_MINUTES": "240"}}}
            if operation == "get-function-concurrency":
                return {}
            if operation == "get-function-event-invoke-config":
                return {"MaximumRetryAttempts": 0, "MaximumEventAgeInSeconds": 900}
            if operation == "get-policy":
                return {"Policy": json.dumps({"Statement": [{"Effect": "Allow", "Action": "lambda:InvokeFunction", "Principal": {"Service": "events.amazonaws.com"}, "Resource": FUNCTION,
                    "Condition": {"ArnLike": {"AWS:SourceArn": RULE}, "StringEquals": {"AWS:SourceAccount": ACCOUNT}}}]})}
        if service == "events":
            return {"State": "ENABLED", "ScheduleExpression": "rate(15 minutes)", "Arn": RULE} if operation == "describe-rule" else {
                "Targets": [{"Id": "Expiry", "Arn": FUNCTION, "RetryPolicy": {"MaximumRetryAttempts": 0, "MaximumEventAgeInSeconds": 900}}]}
        if service == "ec2":
            return self.ec2(operation, p)
        if service == "cloudwatch" and operation == "get-metric-statistics":
            if (self.mode != "diagnostics" or root is None or p.get("namespace") != "AWS/RDS"
                    or p.get("dimensions") != [{"Name": "DBInstanceIdentifier", "Value": root["name"]}]):
                raise AwsError("Local metrics require the owned run database", code="AccessDenied")
            units = {"CPUUtilization": "Percent", "FreeableMemory": "Bytes", "SwapUsage": "Bytes", "DatabaseConnections": "Count",
                     "ReadIOPS": "Count/Second", "WriteIOPS": "Count/Second", "ReadThroughput": "Bytes/Second", "WriteThroughput": "Bytes/Second",
                     "ReadLatency": "Seconds", "WriteLatency": "Seconds", "DiskQueueDepth": "Count", "FreeStorageSpace": "Bytes",
                     "CPUCreditBalance": "Count", "CPUCreditUsage": "Count", "CPUSurplusCreditBalance": "Count", "CPUSurplusCreditsCharged": "Count"}
            if p.get("metric_name") not in units or p.get("period") not in (60, 300):
                raise AwsError("Unsupported local metric", code="LocalUnsupportedOperation")
            start = datetime.fromisoformat(p["start_time"].replace("Z", "+00:00"))
            end = datetime.fromisoformat(p["end_time"].replace("Z", "+00:00"))
            if not 0 < (end - start).total_seconds() / p["period"] <= 1440:
                raise AwsError("Local metric point budget exceeded", code="LocalUnsupportedOperation")
            points = []
            while start < end:
                points.append({"Timestamp": start.isoformat(), "Unit": units[p["metric_name"]],
                               **{statistic: 1 for statistic in p["statistics"]}})
                start += timedelta(seconds=p["period"])
            return {"Datapoints": points}
        if service == "rds":
            if operation == "describe-db-engine-versions":
                return {"DBEngineVersions": [{"EngineVersion": self.lab.db_version, "Status": "available"}]}
            if operation == "describe-orderable-db-instance-options":
                return {"OrderableDBInstanceOptions": [{"StorageType": "gp3", "SupportsStorageEncryption": True, "Vpc": True, "MinStorageSize": 20, "MaxStorageSize": 20,
                    "AvailabilityZones": [{"Name": "local-a"}, {"Name": "local-b"}]}]}
            arn = "arn:aws:rds:" + REGION + ":" + ACCOUNT + ":db:" + root["name"]
            if operation == "list-tags-for-resource":
                if p.get("resource_name") != arn:
                    raise AwsError("Local database ownership mismatch", code="AccessDenied")
                return {"TagList": root["Tags"]}
            if p.get("db_instance_identifier") != root["name"]:
                raise AwsError("Local database identity mismatch", code="DBInstanceNotFound")
            return {"DBInstances": [{"DBInstanceIdentifier": root["name"], "DBInstanceArn": arn,
                "DBInstanceStatus": "available", "PubliclyAccessible": False, "StorageEncrypted": True, "MultiAZ": False,
                "BackupRetentionPeriod": 0, "Endpoint": {"Address": "db"}, "AvailabilityZone": "local-a", "Engine": "postgres", "EngineVersion": root["dbVersion"],
                "DBInstanceClass": "db.local.small", "AllocatedStorage": 20, "StorageType": "gp3"}]}
        if service == "ssm":
            if operation == "get-parameter":
                return {"Parameter": {"Name": p["name"], "Version": 1, "Value": "ami-11111111"}}
            if operation == "describe-instance-information":
                return {"InstanceInformationList": [{"InstanceId": item, "PingStatus": "Online", "PlatformType": "Linux", "AgentVersion": "3.3.999.0"} for item in p["filters"][0]["Values"]]}
            if operation == "send-command":
                return self.send(root, p)
            if operation == "get-command-invocation":
                return {"Status": self.lab.commands[p["command_id"]]}
        raise AwsError("Unsupported local operation", code="LocalUnsupportedOperation")

    def ec2(self, operation, p):
        arch = self.lab.architecture
        if operation == "describe-instance-types":
            return {"InstanceTypes": [{"InstanceType": "local.small", "ProcessorInfo": {"SupportedArchitectures": [arch]}, "MemoryInfo": {"SizeInMiB": 2048},
                "VCpuInfo": {"DefaultVCpus": 2}, "SupportedRootDeviceTypes": ["ebs"], "SupportedVirtualizationTypes": ["hvm"], "SupportedUsageClasses": ["on-demand"],
                "SupportedBootModes": ["uefi"], "BurstablePerformanceSupported": False}]}
        if operation == "describe-images":
            return {"Images": [{"ImageId": "ami-11111111", "State": "available", "Architecture": arch, "RootDeviceType": "ebs", "VirtualizationType": "hvm",
                "Name": "al2023-local-container", "ImageOwnerAlias": "amazon", "OwnerId": ACCOUNT, "RootDeviceName": "/dev/xvda", "BootMode": "uefi",
                "BlockDeviceMappings": [{"DeviceName": "/dev/xvda", "Ebs": {"VolumeSize": 8, "SnapshotId": "snap-00000000"}}]}]}
        if operation == "describe-instance-type-offerings":
            return {"InstanceTypeOfferings": [{"InstanceType": "local.small", "Location": zone} for zone in ("local-a", "local-b")]}
        if operation == "describe-availability-zones":
            return {"AvailabilityZones": [{"ZoneName": zone, "ZoneId": zone, "State": "available", "ZoneType": "availability-zone", "OptInStatus": "opt-in-not-required"} for zone in ("local-a", "local-b")]}
        if operation == "describe-vpc-endpoint-services":
            return {"ServiceDetails": [{"ServiceName": name, "ServiceType": [{"ServiceType": "Gateway" if name.endswith(".s3") else "Interface"}],
                "PrivateDnsName": "modeled.local", "AvailabilityZones": ["local-a", "local-b"]} for name in p["service_names"]]}
        if operation == "describe-managed-prefix-lists":
            return {"PrefixLists": [{"OwnerId": "AWS", "AddressFamily": "IPv4", "PrefixListId": "pl-00000000"}]}
        if operation == "describe-volumes":
            return {"Volumes": [{"VolumeId": p["volume_ids"][0], "VolumeType": "gp3", "Size": 8, "Encrypted": True}]}
        if operation == "describe-instances":
            return {"Reservations": [{"Instances": [self.lab.instance(root, source) for root in self.lab.roots.values() if root["StackStatus"] == "CREATE_COMPLETE"
                for source in ("sendium", "harness") if "i-" + identifier(root["identity"] + source) in p["instance_ids"]]}]}
        raise AwsError("Unsupported local EC2 metadata", code="LocalUnsupportedOperation")

    def send(self, root, p):
        source, = [source for source in ("sendium", "harness") if self.lab.instance(root, source)["InstanceId"] in p["instance_ids"]]
        command = p["parameters"]["commands"][0]
        if source == "sendium" and "systemd-run" in command and root["fault"] == "bad-tls":
            verify = docker("exec", root["containers"][source], "openssl", "s_client", "-connect", "db:5432", "-starttls", "postgres",
                            "-CAfile", "/opt/sendium-benchmark/rds-ca/global-bundle.pem", "-verify_return_error", "-verify_hostname", "db", check=False)
            root["checks"]["invalidCaRejected"] = verify.returncode != 0 and "certificate" in verify.stderr.lower()
        if source == "sendium" and "systemd-run" in command and root["fault"] == "database-unavailable":
            docker("stop", "-t", "1", root["containers"]["database"])
            root["checks"]["databaseUnavailableInjected"] = True
        result = docker("exec", root["containers"][source], "sh", "-c", command, check=False, timeout=650)
        key = "command-" + secrets.token_hex(12)
        self.lab.commands[key] = "Success" if result.returncode == 0 else "Failed"
        return {"Command": {"CommandId": key}}
