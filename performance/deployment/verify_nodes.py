"""Opt-in real Linux/JVM/PostgreSQL verification using a local S3/Secrets transport."""

import json
import os
import platform
from pathlib import Path
import shutil
import tarfile
import tempfile
import threading
import time

from node import Node, digest, read, write


class LocalAws:
    def __init__(self, directory):
        self.directory = directory

    def put(self, bucket, key, path, immutable=False):
        target = self.directory / bucket / key
        target.parent.mkdir(parents=True, exist_ok=True)
        if immutable and target.exists():
            raise RuntimeError("Attempted input overwrite")
        temporary = target.with_suffix(target.suffix + ".tmp")
        shutil.copyfile(path, temporary)
        temporary.replace(target)

    def get(self, bucket, key, path):
        shutil.copyfile(self.directory / bucket / key, path)

    def call(self, service, operation, **parameters):
        assert (service, operation) == ("secretsmanager", "get-secret-value")
        return {"SecretString": json.dumps({"username": "postgres", "password": "node-test-password"})}


def wait(predicate, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError("Node fixture deadline exceeded")


def main():
    os.umask(0o077)
    workspace = Path("/workspace")
    harness = workspace / "performance/harness/target/sendium-performance-harness-0.5.2-SNAPSHOT.jar"
    native_archive = os.environ.get("SENDIUM_NATIVE_ARCHIVE")
    native_build = read(Path(native_archive).with_name("build-manifest.json"))["nativeBuild"] if native_archive else None
    evidence = Path(os.environ["SENDIUM_NODE_EVIDENCE"]) if os.environ.get("SENDIUM_NODE_EVIDENCE") else None
    application = workspace / "sendium-app/target/quarkus-app"
    assert harness.is_file() and (native_archive or application.is_dir()), "Build application and harness artifacts first"
    # Fixture archives exercise actual checksum/download/extraction/runtime discovery.
    with tempfile.TemporaryDirectory() as temporary:
        temporary = Path(temporary)
        aws = LocalAws(temporary / "objects")
        jre = temporary / "jre.tar.gz"
        with tarfile.open(jre, "w:gz", dereference=True) as bundle:
            bundle.add(os.environ["JAVA_HOME"], arcname="java-runtime")
        app = temporary / "application.tar.gz"
        if native_archive:
            shutil.copyfile(native_archive, app)
        else:
            with tarfile.open(app, "w:gz") as bundle:
                bundle.add(application, arcname="quarkus-app")
        ca = temporary / "ca.pem"
        ca.write_text("Unused local-only fixture CA\n")
        for attempt, (mode, dlr, run_application) in enumerate((("http", True, True), ("smpp", True, True), ("http", True, False)), 1):
            identity = "gh-12345-" + str(attempt)
            prefix = "runs/" + identity + "/"
            artifacts = {}
            for name, path in {"jre": jre, "harness": harness, "application": app, "rdsCa": ca,
                               "sampler": workspace / "performance/resources/sample_process.py"}.items():
                key = prefix + "inputs/common/" + name
                aws.put("bucket", key, path)
                artifacts[name] = {"key": key, "sha256": digest(path), "size": path.stat().st_size}
            workload = read(workspace / "performance/presets/aws-private-jvm-v1.json")["workload"]
            workload.update(ingressMode=mode, dlrEnabled=dlr, offeredMessagesPerSecond=2, warmupSeconds=1,
                            measurementSeconds=2, drainTimeoutSeconds=10, providerDlrDelayMillis=100)
            deployment = read(workspace / "performance/harness/src/test/resources/deployment.json")
            deployment.update(runIdentity=identity, sendiumHost="127.0.0.1", harnessHost="127.0.0.1")
            deployment["database"]["jdbcUrl"] = "jdbc:postgresql://" + os.environ["POSTGRES_HOST"] + ":5432/postgres"
            deployment["database"].pop("username")
            deployment["database"].pop("password")
            execution = {"schemaVersion": 1, "readinessTimeoutSeconds": 45 if run_application else 3, "retentionSeconds": 300, "intervalBudget": 256,
                "context": {"provenance": {"branch": "node-verification", "applicationCommit": "ab" * 20,
                    "harnessRevision": "cd" * 20, "workflowRunUrl": None, "runIdentity": identity,
                    "artifacts": [{"name": "harness", "sha256": digest(harness)}]},
                    "application": {"runtime": "native" if native_archive else "jvm", "jvmArguments": [],
                        **({"nativeArguments": ["-Xms128m", "-Xmx512m"], "nativeBuild": native_build} if native_archive else {}),
                        "connections": {}, "dlrStorage": "postgresql"},
                    "infrastructure": {"preset": "local-test", "region": "local", "sendium": {"architecture": native_build["architecture"]} if native_build else {}, "harness": {},
                                       "database": {"engine": "postgresql"}}, "diagnostics": []},
                "resources": {"schemaVersion": 1, "sendiumSamples": None, "harnessSamples": None, "cpuCredits": None, "sampleBudget": 120},
                "sampling": None}
            common = {"runIdentity": identity, "region": "local", "bucket": "bucket", "artifacts": artifacts,
                      "jvmArguments": ["-Xms128m", "-Xmx512m"], "sampleIntervalSeconds": 1, "sampleBudget": 120, "timeoutSeconds": 90}
            sendium = Node({**common, "source": "sendium", "deployment": deployment, "workload": workload,
                            **({"applicationRuntime": "native", "nativeArguments": ["-Xms128m", "-Xmx512m"],
                                "architecture": {"x86_64": "x86_64", "aarch64": "arm64"}[platform.machine()]}
                               if native_archive else {}),
                            "databaseSecretArn": "local-fixture"}, aws=aws)
            harness_node = Node({**common, "source": "harness", "artifacts": {key: value for key, value in artifacts.items() if key != "application"},
                                 "execution": execution}, aws=aws)
            sendium.install_files()
            harness_node.install_files()
            sendium.configure()
            threads = []
            def supervise(node):
                node.supervise()
            try:
                harness_thread = threading.Thread(target=supervise, args=(harness_node,))
                threads.append(harness_thread)
                harness_thread.start()
                wait(lambda: (harness_node.root / "node-status.json").exists()
                     and read(harness_node.root / "node-status.json")["phase"] == "listening")
                if run_application:
                    sendium_thread = threading.Thread(target=supervise, args=(sendium,))
                    threads.append(sendium_thread)
                    sendium_thread.start()
                harness_thread.join(timeout=90)
                assert not harness_thread.is_alive()
                report = read(harness_node.root / "report/results.json")
                if run_application:
                    assert report["validity"]["status"] != "failed", report["validity"]
                    counts = report["overall"]["counts"]
                    assert counts["accepted"] == 4 and counts["providerResponses"] == 4, counts
                    assert counts["receiptsForwarded"] == (4 if dlr else 0), counts
                else:
                    assert report["validity"]["status"] == "failed", report["validity"]
                    assert read(harness_node.root / "node-status.json")["exitCode"] == 1
                secret = read(sendium.root / "generated/harness/runtime-settings.json")["provider"]["password"]
                sendium.stopping = True
                if run_application:
                    sendium_thread.join(timeout=90)
                    assert not sendium_thread.is_alive()
                output = aws.directory / "bucket" / prefix / "outputs"
                published = [path for path in output.rglob("*") if path.is_file()]
                if run_application:
                    assert any(path.name == "sendium-resources.jsonl" for path in published)
                    assert any(path.name == "harness-resources.jsonl" for path in published)
                assert any(path.name == "results.json" for path in published)
                assert any(path.name == "summary.md" for path in published)
                for path in published:
                    assert secret not in path.read_text(), path
                    assert "node-test-password" not in path.read_text(), path
                assert not any(path.name in ("environment.json", "runtime-settings.json", "deployment-private.json") for path in published)
                if evidence:
                    shutil.copytree(output, evidence / (mode if run_application else "readiness-failure"))
                print(mode + ("" if run_application else " readiness failure")
                      + ": real node install/configure/supervise/report/sampling/redaction passed", flush=True)
            finally:
                sendium.stopping = True
                harness_node.stopping = True
                for thread in threads:
                    thread.join(timeout=90)
                shutil.rmtree(sendium.root.parent)
                shutil.rmtree(sendium.install.parent)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
