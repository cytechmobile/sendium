"""Target isolation, artifact identity and AWS readiness handoff checks."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import deploy
from node import Node, write
from test_deployment import MemoryAws, staged, deployment_outputs


def kannel_state():
    state = staged()
    state.update(benchmarkTarget="kannel", kannelBuild={
        "schemaVersion": 1, "role": "gateway", "architecture": "aarch64", "postgresqlSupport": True,
        "source": {"version": "1.4.5", "archiveSha256": "f279606ea8317fd2c8fa6b0f591c084fdfa954612f1e7c9cb667a5f6b77387f3",
                   "patchArchiveSha256": "3d8d54e272699a5226368738ee0246c8873d152a63220f978ac149285895f5be",
                   "patches": ["Update-bison-source-for-newer-version.patch", "Fix-multiple-conflicting-defs-of-dispatch_to_wdp-wsp-push.patch"]},
        "binarySha256": {"bearerbox": "ab" * 32, "smsbox": "cd" * 32}})
    state["provenance"]["applicationCommit"] = None
    state["artifacts"].pop("sendiumJre")
    state["preset"]["workload"]["ingressMode"] = "http"
    return state


def instances(result):
    return {result[source.title() + "InstanceId"]: {"InstanceId": result[source.title() + "InstanceId"],
            "InstanceType": "t4g.small", "Architecture": "arm64", "Placement": {"AvailabilityZone": "eu-west-1a"},
            "ImageId": "ami-12345678", "RootDeviceName": "/dev/xvda", "BlockDeviceMappings": [],
            "resolvedRootVolume": {"VolumeType": "gp3", "Size": 8, "Encrypted": True}, "burstable": True}
            for source in ("sendium", "harness")}


class KannelDeploymentTests(unittest.TestCase):
    def test_staging_keeps_archive_identity_and_uses_existing_private_transport(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            artifact = directory / "artifact"
            artifact.write_bytes(b"data-only-controller-input")
            state, aws = kannel_state(), MemoryAws()
            state["artifacts"] = {name: {"path": str(artifact), "sha256": deploy.sha256(artifact)}
                                  for name in ("application", "harness", "harnessJre", "rdsCa")}
            boot = {"StackStatus": "CREATE_COMPLETE", "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in
                    {"ArtifactBucketName": state["bucket"], "RunStackPrefix": "sendium-perf-run-", "RepositoryTag": state["repository"]}.items()]}
            with patch.object(deploy, "stack", return_value=boot):
                result = deploy.stage(aws, state, state["preset"], directory)
            self.assertEqual(result["benchmarkTarget"], "kannel")
            self.assertEqual(result["kannelBuild"], state["kannelBuild"])
            self.assertIsNone(result["provenance"]["applicationCommit"])
            self.assertNotIn("sendiumJre", result["artifacts"])
            self.assertTrue(result["artifacts"]["application"]["key"].endswith("inputs/sendium/application"))

    def test_kannel_documents_use_c_runtime_and_keep_credentials_on_gateway(self):
        state, result = kannel_state(), deployment_outputs()
        docs = deploy.documents(state, result, instances(result), 1000)
        gateway, harness = docs["sendium"], docs["harness"]
        self.assertNotIn("jre", gateway["artifacts"])
        self.assertNotIn("harness", gateway["artifacts"])
        self.assertEqual(gateway["jvmArguments"], [])
        self.assertEqual(gateway["deployment"]["database"]["host"], result["DatabaseAddress"])
        self.assertNotIn("jdbc:", json.dumps(docs))
        self.assertNotIn(result["DatabaseCredentialsArn"], json.dumps(harness))
        context = harness["execution"]["context"]
        self.assertIsNone(context["provenance"]["applicationCommit"])
        self.assertEqual(context["application"]["runtime"], "kannel")
        self.assertNotIn("sendium", context["infrastructure"])
        self.assertEqual(context["infrastructure"]["kannel"]["execution"], "ec2-kannel")
        self.assertTrue(context["infrastructure"]["database"]["provisioned"])
        self.assertEqual(context["infrastructure"]["database"]["engine"], "postgresql")
        self.assertTrue(context["infrastructure"]["database"]["resourceMetricsRequired"])

    def test_architecture_mismatch_is_rejected_before_node_documents(self):
        state, result = kannel_state(), deployment_outputs()
        state["kannelBuild"]["architecture"] = "x86_64"
        with self.assertRaisesRegex(deploy.DeploymentError, "architecture differs"):
            deploy.documents(state, result, instances(result), 1000)

    def test_ingress_and_archive_provenance_are_checked_before_aws(self):
        for change in (lambda state: state["preset"]["workload"].update(ingressMode="smpp"),
                       lambda state: state["provenance"].update(applicationCommit="ab" * 20),
                       lambda state: state["kannelBuild"].update(postgresqlSupport=False),
                       lambda state: state["kannelBuild"]["source"].update(archiveSha256="ab" * 32)):
            state, aws = kannel_state(), MemoryAws()
            change(state)
            with self.assertRaises(deploy.DeploymentError):
                deploy.stage(aws, state, state["preset"], Path("unused"))
            self.assertEqual(aws.calls, [])
            self.assertEqual(aws.uploads, [])

    def test_retrieval_preserves_group_samples_with_gateway_label(self):
        with tempfile.TemporaryDirectory() as directory:
            state, aws = kannel_state(), MemoryAws()
            aws.objects[state["bucket"], "runs/gh-12345-1/outputs/sendium/kannel-resources.jsonl"] = b"{}\n"
            deploy.retrieve(aws, state, Path(directory) / "retrieved")
            settings = deploy.read(Path(directory) / "retrieved/resource-inputs.json")
            self.assertEqual(settings["gatewaySamples"], "sendium/kannel-resources.jsonl")
            self.assertNotIn("sendiumSamples", settings)

    def test_gateway_readiness_is_published_only_after_supervisor_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            node = Node({"source": "sendium", "benchmarkTarget": "kannel", "runIdentity": "gh-1-1", "region": "eu-west-1",
                         "bucket": "bucket", "timeoutSeconds": 30}, root=directory, install=directory, aws=aws)
            root = node.root / "generated/kannel"
            root.mkdir(parents=True)
            ready = {"schemaVersion": 1, "runIdentity": node.identity, "processes": [], "database": {"tlsVerified": True}}
            write(root / "gateway-ready.json", ready)
            write(root / "redactions.json", ["private-database-password"])
            (root / "kannel-resources.jsonl").write_text("{}\n")
            captured = Mock()
            captured.process.pid = 123
            captured.process.returncode = 0
            captured.process.poll.side_effect = [None, 0, 0]
            captured.log.return_value = "private-database-password"
            runtime = Mock(command=Mock(return_value=["supervisor"]), environment=Mock(return_value={}))
            with patch.object(node, "kannel", return_value=runtime), patch("node.CapturedProcess", return_value=captured), patch("node.time.sleep"):
                self.assertEqual(node.supervise(), 0)
            prefix = "runs/gh-1-1/"
            self.assertEqual(json.loads(aws.objects["bucket", prefix + "inputs/harness/generated/gateway-ready.json"]), ready)
            self.assertEqual(aws.objects["bucket", prefix + "outputs/sendium/process.log"], b"[REDACTED]")
            self.assertIn(prefix + "outputs/sendium/kannel-resources.jsonl", aws.uploads)

    def test_supervisor_shutdown_failure_is_not_reported_as_clean_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = MemoryAws()
            node = Node({"source": "sendium", "benchmarkTarget": "kannel", "runIdentity": "gh-1-1", "region": "eu-west-1",
                         "bucket": "bucket", "timeoutSeconds": 30}, root=directory, install=directory, aws=aws)
            node.root.mkdir(parents=True)
            node.stopping = True
            captured = Mock()
            captured.process.pid = 123
            captured.process.poll.return_value = None
            captured.process.returncode = 1
            captured.log.return_value = "Sampler shutdown failed"
            runtime = Mock(command=Mock(return_value=["supervisor"]), environment=Mock(return_value={}))
            with patch.object(node, "kannel", return_value=runtime), patch("node.CapturedProcess", return_value=captured):
                self.assertEqual(node.supervise(), 1)
            status = json.loads(aws.objects["bucket", "runs/gh-1-1/outputs/sendium/node-status.json"])
            self.assertEqual(status["exitCode"], 1)


if __name__ == "__main__":
    unittest.main()
