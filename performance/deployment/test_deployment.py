"""Offline failure-path and trust-boundary checks for deployment orchestration."""

import io
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import tarfile
import unittest
from unittest.mock import patch

from aws_cli import AwsError
import deploy
from node import Node, child_environment, digest, extract, write


class MemoryAws:
    def __init__(self):
        self.objects = {}
        self.uploads = []
        self.calls = []

    def put(self, bucket, key, path, immutable=False):
        if immutable and (bucket, key) in self.objects:
            raise AwsError("Object already exists")
        self.objects[bucket, key] = Path(path).read_bytes()
        self.uploads.append(key)

    def get(self, bucket, key, path):
        if (bucket, key) not in self.objects:
            raise AwsError("Unavailable")
        Path(path).write_bytes(self.objects[bucket, key])

    def call(self, service, operation, **parameters):
        self.calls.append((service, operation, parameters))
        if operation == "get-secret-value":
            return {"SecretString": json.dumps({"username": "sendium_perf", "password": "private-database-password"})}
        if operation == "send-command":
            return {"Command": {"CommandId": "command-1"}}
        if operation == "get-command-invocation":
            return {"Status": "Success"}
        raise AwsError("Unexpected API")


def staged():
    preset = deploy.read(deploy.HERE.parent / "presets/aws-private-jvm-v1.json")
    return {"schemaVersion": 1, "runIdentity": "gh-12345-1", "region": "eu-west-1", "repository": "cytechmobile/sendium",
            "bucket": "benchmark-artifacts", "bootstrapStack": "bootstrap", "runStack": "sendium-perf-run-gh-12345-1",
            "preset": preset, "provenance": {"branch": "main", "applicationCommit": "ab" * 20, "harnessRevision": "cd" * 20,
                                           "workflowRunUrl": None},
            "artifacts": {name: {"key": "runs/gh-12345-1/inputs/common/" + name, "sha256": "ab" * 32, "size": 10}
                          for name in ("application", "harness", "sendiumJre", "harnessJre", "rdsCa", "node", "transport", "sampler")}}


def deployment_outputs():
    return {"SendiumInstanceId": "i-12345678901234567", "HarnessInstanceId": "i-23456789012345678",
            "SendiumPrivateAddress": "10.70.0.5", "HarnessPrivateAddress": "10.70.0.6", "DatabaseIdentifier": "database",
            "DatabaseAddress": "database.eu-west-1.rds.amazonaws.com", "DatabasePort": "5432", "DatabaseName": "sendium_benchmark",
            "DatabaseCredentialsArn": "arn:aws:secretsmanager:eu-west-1:123456789012:secret:run/database-123456",
            "PrimaryAvailabilityZone": "eu-west-1a", "resolvedDatabase": {"EngineVersion": "17.6"}}


