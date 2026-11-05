"""Native deployment identity, launch and format boundaries without executing fixture binaries."""

from copy import deepcopy
from pathlib import Path
import os
import struct
import tempfile
import unittest
from unittest.mock import patch

import deploy
from node import Node
from test_deployment import staged, deployment_outputs


def native_state(architecture="arm64"):
    state = staged()
    state["preset"]["runtime"]["applicationRuntime"] = "native"
    state["nativeBuild"] = {"schemaVersion": 1, "architecture": architecture,
        "builderImage": state["preset"]["runtime"]["native"]["builderImage"],
        "compilerVersion": "native-image 25.0.4.1 Mandrel Distribution", "buildArguments": ["-march=compatibility"]}
    state["resolution"] = {"nodes": {"sendium": {"architecture": architecture}}}
    return state


class NativeDeploymentTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "posix", "Executable permissions require a Linux filesystem")
    def test_native_installation_rejects_non_executable_symlink_and_wrong_elf(self):
        document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "eu-west-1", "bucket": "bucket",
                    "applicationRuntime": "native", "architecture": "x86_64"}
        with tempfile.TemporaryDirectory() as directory, patch("node.platform.machine", return_value="x86_64"):
            node = Node(document, root=directory, install=directory, aws=object())
            binary = node.install / "application/native/application"
            binary.parent.mkdir(parents=True)
            header = bytearray(64)
            header[:7] = b"\x7fELF\x02\x01\x01"
            struct.pack_into("<HH", header, 16, 2, 62)
            binary.write_bytes(header)
            binary.chmod(0o700)
            self.assertEqual(node.native_application(), str(binary))
            binary.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "executable native/application"):
                node.native_application()
            binary.chmod(0o700)
            struct.pack_into("<HH", header, 16, 2, 183)
            binary.write_bytes(header)
            with self.assertRaisesRegex(ValueError, "ELF architecture/type mismatch"):
                node.native_application()
            binary.rename(binary.with_name("other"))
            binary.symlink_to("other")
            with self.assertRaisesRegex(ValueError, "executable native/application"):
                node.native_application()

    def test_native_plan_binds_compiler_identity_to_resolved_architecture(self):
        state = native_state()
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "artifact"
            artifact.write_bytes(b"verified transport data")
            state["artifacts"] = {name: {"path": str(artifact), "sha256": deploy.sha256(artifact)}
                                  for name in ("application", "harness", "sendiumJre", "harnessJre", "rdsCa")}
            self.assertEqual(deploy.validate_plan(state, state["preset"])["runtime"]["applicationRuntime"], "native")
            for field, value in (("architecture", "x86_64"), ("builderImage", "substituted"), ("compilerVersion", "native-image 21")):
                changed = deepcopy(state)
                changed["nativeBuild"][field] = value
                with self.assertRaises(ValueError):
                    deploy.validate_plan(changed, changed["preset"])

    def test_documents_keep_harness_jvm_and_report_native_identity(self):
        state = native_state()
        result = deployment_outputs()
        instances = {result[source.title() + "InstanceId"]: {"InstanceId": result[source.title() + "InstanceId"],
            "InstanceType": "t4g.small", "Architecture": "arm64", "Placement": {"AvailabilityZone": "eu-west-1a"},
            "ImageId": "ami-12345678", "RootDeviceName": "/dev/xvda", "BlockDeviceMappings": [],
            "resolvedRootVolume": {}, "burstable": True} for source in ("sendium", "harness")}
        documents = deploy.documents(state, result, instances, 120)
        self.assertEqual(documents["sendium"]["jvmArguments"], [])
        self.assertEqual(documents["sendium"]["nativeArguments"], ["-Xms512m", "-Xmx512m"])
        context = documents["harness"]["execution"]["context"]
        self.assertEqual(context["application"]["nativeBuild"], state["nativeBuild"])
        self.assertEqual(context["infrastructure"]["sendium"]["execution"], "ec2-native")
        self.assertEqual(context["infrastructure"]["harness"]["execution"], "ec2-jvm")
        self.assertTrue(context["infrastructure"]["database"]["resourceMetricsRequired"])
        self.assertNotIn("nativeArguments", documents["harness"])
        state["nativeBuild"]["architecture"] = "x86_64"
        with self.assertRaises(ValueError):
            deploy.documents(state, result, instances, 120)

    def test_direct_native_command_does_not_use_java_or_jvm_gc_options(self):
        document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "eu-west-1", "bucket": "bucket",
                    "applicationRuntime": "native", "nativeArguments": ["-Xms512m", "-Xmx512m"]}
        node = Node(document, aws=object())
        with patch.object(node, "native_application", return_value="/checked/native/application"), patch.object(node, "java", side_effect=AssertionError("Java launch")):
            self.assertEqual(node.application_command(), ["/checked/native/application", "-Xms512m", "-Xmx512m"])
        with self.assertRaisesRegex(ValueError, "harness requires JVM"):
            Node({**document, "source": "harness"}, aws=object())

    def test_native_launch_rejects_wrong_node_architecture_and_missing_binary(self):
        document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "eu-west-1", "bucket": "bucket",
                    "applicationRuntime": "native", "architecture": "arm64"}
        node = Node(document, aws=object())
        with patch("node.platform.machine", return_value="x86_64"):
            with self.assertRaisesRegex(ValueError, "runtime node"):
                node.native_application()
        with patch("node.platform.machine", return_value="aarch64"):
            with self.assertRaisesRegex(ValueError, "executable native/application"):
                node.native_application()


if __name__ == "__main__":
    unittest.main()
