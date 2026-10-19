"""Run-owned Linux node deployment. Execute only with the bounded EC2 runtime role."""

import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import subprocess
import sys
import tarfile
import threading
import time

from aws_cli import AwsCli


ROOT = Path("/var/lib/sendium-benchmark")
INSTALL = Path("/opt/sendium-benchmark")
MAX_LOG_BYTES = 4 * 1024 * 1024
PUBLIC_FILES = ("results.json", "summary.md", "state.json", "lifecycle.jsonl", "harness-resources.jsonl")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(path)


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def extract(archive, destination, maximum_bytes):
    """Extract bounded tar files without following archive-created links."""
    destination = Path(destination)
    destination.mkdir(mode=0o700)
    with tarfile.open(archive, "r:gz") as bundle:
        members = []
        total = 0
        for member in bundle:
            total += member.size
            if len(members) >= 100000 or total > maximum_bytes:
                raise ValueError("Archive exceeds extraction budget")
            members.append(member)
        names = set()
        links = []
        for member in members:
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or ".." in relative.parts or "\\" in member.name:
                raise ValueError("Archive path escapes destination")
            if str(relative) in (".", "") and member.isdir():
                continue
            if str(relative) in names or not (member.isfile() or member.isdir() or member.issym()):
                raise ValueError("Unsupported or duplicate archive entry")
            names.add(str(relative))
            if member.issym():
                links.append(member)
                continue
            target = destination / str(relative)
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            if member.isdir():
                target.mkdir(exist_ok=True, mode=0o700)
            else:
                with bundle.extractfile(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o700 if member.mode & 0o111 else 0o600)
        # Links are created only after regular entries, preventing writes through symlinks.
        for member in links:
            target = destination / member.name
            link = PurePosixPath(member.linkname)
            if link.is_absolute() or "\\" in member.linkname:
                raise ValueError("Absolute archive link")
            resolved = (target.parent / member.linkname).resolve()
            if destination.resolve() not in resolved.parents:
                raise ValueError("Archive link escapes destination")
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            target.symlink_to(member.linkname)


