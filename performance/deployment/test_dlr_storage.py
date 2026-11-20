"""Sendium-only file DLR storage propagation and run-local DLR directory configuration."""

import tempfile
import unittest

import deploy
from node import Node, read, write
from test_deployment import staged, deployment_outputs
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
    instances = {outputs[source.title() + "InstanceId"]: {
        "InstanceId": outputs[source.title() + "InstanceId"], "InstanceType": "t4g.small", "Architecture": "arm64",
        "Placement": {"AvailabilityZone": "eu-west-1a"}, "ImageId": "ami-test", "RootDeviceName": "/dev/xvda",
        "BlockDeviceMappings": [], "resolvedRootVolume": {}, "burstable": True} for source in ("sendium", "harness")}
    return deploy.documents(state, outputs, instances, 60)


class DlrStorageTests(unittest.TestCase):
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
                        {"dlrStorage": "file", "benchmarkTarget": "kannel"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                Node({**document, **changes}, aws=object())


if __name__ == "__main__":
    unittest.main()
