"""Sendium-only pending profile propagation and private writable run-local spool configuration."""

from pathlib import Path
import tempfile
import unittest

import deploy
from node import Node, read, write
from test_deployment import staged, deployment_outputs
from test_native_deployment import native_state


class PendingStorageTests(unittest.TestCase):
    def test_full_profiles_reach_all_gateway_settings_and_effective_evidence(self):
        profiles = ("memory/memory/memory", "file/memory/memory", "file/file/memory", "file/memory/file", "file/file/file")
        for runtime in ("jvm", "native"):
            for profile in profiles:
                with self.subTest(runtime=runtime, profile=profile), tempfile.TemporaryDirectory() as directory:
                    document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "local", "bucket": "bucket",
                                "applicationRuntime": runtime, "messageStorageProfile": profile}
                    node = Node(document, root=directory, install=directory, aws=object())
                    for location in ("sendium", "harness", "public"):
                        (node.root / "generated" / location).mkdir(parents=True)
                    write(node.root / "generated/sendium/environment.json", {})
                    write(node.root / "generated/harness/effective-configuration.json", {"applicationProperties": {}})
                    node.configure_storage()
                    environment = read(node.root / "generated/sendium/environment.json")
                    self.assertEqual([environment[key] for key in ("SENDIUM_MESSAGE_PENDING_BACKEND",
                                     "SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND", "SENDIUM_MESSAGE_ROUTED_WORK_BACKEND")], profile.split("/"))
                    self.assertEqual(read(node.root / "generated/harness/effective-configuration.json")["messageStorageProfile"], profile)
                    self.assertEqual("SENDIUM_MESSAGE_PENDING_PATH" in environment, profile.startswith("file/"))

    def test_profiles_reach_gateway_documents_and_report_context_for_both_runtimes_and_ingresses(self):
        for runtime in ("jvm", "native"):
            for ingress in ("http", "smpp"):
                for profile in ("memory/memory/memory", "file/memory/memory", "file/file/memory", "file/memory/file", "file/file/file"):
                    backend = profile.split("/")[0]
                    with self.subTest(runtime=runtime, ingress=ingress, profile=profile):
                        state = native_state() if runtime == "native" else staged()
                        state["preset"]["runtime"]["pendingBackend"] = backend
                        state["preset"]["runtime"]["messageStorageProfile"] = profile
                        state["preset"]["workload"]["ingressMode"] = ingress
                        outputs = deployment_outputs()
                        instances = {outputs[source.title() + "InstanceId"]: {
                            "InstanceId": outputs[source.title() + "InstanceId"], "InstanceType": "t4g.small", "Architecture": "arm64",
                            "Placement": {"AvailabilityZone": "eu-west-1a"}, "ImageId": "ami-test", "RootDeviceName": "/dev/xvda",
                            "BlockDeviceMappings": [], "resolvedRootVolume": {}, "burstable": True} for source in ("sendium", "harness")}
                        documents = deploy.documents(state, outputs, instances, 60)
                        self.assertEqual(documents["sendium"]["pendingBackend"], backend)
                        self.assertNotIn("pendingBackend", documents["harness"])
                        application = documents["harness"]["execution"]["context"]["application"]
                        self.assertEqual(application["messageStorageProfile"], profile)
                        self.assertEqual(documents["sendium"]["messageStorageProfile"], profile)

    def test_node_configures_only_selected_backend_and_preserves_existing_spool_content(self):
        for backend in ("memory", "file"):
            with self.subTest(backend=backend), tempfile.TemporaryDirectory() as directory:
                document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "local", "bucket": "bucket", "pendingBackend": backend}
                node = Node(document, root=directory, install=directory, aws=object())
                for location in ("sendium", "harness", "public"):
                    (node.root / "generated" / location).mkdir(parents=True)
                write(node.root / "generated/sendium/environment.json", {"SENDIUM_DLR_POSTGRESQL_PASSWORD": "private"})
                write(node.root / "generated/harness/effective-configuration.json", {"applicationProperties": {}})
                node.configure_storage()
                environment = read(node.root / "generated/sendium/environment.json")
                self.assertEqual(environment["SENDIUM_MESSAGE_PENDING_BACKEND"], backend)
                self.assertEqual(environment["SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND"], "memory")
                self.assertEqual(environment["SENDIUM_MESSAGE_ROUTED_WORK_BACKEND"], "memory")
                effective = read(node.root / "generated/harness/effective-configuration.json")
                self.assertEqual(effective["messageStorageProfile"], backend + "/memory/memory")
                self.assertNotIn("private", str(effective))
                if backend == "file":
                    spool = Path(environment["SENDIUM_MESSAGE_PENDING_PATH"])
                    self.assertEqual(spool, node.root / "pending")
                    (spool / "retained").write_text("accepted")
                    node.configure_storage()
                    self.assertEqual((spool / "retained").read_text(), "accepted")
                else:
                    self.assertNotIn("SENDIUM_MESSAGE_PENDING_PATH", environment)
                    self.assertFalse((node.root / "pending").exists())

    def test_invalid_or_non_sendium_file_settings_fail_before_aws_access(self):
        document = {"source": "sendium", "runIdentity": "gh-12345-1", "region": "local", "bucket": "bucket"}
        for changes in ({"pendingBackend": "disk"}, {"pendingBackend": "file", "source": "harness"},
                         {"pendingBackend": "file", "benchmarkTarget": "kannel"},
                         {"messageStorageProfile": "memory/file/file"},
                         {"messageStorageProfile": "file/file/file", "pendingBackend": "memory"},
                         {"messageStorageProfile": "file/file/file", "source": "harness"},
                         {"messageStorageProfile": "file/file/file", "benchmarkTarget": "kannel"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                Node({**document, **changes}, aws=object())