class CapturedProcess:
    """Drain JVM output into a bounded in-memory tail, not an unbounded root-disk log."""
    def __init__(self, command, cwd, environment=None):
        self.parts = deque()
        self.size = 0
        self.lock = threading.Lock()
        self.process = subprocess.Popen(command, cwd=cwd, env=environment, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, start_new_session=True)
        self.thread = threading.Thread(target=self._capture, daemon=True)
        self.thread.start()

    def _capture(self):
        with self.process.stdout as stream:
            for chunk in iter(lambda: stream.read(8192), b""):
                with self.lock:
                    self.parts.append(chunk)
                    self.size += len(chunk)
                    while self.size > MAX_LOG_BYTES:
                        self.size -= len(self.parts.popleft())

    def stop(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self.thread.join(timeout=5)

    def log(self):
        with self.lock:
            return b"".join(self.parts).decode("utf-8", errors="replace")


def child_environment():
    # The JVM uses local files; do not inherit AWS keys/profiles or application overrides.
    return {key: value for key, value in os.environ.items() if not key.startswith(
        ("AWS_", "QUARKUS_", "SENDIUM_", "SMSG_", "JAVA_", "JDK_")) and key not in ("LOG_LEVEL", "_JAVA_OPTIONS")}


class Node:
    def __init__(self, document, root=ROOT, install=INSTALL, aws=None):
        self.document = document
        self.source = document["source"]
        self.identity = document["runIdentity"]
        self.root = Path(root) / self.identity / self.source
        self.install = Path(install) / self.identity / self.source
        self.aws = aws or AwsCli(document["region"])
        self.bucket = document["bucket"]
        self.prefix = "runs/" + self.identity + "/"
        self.stopping = False

    def fetch(self, descriptor, target):
        self.aws.get(self.bucket, descriptor["key"], target)
        if Path(target).stat().st_size != descriptor["size"] or digest(target) != descriptor["sha256"]:
            Path(target).unlink()
            raise ValueError("Artifact digest/size mismatch")

    def publish(self, name, path):
        self.aws.put(self.bucket, self.prefix + "outputs/" + self.source + "/" + name, path)

    def status(self, phase, **fields):
        value = {"schemaVersion": 1, "runIdentity": self.identity, "source": self.source, "phase": phase, **fields}
        write(self.root / "node-status.json", value)
        self.publish("node-status.json", self.root / "node-status.json")

    def install_files(self):
        self.root.mkdir(parents=True, mode=0o700, exist_ok=False)
        self.install.mkdir(parents=True, mode=0o700, exist_ok=False)
        artifacts = self.document["artifacts"]
        for name, descriptor in artifacts.items():
            self.fetch(descriptor, self.root / name)
        # Leave at least 512 MiB for OS/SSM and evidence after expanding both archives.
        budget = shutil.disk_usage(self.install).free - 512 * 1024 * 1024
        extract(self.root / "jre", self.install / "jre", budget)
        candidates = list((self.install / "jre").glob("*/bin/java"))
        if len(candidates) != 1:
            raise ValueError("Expected one top-level Java runtime directory")
        java = str(candidates[0])
        version = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30, check=True, env=child_environment())
        if 'version "25' not in version.stderr:
            raise ValueError("Java 25 runtime required")
        write(self.root / "runtime.json", {"java": java, "version": version.stderr.strip()})
        if self.source == "sendium":
            budget = shutil.disk_usage(self.install).free - 512 * 1024 * 1024
            extract(self.root / "application", self.install / "application", budget)
            if not (self.install / "application/quarkus-app/quarkus-run.jar").is_file():
                raise ValueError("Application archive must contain the complete quarkus-app tree")
        (INSTALL / "rds-ca").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.root / "rdsCa", INSTALL / "rds-ca/global-bundle.pem")
        self.status("installed", runtimeVersion=version.stderr.strip())

    def java(self):
        return read(self.root / "runtime.json")["java"]

    def configure(self):
        secret = self.aws.call("secretsmanager", "get-secret-value", secret_id=self.document["databaseSecretArn"])
        credentials = json.loads(secret["SecretString"])
        deployment = dict(self.document["deployment"])
        deployment["database"] = {**deployment["database"], "username": credentials["username"], "password": credentials["password"]}
        write(self.root / "deployment-private.json", deployment)
        write(self.root / "workload.json", self.document["workload"])
        completed = subprocess.run([self.java(), "-jar", str(self.root / "harness"), "generate-config",
                                    str(self.root / "workload.json"), str(self.root / "deployment-private.json"),
                                    str(self.root / "generated")], capture_output=True, timeout=60, env=child_environment())
        if completed.returncode:
            raise RuntimeError("Configuration generation failed")
        for name in ("runtime-settings.json", "run-config.json", "effective-configuration.json"):
            self.aws.put(self.bucket, self.prefix + "inputs/harness/generated/" + name,
                         self.root / "generated/harness" / name, immutable=True)
        self.status("configured")

    def secrets(self):
        values = []
        for path in (self.root / "generated/sendium/environment.json", self.root / "generated/harness/runtime-settings.json"):
            if path.is_file():
                def visit(node):
                    if isinstance(node, dict):
                        for key, value in node.items():
                            if any(part in key.lower() for part in ("password", "username", "systemid")) and isinstance(value, str):
                                values.append(value)
                            else:
                                visit(value)
                visit(read(path))
        return values

    def supervise(self):
        captured = None
        sampler = None
        exit_code = 1
        private_values = ()
        try:
            if self.source == "harness":
                generated = self.root / "generated/harness"
                generated.mkdir(parents=True, mode=0o700)
                for name in ("runtime-settings.json", "run-config.json", "effective-configuration.json"):
                    self.aws.get(self.bucket, self.prefix + "inputs/harness/generated/" + name, generated / name)
                execution = self.document["execution"]
                execution["sampling"] = {"pythonExecutable": "python3", "agentPath": str(self.root / "sampler"),
                                         "intervalSeconds": self.document["sampleIntervalSeconds"],
                                         "maxSamples": self.document["sampleBudget"]}
                write(self.root / "execution.json", execution)
                command = [self.java(), *self.document["jvmArguments"], "-jar", str(self.root / "harness"), "run",
                           str(generated), str(self.root / "execution.json"), str(self.root / "report")]
                private_values = tuple(self.secrets())
                captured = CapturedProcess(command, self.root, child_environment())
            else:
                environment = child_environment()
                environment.update(read(self.root / "generated/sendium/environment.json"))
                command = [self.java(), *self.document["jvmArguments"], "-jar",
                           str(self.install / "application/quarkus-app/quarkus-run.jar")]
                private_values = tuple(self.secrets())
                captured = CapturedProcess(command, self.root / "generated/sendium", environment)
                sampler = subprocess.Popen(["python3", str(self.root / "sampler"), "--pid", str(captured.process.pid),
                                            "--duration-seconds", str(self.document["timeoutSeconds"]),
                                            "--interval-seconds", str(self.document["sampleIntervalSeconds"]),
                                            "--max-samples", str(self.document["sampleBudget"]),
                                            "--output", str(self.root / "sendium-resources.jsonl")],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            deadline = time.monotonic() + self.document["timeoutSeconds"]
            self.status("running", processId=captured.process.pid)
            ready = False
            while captured.process.poll() is None and not self.stopping and not (self.root / "stop").exists():
                if time.monotonic() >= deadline:
                    raise TimeoutError("Node execution budget exhausted")
                if self.source == "harness" and not ready and (self.root / "report/state.json").is_file():
                    ready = read(self.root / "report/state.json").get("listenersReady", False)
                    if ready:
                        self.status("listening", processId=captured.process.pid)
                time.sleep(1)
            exit_code = captured.process.poll()
            if exit_code is None:
                exit_code = 1 if self.source == "harness" else 0
        finally:
            # Finish remote sampling while the JVM still exists, retaining its final interval.
            if sampler and sampler.poll() is None:
                sampler.terminate()
                try:
                    sampler.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    sampler.kill()
                    sampler.wait(timeout=5)
            if captured:
                captured.stop()
                text = captured.log()
                for value in sorted(private_values, key=len, reverse=True):
                    if value:
                        text = text.replace(value, "[REDACTED]")
                (self.root / "process.log").write_text(text, encoding="utf-8")
            # Explicit allowlist: never publish generated private configuration or deployment input.
            candidates = {"process.log": self.root / "process.log",
                          "sendium-resources.jsonl": self.root / "sendium-resources.jsonl"}
            candidates.update({name: self.root / "report" / name for name in PUBLIC_FILES})
            issues = []
            for name, path in candidates.items():
                if path.is_file():
                    try:
                        self.publish(name, path)
                    except RuntimeError:
                        issues.append(name)
            self.status("finished", exitCode=exit_code, retrievalIssues=issues)
        return exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("install", "configure", "supervise", "stop"))
    parser.add_argument("--document", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    node = Node(read(args.document))
    signal.signal(signal.SIGTERM, lambda *_: setattr(node, "stopping", True))
    signal.signal(signal.SIGINT, lambda *_: setattr(node, "stopping", True))
    try:
        if args.phase == "install":
            node.install_files()
        elif args.phase == "configure":
            node.configure()
        elif args.phase == "supervise":
            return node.supervise()
        else:
            (node.root / "stop").touch()
        return 0
    except Exception:
        if node.root.is_dir():
            try:
                node.status("failed", exitCode=1, error="NODE_" + args.phase.upper() + "_FAILED")
            except Exception:
                pass
        print("NODE_" + args.phase.upper() + "_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