class DeploymentTests(unittest.TestCase):
    def test_native_requests_cannot_stage_or_provision_before_launcher_integration(self):
        state = staged()
        state["preset"]["runtime"]["applicationRuntime"] = "native"
        aws = MemoryAws()
        with self.assertRaisesRegex(deploy.DeploymentError, "Native application deployment is not enabled"):
            deploy.stage(aws, state, state["preset"], Path("unused"))
        self.assertEqual(aws.calls, [])
        self.assertEqual(aws.uploads, [])

    def test_preset_cannot_silently_change_fixed_network_ports(self):
        state = staged()
        state["preset"]["deployment"]["httpPort"] = 8082
        with self.assertRaisesRegex(deploy.DeploymentError, "V1 deployment contract"):
            deploy.validate_plan(state, state["preset"])

    def test_execution_preflight_accepts_owned_resources_and_rejects_public_instance(self):
        state = staged()
        result = deployment_outputs()
        expiry = (datetime.now(timezone.utc) + timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:%SZ")
        result.update(RunIdentity=state["runIdentity"], ArtifactRunPrefix="runs/" + state["runIdentity"] + "/",
                      ExpiresAt=expiry, PrimarySubnetId="subnet-12345678")
        result.pop("resolvedDatabase")
        tags = {"sendium:managed-by": "performance-tests", "sendium:repository": state["repository"],
                "sendium:run-identity": state["runIdentity"], "sendium:expires-at": expiry}
        tag_list = [{"Key": key, "Value": value} for key, value in tags.items()]
        role = "arn:aws:iam::123456789012:role/sendium-perf-cloudformation"
        boot_values = {"ArtifactBucketName": state["bucket"], "RunStackPrefix": "sendium-perf-run-",
                       "RepositoryTag": state["repository"], "ExpiryHandlerInstalled": "true", "CloudFormationExecutionRoleArn": role,
                       "RunResourceTtlMinutes": "240"}
        boot = {"StackStatus": "CREATE_COMPLETE", "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in boot_values.items()]}
        root = {"StackStatus": "CREATE_COMPLETE", "RoleARN": role, "Tags": tag_list,
                "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in result.items()]}
        instances = [{"InstanceId": result[node + "InstanceId"], "State": {"Name": "running"},
                      "PrivateIpAddress": result[node + "PrivateAddress"], "SubnetId": result["PrimarySubnetId"],
                      "InstanceType": "t4g.small", "Architecture": "arm64", "Tags": tag_list + [{"Key": "sendium:role", "Value": node.lower()}],
                      "RootDeviceName": "/dev/xvda", "BlockDeviceMappings": [{"DeviceName": "/dev/xvda",
                           "Ebs": {"DeleteOnTermination": True, "VolumeId": "vol-12345678"}}],
                      "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 1}} for node in ("Sendium", "Harness")]
        responses = {"describe-instances": {"Reservations": [{"Instances": instances}]},
                     "describe-instance-types": {"InstanceTypes": [{"InstanceType": "t4g.small", "MemoryInfo": {"SizeInMiB": 2048}, "BurstablePerformanceSupported": True}]},
                     "describe-volumes": {"Volumes": [{"VolumeId": "vol-12345678", "Size": 8, "VolumeType": "gp3", "Encrypted": True}]},
                     "describe-db-instances": {"DBInstances": [{"DBInstanceStatus": "available", "PubliclyAccessible": False,
                        "StorageEncrypted": True, "MultiAZ": False, "BackupRetentionPeriod": 0, "Endpoint": {"Address": result["DatabaseAddress"]},
                        "AvailabilityZone": "eu-west-1a", "Engine": "postgres", "EngineVersion": "17.6", "DBInstanceClass": "db.t4g.small",
                        "AllocatedStorage": 20, "StorageType": "gp3"}]}}
        aws = MemoryAws()
        with patch.object(deploy, "verify_expiry"), patch.object(deploy, "stack", side_effect=[boot, root]), patch.object(aws, "call", side_effect=lambda service, operation, **_: responses[operation]):
            inspected, actual, timeout = deploy.inspect_run(aws, state)
        self.assertEqual(inspected["resolvedDatabase"]["EngineVersion"], "17.6")
        self.assertEqual(len(actual), 2)
        self.assertGreater(timeout, 840)
        source_tag = next(tag for tag in instances[0]["Tags"] if tag["Key"] == "sendium:role")
        source_tag["Value"] = "harness"
        with patch.object(deploy, "verify_expiry"), patch.object(deploy, "stack", side_effect=[boot, root]), patch.object(aws, "call", side_effect=lambda service, operation, **_: responses[operation]):
            with self.assertRaisesRegex(deploy.DeploymentError, "ownership/source"):
                deploy.inspect_run(aws, state)
        source_tag["Value"] = "sendium"
        instances[0]["InstanceType"] = "m7i.large"
        with patch.object(deploy, "verify_expiry"), patch.object(deploy, "stack", side_effect=[boot, root]), patch.object(aws, "call", side_effect=lambda service, operation, **_: responses[operation]):
            with self.assertRaisesRegex(deploy.DeploymentError, "Requested instance"):
                deploy.inspect_run(aws, state)
        instances[0]["InstanceType"] = "t4g.small"
        instances[0]["PublicIpAddress"] = "203.0.113.1"
        with patch.object(deploy, "verify_expiry"), patch.object(deploy, "stack", side_effect=[boot, root]), patch.object(aws, "call", side_effect=lambda service, operation, **_: responses[operation]):
            with self.assertRaises(deploy.DeploymentError):
                deploy.inspect_run(aws, state)

    def test_artifact_tampering_fails_before_any_aws_calls(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact"
            path.write_bytes(b"artifact")
            plan = staged()
            plan["artifacts"] = {name: {"path": str(path), "sha256": "ab" * 32}
                                 for name in ("application", "harness", "sendiumJre", "harnessJre", "rdsCa")}
            aws = MemoryAws()
            with self.assertRaises(deploy.DeploymentError):
                deploy.stage(aws, plan, plan["preset"], directory)
            self.assertFalse(aws.calls)
            self.assertFalse(aws.uploads)

    def test_staging_uses_immutable_run_keys_and_omits_local_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            artifact = directory / "artifact"
            artifact.write_bytes(b"artifact")
            plan = staged()
            plan["artifacts"] = {name: {"path": str(artifact), "sha256": digest(artifact)}
                                 for name in ("application", "harness", "sendiumJre", "harnessJre", "rdsCa")}
            aws = MemoryAws()
            boot = {"StackStatus": "CREATE_COMPLETE", "Outputs": [
                {"OutputKey": key, "OutputValue": value} for key, value in
                {"ArtifactBucketName": plan["bucket"], "RunStackPrefix": "sendium-perf-run-", "RepositoryTag": plan["repository"]}.items()]}
            with patch.object(deploy, "stack", return_value=boot):
                result = deploy.stage(aws, plan, plan["preset"], directory)
            self.assertNotIn(str(directory), json.dumps(result))
            self.assertTrue(all(key.startswith("runs/gh-12345-1/") for key in aws.uploads))
            self.assertEqual(result["artifacts"]["application"]["key"], "runs/gh-12345-1/inputs/sendium/application")
            self.assertEqual(aws.uploads[0], "runs/gh-12345-1/control/run.json")
            marker = json.loads(aws.objects[plan["bucket"], aws.uploads[0]])
            self.assertEqual(marker["expiresAt"], result["expiresAt"])
            self.assertNotIn("provenance", marker)
            # Inputs cannot be overwritten, even if a retry has identical bytes.
            with self.assertRaises(AwsError):
                aws.put(plan["bucket"], result["artifacts"]["application"]["key"], artifact, immutable=True)

    def test_expiry_preflight_blocks_execution_and_still_retrieves_available_results(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            state = staged()
            aws.objects[state["bucket"], "runs/gh-12345-1/outputs/harness/results.json"] = b'{"validity":{"status":"failed"}}'
            boot = {"StackStatus": "CREATE_COMPLETE", "Outputs": [
                {"OutputKey": key, "OutputValue": value} for key, value in
                {"ArtifactBucketName": state["bucket"], "RunStackPrefix": "sendium-perf-run-",
                 "RepositoryTag": state["repository"], "ExpiryHandlerInstalled": "false"}.items()]}
            with patch.object(deploy, "stack", return_value=boot):
                outcome = deploy.execute(aws, state, Path(directory) / "execution")
            self.assertEqual(outcome["exitCode"], 1)
            self.assertEqual(outcome["diagnostic"], "Independent expiry handler is not installed")
            self.assertIn("harness/results.json", outcome["retrieval"]["retrieved"])
            self.assertFalse(aws.calls)

    def test_scenario_failure_stops_both_nodes_and_keeps_failed_report(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            state = staged()
            aws.objects[state["bucket"], "runs/gh-12345-1/outputs/harness/results.json"] = b'{"failed":true}'
            controller = deploy.Controller(aws, state, deployment_outputs(), directory)
            calls = []
            def command(source, phase, background=False):
                calls.append((source, phase))
                if background:
                    controller.started.add(source)
            def wait(source, phases, seconds):
                return {"phase": "listening" if "listening" in phases else "finished", "exitCode": 1 if source == "harness" else 0}
            with patch.object(controller, "await_agents"), patch.object(controller, "command", side_effect=command), patch.object(controller, "wait", side_effect=wait):
                result = controller.run()
            self.assertEqual(result["exitCode"], 1)
            self.assertEqual(calls[-2:], [("harness", "stop"), ("sendium", "stop")])
            self.assertTrue((Path(directory) / "retrieved/harness/results.json").is_file())
            self.assertTrue((Path(directory) / "deployment-outcome.json").is_file())

    def test_ssm_background_command_contains_only_identifiers_and_checked_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            state = staged() | {"nodeDigests": {"harness": "ab" * 32}, "timeoutSeconds": 900}
            controller = deploy.Controller(aws, state, deployment_outputs(), directory)
            controller.command("harness", "supervise", background=True)
            command = aws.calls[0][2]
            shell = command["parameters"]["commands"][0]
            self.assertIn("systemd-run", shell)
            self.assertEqual(shell.count("sha256sum -c"), 3)
            self.assertNotIn("secret", shell.lower())
            self.assertNotIn("password", shell.lower())
            self.assertNotIn("-jar", shell)
            self.assertEqual(command["document_name"], "AWS-RunShellScript")

    def test_node_documents_keep_database_secret_and_connection_details_off_harness(self):
        state = staged()
        result = deployment_outputs()
        instances = {result[source.title() + "InstanceId"]: {"InstanceId": result[source.title() + "InstanceId"],
                     "InstanceType": "t4g.small", "Architecture": "arm64", "Placement": {"AvailabilityZone": "eu-west-1a"},
                     "ImageId": "ami-12345678", "RootDeviceName": "/dev/xvda", "BlockDeviceMappings": [],
                     "resolvedRootVolume": {"VolumeType": "gp3", "Size": 8, "Encrypted": True}, "burstable": True}
                     for source in ("sendium", "harness")}
        documents = deploy.documents(state, result, instances, 1000)
        self.assertIn("databaseSecretArn", documents["sendium"])
        self.assertNotIn(result["DatabaseCredentialsArn"], json.dumps(documents["harness"]))
        self.assertNotIn("jdbc:", json.dumps(documents["harness"]))
        self.assertIn("sslmode=verify-full", documents["sendium"]["deployment"]["database"]["jdbcUrl"])
        self.assertEqual(documents["harness"]["execution"]["context"]["infrastructure"]["database"]["version"], "17.6")

    def test_retrieval_is_allowlisted_and_preserves_sources_when_one_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            aws, state = MemoryAws(), staged()
            prefix = "runs/gh-12345-1/outputs/"
            aws.objects[state["bucket"], prefix + "sendium/process.log"] = b"diagnostic"
            aws.objects[state["bucket"], "runs/gh-12345-1/inputs/sendium/environment.json"] = b"secret"
            result = deploy.retrieve(aws, state, Path(directory) / "out")
            self.assertEqual(result["retrieved"], ["sendium/process.log"])
            self.assertTrue(result["issues"])
            self.assertFalse(any(path.name == "environment.json" for path in Path(directory).rglob("*")))
            resources = deploy.read(Path(directory) / "out/resource-inputs.json")
            self.assertIsNone(resources["sendiumSamples"])
            self.assertIsNone(resources["cpuCredits"])

    def test_node_rejects_changed_download(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            aws.objects["bucket", "input"] = b"changed"
            node = Node({"source": "harness", "runIdentity": "gh-1-1", "region": "eu-west-1", "bucket": "bucket"}, aws=aws)
            target = Path(directory) / "artifact"
            with self.assertRaises(ValueError):
                node.fetch({"key": "input", "size": 7, "sha256": "ab" * 32}, target)
            self.assertFalse(target.exists())

    def test_supervisor_redacts_process_log_and_never_publishes_private_files(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            prefix = "runs/gh-1-1/inputs/harness/generated/"
            aws.objects["bucket", prefix + "runtime-settings.json"] = b'{"provider":{"username":"private-user","password":"private-password"}}'
            for name in ("run-config.json", "effective-configuration.json"):
                aws.objects["bucket", prefix + name] = b"{}"
            node = Node({"source": "harness", "runIdentity": "gh-1-1", "region": "eu-west-1", "bucket": "bucket",
                         "execution": {}, "sampleIntervalSeconds": 1, "sampleBudget": 30,
                         "jvmArguments": [], "timeoutSeconds": 10}, root=directory, install=directory, aws=aws)
            node.root.mkdir(parents=True)
            write(node.root / "runtime.json", {"java": "java"})
            with patch("node.CapturedProcess") as captured:
                captured.return_value.process.poll.return_value = 0
                captured.return_value.process.pid = 123
                captured.return_value.log.return_value = "diagnostic private-user private-password"
                self.assertEqual(node.supervise(), 0)
            published = aws.objects["bucket", "runs/gh-1-1/outputs/harness/process.log"].decode()
            self.assertEqual(published, "diagnostic [REDACTED] [REDACTED]")
            self.assertFalse(any(key.endswith("runtime-settings.json") for key in aws.uploads))

    def test_archive_symlink_cannot_escape_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "archive.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                link = tarfile.TarInfo("escape")
                link.type = tarfile.SYMTYPE
                link.linkname = "../outside"
                bundle.addfile(link)
            with self.assertRaises(ValueError):
                extract(archive, Path(directory) / "extracted", 100)

    def test_jvm_environment_excludes_inherited_aws_and_application_settings(self):
        with patch.dict("os.environ", {"AWS_ACCESS_KEY_ID": "secret", "QUARKUS_HTTP_PORT": "1", "JAVA_TOOL_OPTIONS": "injected", "PATH": "tools"}):
            environment = child_environment()
        self.assertNotIn("AWS_ACCESS_KEY_ID", environment)
        self.assertNotIn("QUARKUS_HTTP_PORT", environment)
        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)
        self.assertEqual(environment["PATH"], "tools")

    def test_tar_traversal_and_size_bombs_are_rejected(self):
        for name, size, maximum in (("../escape", 1, 100), ("large", 100, 10)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                archive = Path(directory) / "archive.tar.gz"
                with tarfile.open(archive, "w:gz") as bundle:
                    item = tarfile.TarInfo(name)
                    item.size = size
                    bundle.addfile(item, io.BytesIO(b"x" * size))
                with self.assertRaises(ValueError):
                    extract(archive, Path(directory) / "extracted", maximum)
                self.assertFalse((Path(directory) / "escape").exists())

    def test_valid_regular_archive_extracts(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "archive.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                item = tarfile.TarInfo("quarkus-app/quarkus-run.jar")
                item.size = 3
                bundle.addfile(item, io.BytesIO(b"jar"))
            destination = Path(directory) / "extracted"
            extract(archive, destination, 100)
            self.assertEqual((destination / "quarkus-app/quarkus-run.jar").read_bytes(), b"jar")


if __name__ == "__main__":
    unittest.main()
