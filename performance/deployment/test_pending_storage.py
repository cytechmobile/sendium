"""Sendium-only pending profile propagation and private writable run-local spool configuration."""

from pathlib import Path
import tempfile
import unittest

import deploy
from node import Node, read, write
from test_deployment import staged, deployment_outputs
from test_native_deployment import native_state


class PendingStorageTests(unittest.TestCase):
    def test_profiles_reach_gateway_documents_and_report_context_for_both_runtimes_and_ingresses(self):
        for runtime in ("jvm", "native"):
            for ingress in ("http", "smpp"):
                for backend in ("memory", "file"):
                    with self.subTest(runtime=runtime, ingress=ingress, backend=backend):
                        state = native_state() if runtime == "native" else staged()
                        state["preset"]["runtime"]["pendingBackend"] = backend
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
                        self.assertEqual(application["messageStorageProfile"], backend + "/memory/memory")

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
                        {"pendingBackend": "file", "benchmarkTarget": "kannel"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                Node({**document, **changes}, aws=object())
