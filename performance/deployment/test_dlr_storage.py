"""Sendium-only file/memory DLR propagation and database-free node configuration."""

import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import Mock, patch

import deploy
from node import Node, read, write
from test_deployment import MemoryAws, staged, deployment_outputs
from test_native_deployment import native_state


def configured(directory, **document):
    node = Node({"source": "sendium", "runIdentity": "gh-12345-1", "region": "local", "bucket": "bucket", **document},
                root=directory, install=directory, aws=object())
    for location in ("sendium", "harness", "public"):
        (node.root / "generated" / location).mkdir(parents=True)
    write(node.root / "generated/sendium/environment.json", {"SENDIUM_DLR_POSTGRESQL_PASSWORD": "private"})
    write(node.root / "generated/harness/effective-configuration.json", {"applicationProperties": {}, "dlrStorage": "postgresql"})
    node.configure_storage()
    return node


def documents(runtime, storage):
    state = native_state() if runtime == "native" else staged()
    state["preset"]["runtime"]["dlrStorage"] = storage
    outputs = deployment_outputs()
    if storage in ("file", "memory"):
        outputs = {key: value for key, value in outputs.items() if not key.startswith("Database") and key != "resolvedDatabase"}
    instances = {outputs[source.title() + "InstanceId"]: {
        "InstanceId": outputs[source.title() + "InstanceId"], "InstanceType": "t4g.small", "Architecture": "arm64",
        "Placement": {"AvailabilityZone": "eu-west-1a"}, "ImageId": "ami-test", "RootDeviceName": "/dev/xvda",
        "BlockDeviceMappings": [], "resolvedRootVolume": {}, "burstable": True} for source in ("sendium", "harness")}
    return deploy.documents(state, outputs, instances, 60)


