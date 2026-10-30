"""Opt-in real AWS-node lifecycle rehearsal with local artifact/secret transport."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time

PERFORMANCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PERFORMANCE / "deployment"))
import deploy
from aws_cli import AwsError
from node import Node, digest, read, write


class LocalTransport:
    def __init__(self, root):
        self.root = root

    def put(self, bucket, key, path, immutable=False):
        target = self.root / bucket / key
        target.parent.mkdir(parents=True, exist_ok=True)
        if immutable and target.exists():
            raise ValueError("Immutable fixture object exists")
        temporary = target.with_suffix(".tmp")
        shutil.copyfile(path, temporary)
        temporary.replace(target)

    def get(self, bucket, key, path):
        target = self.root / bucket / key
        if not target.exists():
            raise AwsError("Fixture object not published", code="NoSuchKey")
        shutil.copyfile(target, path)

    def call(self, service, operation, **parameters):
        assert (service, operation) == ("secretsmanager", "get-secret-value")
        return {"SecretString": json.dumps({"username": "kannel_perf", "password": Path("/tls/password").read_text().strip()})}


def wait(predicate, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError("AWS-node fixture deadline exceeded")


def main():
    os.umask(0o077)
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        transport = LocalTransport(root / "objects")
        jre = root / "java.tar.gz"
        with tarfile.open(jre, "w:gz", dereference=True) as archive:
            archive.add(os.environ["JAVA_HOME"], arcname="java-runtime")
        identity, bucket = "gh-12345-1", "fixture"
        preset = read(PERFORMANCE / "presets/aws-private-jvm-v1.json")
        preset["workload"].update(ingressMode="http", offeredMessagesPerSecond=5, warmupSeconds=1, measurementSeconds=3,
                                   drainTimeoutSeconds=5, providerDlrDelayMillis=100)
        preset["measurement"].update(readinessTimeoutSeconds=45, sampleBudgetPerSource=120)
        artifacts = {}
        for name, path in {"application": Path("/export/kannel.tar.gz"), "harness": Path("/opt/probe/harness.jar"),
                           "harnessJre": jre, "rdsCa": Path("/tls/ca.pem"),
                           "sampler": PERFORMANCE / "resources/sample_process.py"}.items():
            key = "runs/" + identity + "/inputs/common/" + name
            transport.put(bucket, key, path)
            artifacts[name] = {"key": key, "sha256": digest(path), "size": path.stat().st_size}
        staged = {"benchmarkTarget": "kannel", "kannelBuild": read("/export/build-metadata.json"), "preset": preset,
                  "runIdentity": identity, "region": "eu-west-1", "bucket": bucket,
                  "provenance": {"branch": "local-aws-node-rehearsal", "applicationCommit": None,
                                 "harnessRevision": "ab" * 20, "workflowRunUrl": None}, "artifacts": artifacts}
        result = {"SendiumInstanceId": "gateway", "HarnessInstanceId": "harness", "SendiumPrivateAddress": "127.0.0.1",
                  "HarnessPrivateAddress": "127.0.0.1", "DatabaseIdentifier": "local-fixture", "DatabaseAddress": "db",
                  "DatabasePort": "5432", "DatabaseName": "kannel_benchmark", "DatabaseCredentialsArn": "fixture-secret",
                  "PrimaryAvailabilityZone": "fixture", "resolvedDatabase": {"EngineVersion": "17.10"}}
        architecture = {"aarch64": "arm64", "x86_64": "x86_64"}[staged["kannelBuild"]["architecture"]]
        instances = {name: {"InstanceId": name, "InstanceType": "local-container", "Architecture": architecture,
                     "Placement": {"AvailabilityZone": "fixture"}, "ImageId": "local-fixture", "RootDeviceName": "/fixture",
                     "BlockDeviceMappings": [], "resolvedRootVolume": {}, "burstable": False} for name in ("gateway", "harness")}
        documents = deploy.documents(staged, result, instances, 90)
        context = documents["harness"]["execution"]["context"]
        context["infrastructure"].update(region="local", preset="local-aws-node-rehearsal")
        context["infrastructure"]["database"]["resourceMetricsRequired"] = False
        for source in ("kannel", "harness"):
            context["infrastructure"][source]["execution"] = "container-aws-node-rehearsal"
        gateway, harness = [Node(documents[source], root=root / "nodes", install=root / "install", aws=transport)
                            for source in ("sendium", "harness")]
        gateway.install_files()
        harness.install_files()
        gateway.configure()
        failures = []
        def run(node):
            try:
                node.supervise()
            except BaseException as error:
                failures.append(type(error).__name__)
        threads = []
        try:
            thread = threading.Thread(target=run, args=(harness,))
            thread.start()
            threads.append(thread)
            wait(lambda: (harness.root / "node-status.json").exists() and read(harness.root / "node-status.json")["phase"] == "listening")
            thread = threading.Thread(target=run, args=(gateway,))
            thread.start()
            threads.append(thread)
            threads[0].join(timeout=60)
            assert not threads[0].is_alive() and not failures, failures
            report = read(harness.root / "report/results.json")
            counts = report["overall"]["counts"]
            assert counts["accepted"] == counts["providerResponses"] == counts["receiptsForwarded"] == 15, counts
            assert report["configuration"]["infrastructure"]["database"]["readiness"]["tlsVerified"]
            assert len(report["configuration"]["infrastructure"]["kannel"]["processes"]) == 2
        finally:
            gateway.stopping = harness.stopping = True
            for thread in threads:
                thread.join(timeout=90)
            assert all(not thread.is_alive() for thread in threads), "Node did not stop"
            report_path = harness.root / "report/results.json"
            if report_path.is_file():
                print("Fixture validity:", json.dumps(read(report_path)["validity"]), flush=True)
            else:
                log = harness.root / "process.log"
                if log.is_file():
                    print(log.read_text()[-8192:], flush=True)
            if failures:
                print("Fixture node failures:", failures, flush=True)
        assert not failures, failures
        output = root / "retrieved"
        retrieval = deploy.retrieve(transport, staged, output)
        assert not retrieval["issues"], retrieval
        samples = [json.loads(line) for line in (output / "sendium/kannel-resources.jsonl").read_text().splitlines()]
        assert len(samples) > 2 and all(len(sample["processes"]) == 2 for sample in samples)
        secrets = read(gateway.root / "generated/kannel/redactions.json")
        for path in output.rglob("*"):
            if path.is_file():
                assert not any(value in path.read_text() for value in secrets), path
        finalized = root / "finalized"
        subprocess.run([harness.java(), "-jar", str(harness.root / "harness"), "finalize-report",
                        str(output / "harness/results.json"), str(output / "resource-inputs.json"), str(finalized)],
                       check=True, timeout=30)
        assert read(finalized / "results.json")["validity"]["status"] == "valid"
        shutil.copytree(finalized, output / "finalized")
        if os.environ.get("KANNEL_NODE_EVIDENCE"):
            shutil.copytree(output, os.environ["KANNEL_NODE_EVIDENCE"])
        print("PASS AWS-node lifecycle: install/configure/TLS/schema/readiness handoff, 15 measured messages/DLRs, group sampling, shutdown/retrieval/redaction")


if __name__ == "__main__":
    main()