class DlrStorageTests(unittest.TestCase):
    def test_memory_dlr_needs_no_database_or_dlr_directory(self):
        for runtime in ("jvm", "native"):
            for profile in ("memory/memory/memory", "file/file/file"):
                with self.subTest(runtime=runtime, profile=profile), tempfile.TemporaryDirectory() as directory:
                    generated = documents(runtime, "memory")
                    self.assertNotIn("database", generated["sendium"]["deployment"])
                    self.assertNotIn("databaseSecretArn", generated["sendium"])
                    self.assertEqual(generated["harness"]["execution"]["context"]["application"]["dlrStorage"], "memory")
                    self.assertEqual(generated["harness"]["execution"]["context"]["infrastructure"]["database"],
                                     {"provisioned": False, "resourceMetricsRequired": False})
                    node = configured(directory, messageStorageProfile=profile, dlrStorage="memory")
                    environment = read(node.root / "generated/sendium/environment.json")
                    self.assertEqual(environment["SENDIUM_DLR_STORAGE_BACKEND"], "memory")
                    self.assertNotIn("SENDIUM_DLR_STORAGE_FILE_PATH", environment)
                    self.assertFalse((node.root / "dlr").exists())
                    for location in ("public", "harness"):
                        effective = read(node.root / ("generated/" + location + "/effective-configuration.json"))
                        self.assertEqual(effective["dlrStorage"], "memory")
                        self.assertEqual(effective["applicationProperties"]["sendium.dlr.storage.backend"], "memory")

    def test_file_dlr_deployment_records_no_database(self):
        state = staged()
        state["preset"]["runtime"]["dlrStorage"] = "file"
        result = {key: value for key, value in deployment_outputs().items()
                  if not key.startswith("Database") and key != "resolvedDatabase"}
        expiry = (datetime.now(timezone.utc) + timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:%SZ")
        result.update(RunIdentity=state["runIdentity"], ArtifactRunPrefix="runs/" + state["runIdentity"] + "/",
                      ExpiresAt=expiry, PrimarySubnetId="subnet-12345678")
        tags = [{"Key": key, "Value": value} for key, value in {
            "sendium:managed-by": "performance-tests", "sendium:repository": state["repository"],
            "sendium:run-identity": state["runIdentity"], "sendium:expires-at": expiry}.items()]
        role = "arn:aws:iam::123456789012:role/sendium-perf-cloudformation"
        boot = {"StackStatus": "CREATE_COMPLETE", "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in {
            "ArtifactBucketName": state["bucket"], "RunStackPrefix": "sendium-perf-run-", "RepositoryTag": state["repository"],
            "ExpiryHandlerInstalled": "true", "CloudFormationExecutionRoleArn": role, "RunResourceTtlMinutes": "240"}.items()]}
        root = {"StackStatus": "CREATE_COMPLETE", "RoleARN": role, "Tags": tags,
                "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in result.items()]}
        instances = [{"InstanceId": result[source + "InstanceId"], "State": {"Name": "running"},
                      "PrivateIpAddress": result[source + "PrivateAddress"], "SubnetId": result["PrimarySubnetId"],
                      "InstanceType": "t4g.small", "Architecture": "arm64", "ImageId": "ami-test",
                      "Placement": {"AvailabilityZone": "eu-west-1a"},
                      "Tags": tags + [{"Key": "sendium:role", "Value": source.lower()}],
                      "RootDeviceName": "/dev/xvda", "BlockDeviceMappings": [
                          {"DeviceName": "/dev/xvda", "Ebs": {"DeleteOnTermination": True, "VolumeId": "vol-12345678"}}],
                      "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 1}} for source in ("Sendium", "Harness")]
        responses = {"describe-instances": {"Reservations": [{"Instances": instances}]},
                     "describe-instance-types": {"InstanceTypes": [
                         {"InstanceType": "t4g.small", "MemoryInfo": {"SizeInMiB": 2048}, "BurstablePerformanceSupported": True}]},
                     "describe-volumes": {"Volumes": [
                         {"VolumeId": "vol-12345678", "Size": 8, "VolumeType": "gp3", "Encrypted": True}]}}
        def call(service, operation, **parameters):
            self.assertNotEqual(service, "rds")
            return responses[operation]
        with patch.object(deploy, "verify_expiry"), patch.object(deploy, "stack", side_effect=[boot, root]):
            inspected, actual, timeout = deploy.inspect_run(Mock(call=call), state)
        self.assertNotIn("resolvedDatabase", inspected)
        generated = deploy.documents(state, inspected, actual, timeout)
        for runtime in ("jvm", "native"):
            with self.subTest(runtime=runtime):
                value = documents(runtime, "file")
                self.assertEqual(value["harness"]["execution"]["context"]["infrastructure"]["database"],
                                 {"provisioned": False, "resourceMetricsRequired": False})
                self.assertNotIn("database", value["sendium"]["deployment"])
                self.assertNotIn("databaseSecretArn", value["sendium"])
                self.assertNotIn("jdbc:", str(value))
        self.assertNotIn("database", generated["sendium"]["deployment"])

    def test_non_postgresql_dlr_node_reads_no_database_secret(self):
        for runtime, storage in ((runtime, storage) for runtime in ("jvm", "native") for storage in ("file", "memory")):
            with self.subTest(runtime=runtime, storage=storage), tempfile.TemporaryDirectory() as directory:
                document = documents(runtime, storage)["sendium"]
                aws = MemoryAws()
                node = Node(document, root=directory, install=directory, aws=aws)
                node.root.mkdir(parents=True)
                write(node.root / "runtime.json", {"java": "java"})
                def generate(command, **kwargs):
                    self.assertEqual(read(Path(command[5])), document["deployment"])
                    self.assertNotIn("database", read(Path(command[5])))
                    for location in ("sendium", "harness", "public"):
                        (node.root / "generated" / location).mkdir(parents=True)
                    write(node.root / "generated/sendium/environment.json", {})
                    write(node.root / "generated/harness/effective-configuration.json", {"applicationProperties": {}})
                    for name in ("runtime-settings.json", "run-config.json"):
                        write(node.root / "generated/harness" / name, {})
                    return Mock(returncode=0)
                with patch.object(aws, "call", side_effect=AssertionError("No AWS API call is allowed")), \
                     patch("node.subprocess.run", side_effect=generate):
                    node.configure()
                environment = read(node.root / "generated/sendium/environment.json")
                self.assertEqual(environment["SENDIUM_DLR_STORAGE_BACKEND"], storage)
                self.assertFalse(any(key.startswith("SENDIUM_DLR_POSTGRESQL_") for key in environment))
                self.assertTrue(any(key.endswith("effective-configuration.json") for key in aws.uploads))

    def test_file_dlr_configures_sendium_environment(self):
        for profile in ("memory/memory/memory", "file/memory/memory", "file/file/file"):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as directory:
                node = configured(directory, messageStorageProfile=profile, dlrStorage="file")
                environment = read(node.root / "generated/sendium/environment.json")
                self.assertEqual(environment["SENDIUM_DLR_STORAGE_BACKEND"], "file")
                self.assertEqual(environment["SENDIUM_DLR_STORAGE_FILE_PATH"], str(node.root / "dlr"))
                self.assertTrue((node.root / "dlr").is_dir())
                if profile.startswith("file/"):
                    # Siblings under the node root; Sendium rejects nested or shared directories.
                    self.assertEqual(environment["SENDIUM_MESSAGE_PENDING_PATH"], str(node.root / "pending"))

    def test_file_dlr_is_recorded_in_effective_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            node = configured(directory, dlrStorage="file")
            for location in ("public", "harness"):
                effective = read(node.root / ("generated/" + location + "/effective-configuration.json"))
                self.assertEqual(effective["dlrStorage"], "file")
                self.assertEqual(effective["applicationProperties"]["sendium.dlr.storage.backend"], "file")
                self.assertNotIn("private", str(effective))
        for runtime in ("jvm", "native"):
            with self.subTest(runtime=runtime):
                result = documents(runtime, "file")
                self.assertEqual(result["sendium"]["dlrStorage"], "file")
                self.assertNotIn("dlrStorage", result["harness"])
                self.assertEqual(result["harness"]["execution"]["context"]["application"]["dlrStorage"], "file")

    def test_postgresql_leaves_node_configuration_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            node = configured(directory)
            environment = read(node.root / "generated/sendium/environment.json")
            self.assertFalse([key for key in environment if key.startswith("SENDIUM_DLR_STORAGE_")])
            self.assertEqual(environment["SENDIUM_DLR_POSTGRESQL_PASSWORD"], "private")
            self.assertFalse((node.root / "dlr").exists())
            self.assertEqual(read(node.root / "generated/harness/effective-configuration.json")["dlrStorage"], "postgresql")
        self.assertEqual(documents("jvm", "postgresql")["harness"]["execution"]["context"]["application"]["dlrStorage"], "postgresql")

    def test_invalid_or_non_sendium_file_dlr_settings_fail_before_aws_access(self):
        document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "local", "bucket": "bucket"}
        for changes in ({"dlrStorage": "disk"}, {"dlrStorage": "file", "source": "harness"},
                        {"dlrStorage": "file", "benchmarkTarget": "kannel"},
                        {"dlrStorage": "memory", "source": "harness"}, {"dlrStorage": "memory", "benchmarkTarget": "kannel"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                Node({**document, **changes}, aws=object())


if __name__ == "__main__":
    unittest.main()
