"""Run the real local topology and production orchestration through an explicit adapter."""

import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tarfile
import threading
import time


PERFORMANCE = Path(__file__).resolve().parents[1]
for folder in ("workflow", "deployment", "contracts"):
    sys.path.insert(0, str(PERFORMANCE / folder))
import prepare
import publish
import recover
import run_aws
import runtime_assets
from cleanup import Cleaner
from expiry import Sweeper
from node import extract
from aws_cli import AwsError
from backend import ACCOUNT, BOOT, PREFIX, REGION, ROLE, Lab, LocalAws, docker, inspect
from public_artifacts import redact_known
from scenarios import ALL as SCENARIOS, select


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class Assets:
    def __init__(self, lab, identity, fault):
        self.lab, self.identity, self.fault = lab, identity, fault
        self.real = runtime_assets.Downloads()
        self.cache = Path("/state/runtime-cache")
        self.cache.mkdir(exist_ok=True)

    def metadata(self, url):
        path = self.cache / (hashlib.sha256(url.encode()).hexdigest() + ".json")
        if not path.exists():
            path.write_text(json.dumps(self.real.metadata(url)))
        return prepare.read(path)

    def download(self, url, target, maximum, checksum=None, size=None):
        if "truststore.pki.rds.amazonaws.com" in url:
            name = "wrong-ca.pem" if self.fault == "bad-tls" else "ca.pem"
            source = Path("/state/private") / self.identity / "tls" / name
            origin = "local-fixture://postgresql-verified-tls/" + name
        else:
            source = self.cache / (checksum + ".tar.gz")
            if not source.exists():
                self.real.download(url, source, maximum, checksum=checksum, size=size)
            require(prepare.digest(source) == checksum and source.stat().st_size == size, "Cached runtime checksum mismatch")
            origin = url
        shutil.copyfile(source, target)
        return {"path": str(target), "url": origin, "size": Path(target).stat().st_size, "sha256": prepare.digest(target)}


def request_for(lab, identity, scenario, offered_rate=None):
    preset = prepare.read(PERFORMANCE / "presets/aws-private-jvm-v1.json")
    preset["id"] = "local-aws-like-v1"
    preset["aws"]["instanceDefaults"] = {source: "local.small" for source in ("sendium", "harness")}
    preset["database"]["instanceType"] = "db.local.small"
    mode = "smpp" if scenario.startswith("smpp") else "http"
    dlr = not scenario.endswith("no-dlr")
    run, attempt = identity[3:].rsplit("-", 1)
    context = {"ref": lab.source.get("applicationRef", "refs/heads/local-rehearsal"), "repository": "cytechmobile/sendium", "applicationCommit": lab.source.get("applicationCommit", lab.source["baseCommit"]),
               "controllerRevision": lab.source["baseCommit"], "workflowRevision": lab.source["baseCommit"], "runId": run, "runAttempt": attempt,
               "workflowRunUrl": "local://rehearsal/" + identity}
    inputs = {"ingress_mode": mode, "dlr_enabled": dlr}
    if offered_rate is not None:
        inputs["offered_rate"] = offered_rate
    return prepare.normalize(inputs, context, preset)


def bundles_for(lab, request, directory):
    directory.mkdir()
    for component in ("application", "harness"):
        path = directory / (component + "-" + lab.architecture)
        path.mkdir()
        target = path / prepare.FILES[component]
        if component == "application":
            prepare.archive_application(Path("/opt/build/quarkus-app"), target)
        else:
            candidates = [item for item in Path("/opt/build/harness").glob("*.jar") if not item.name.endswith("-shaded.jar")]
            require(len(candidates) == 1, "Expected one built harness")
            shutil.copyfile(candidates[0], target)
        prepare.write(path / "build-manifest.json", {"schemaVersion": 1, "runIdentity": request["runIdentity"], "component": component,
            "sourceRevision": prepare.revision(request, component), "architecture": lab.architecture, "javaVersion": "25-local-build",
            "file": target.name, "sha256": prepare.digest(target), "size": target.stat().st_size})
    def verify(req, location):
        return {"schemaVersion": 1, "runIdentity": req["runIdentity"], "provenance": req["provenance"], "controllerRevision": req["controllerRevision"],
                "artifacts": [prepare.verify_bundle(req, location, component, lab.architecture) for component in ("application", "harness")]}
    return verify(request, directory), verify


def monitor(lab, identity, scenario, stopped):
    while not stopped.wait(0.3):
        root = lab.roots.get(identity)
        if root is None or root["StackStatus"] != "CREATE_COMPLETE":
            continue
        harness = root["containers"]["harness"]
        path = "/var/lib/sendium-benchmark/" + identity + "/harness/report/state.json"
        result = docker("exec", harness, "cat", path, check=False, timeout=5)
        if result.returncode:
            continue
        try:
            state = json.loads(result.stdout)
        except ValueError:
            continue
        if state.get("phase") != "MEASUREMENT":
            continue
        check = docker("exec", root["containers"]["database"], "psql", "-U", "sendium_perf", "-d", "sendium_benchmark", "-Atc",
                       "SELECT count(*) FROM pg_stat_ssl WHERE ssl", check=False)
        root["checks"]["verifiedTlsConnectionSeen"] = check.returncode == 0 and int(check.stdout.strip() or "0") > 0
        root["checks"]["measurementSeen"] = True
        if scenario == "app-crash":
            status = "/var/lib/sendium-benchmark/" + identity + "/sendium/node-status.json"
            docker("exec", root["containers"]["sendium"], "python3", "-c",
                   "import json,os,signal; os.kill(json.load(open('" + status + "'))['processId'],signal.SIGKILL)")
            root["checks"]["faultInjected"] = True
        elif scenario == "interrupted":
            root["checks"]["faultInjected"] = True
            os.kill(os.getpid(), signal.SIGTERM)
        return


def prepare_publication(lab, identity, base, request, inputs, bundles, outcomes):
    destination = Path("/state/publish") / identity
    destination.mkdir(parents=True)
    prepare.write(destination / "request.json", request)
    prepare.write(destination / "inputs.json", inputs)
    prepare.write(destination / "outcomes.json", outcomes)
    for kind in ("diagnostics", "cleanup"):
        shutil.copytree(base / kind, destination / kind)
    controller = destination / "controller"
    controller.mkdir()
    for name in ("controller-outcome.json", "resolution.json", "stack-events.json"):
        if (base / "controller" / name).is_file():
            shutil.copyfile(base / "controller" / name, controller / name)
    for name in ("execution/retrieved", "retrieved"):
        if (base / "controller" / name).is_dir():
            shutil.copytree(base / "controller" / name, controller / name)
    (destination / "builds").mkdir()
    shutil.copytree(bundles / ("harness-" + lab.architecture), destination / "builds" / ("harness-" + lab.architecture))
    runtime = next((base / "controller/runtime").glob("jre-*.tar.gz"), None)
    if runtime:
        shutil.copyfile(runtime, destination / "runtime.tar.gz")
        prepare.write(destination / "runtime.json", {"sha256": prepare.digest(runtime)})
    result = docker("run", "--rm", "--network", "none", "--mount",
        "type=volume,source=" + os.environ["LOCAL_STATE_VOLUME"] + ",target=/publication,volume-subpath=publish/" + identity,
        "sendium-local:control", "publish", timeout=180)
    return prepare.read(destination / "result/workflow-outcome.json")


def publish_mode():
    root = Path("/publication")
    java = "java"
    if (root / "runtime.tar.gz").exists():
        require(prepare.digest(root / "runtime.tar.gz") == prepare.read(root / "runtime.json")["sha256"], "Publisher runtime digest mismatch")
        extract(root / "runtime.tar.gz", Path("/tmp/publisher-jre"), 2 * 1024 ** 3)
        java, = (Path("/tmp/publisher-jre")).glob("*/bin/java")
        java = str(java)
    # A failed observation intentionally returns exit 1; artifact creation itself must still succeed.
    publish.publish(prepare.read(root / "request.json"), root / "controller", root / "diagnostics", root / "cleanup", root / "builds",
                    prepare.read(root / "inputs.json"), prepare.read(root / "outcomes.json"), root / "result", java)
    return 0


def assert_no_run_resources(lab, identity):
    labels = ["--filter", "label=sendium.local.session=" + lab.session, "--filter", "label=sendium.local.run=" + identity]
    for arguments in (("ps", "-aq"), ("network", "ls", "-q"), ("volume", "ls", "-q")):
        require(not docker(*arguments, *labels).stdout.strip(), "Run-owned local resource leaked")
    objects = lab.real.call("s3api", "list-objects-v2", bucket=lab.bucket, prefix="runs/" + identity + "/")
    require(not objects.get("Contents"), "Run-owned object prefix leaked")


def network_checks(lab, root):
    node = root["containers"]["harness"]
    database = inspect(root["containers"]["database"])["NetworkSettings"]["Networks"][root["networks"]["database"]]["IPAddress"]
    program = "import socket,sys\ntry:\n s=socket.create_connection(('" + database + "',5432),2);s.close();sys.exit(1)\nexcept OSError: sys.exit(0)"
    root["checks"]["harnessCannotReachDatabase"] = docker("exec", node, "python3", "-c", program, check=False).returncode == 0
    require(root["checks"]["harnessCannotReachDatabase"], "Harness reached database-only network")
    denied = docker("exec", node, "aws", "secretsmanager", "get-secret-value", "--secret-id", root["secret"], check=False)
    root["checks"]["harnessCannotReadDatabaseSecret"] = denied.returncode != 0 and "AccessDenied" in denied.stderr
    require(root["checks"]["harnessCannotReadDatabaseSecret"], "Harness runtime scope allowed database-secret read")


def start_witness(lab, identity):
    base = Path("/state/runs") / identity
    base.mkdir(parents=True)
    subprocess.run([sys.executable, str(PERFORMANCE / "local/tls.py"), str(Path("/state/private") / identity / "tls")], check=True)
    req = request_for(lab, identity, "http-dlr", lab.offered_rate)
    inputs, verify = bundles_for(lab, req, base / "bundles")
    result = {}
    stopped = threading.Event()
    watcher = threading.Thread(target=monitor, args=(lab, identity, "witness", stopped), daemon=True)
    watcher.start()
    def execute():
        result.update(run_aws.run(LocalAws(lab, identity), Assets(lab, identity, "witness"), req, inputs, base / "bundles",
            {"region": REGION, "bootstrapStack": BOOT, "roleArn": ROLE + PREFIX + "-orchestrator"}, base / "controller", verify_builds=verify))
    worker = threading.Thread(target=execute, daemon=True)
    worker.start()
    deadline = time.monotonic() + req["preset"]["measurement"]["readinessTimeoutSeconds"] + req["preset"]["workload"]["warmupSeconds"] + 180
    while time.monotonic() < deadline:
        if lab.roots.get(identity, {}).get("checks", {}).get("measurementSeen"):
            return {"identity": identity, "request": req, "base": base, "inputs": inputs, "worker": worker, "watcher": watcher,
                    "stopped": stopped, "outcome": result}
        require(worker.is_alive(), "Concurrent witness failed before measurement")
        time.sleep(0.5)
    raise AssertionError("Concurrent witness readiness deadline exceeded")


def finish_witness(lab, witness):
    workload = witness["request"]["preset"]["workload"]
    witness["worker"].join(timeout=sum(workload[key] for key in ("warmupSeconds", "measurementSeconds", "drainTimeoutSeconds")) + 300)
    require(not witness["worker"].is_alive() and witness["outcome"].get("exitCode") == 0, "Concurrent witness observation failed")
    witness["stopped"].set()
    witness["watcher"].join(timeout=10)
    identity, req, base = witness["identity"], witness["request"], witness["base"]
    diag = recover.run(LocalAws(lab, identity, "diagnostics"), req, REGION, BOOT, ROLE + PREFIX + "-diagnostics",
                       base / "diagnostics", True, credits=LocalAws(lab, identity, "diagnostics"))
    clean = recover.run(LocalAws(lab, identity, "cleanup"), req, REGION, BOOT, ROLE + PREFIX + "-cleanup", base / "cleanup", False)
    require(clean["exitCode"] == 0, "Concurrent witness cleanup failed")
    assert_no_run_resources(lab, identity)
    prepare_publication(lab, identity, base, req, witness["inputs"], base / "bundles",
                        {"aws-execution": "success", "diagnostics": "success" if diag["exitCode"] == 0 else "failure", "cleanup": "success"})


def run_case(lab, identity, scenario):
    started = time.monotonic()
    print("Rehearsing " + scenario, flush=True)
    base = Path("/state/runs") / identity
    base.mkdir(parents=True)
    private = Path("/state/private") / identity / "tls"
    subprocess.run([sys.executable, str(PERFORMANCE / "local/tls.py"), str(private)], check=True)
    request = request_for(lab, identity, scenario, lab.offered_rate)
    inputs, verify = bundles_for(lab, request, base / "bundles")
    witness = start_witness(lab, identity.rsplit("-", 1)[0] + "-99") if scenario == "concurrent" else None
    aws = LocalAws(lab, identity, fault=scenario)
    settings = {"region": REGION, "bootstrapStack": BOOT, "roleArn": ROLE + PREFIX + "-orchestrator"}
    stopped = threading.Event()
    thread = threading.Thread(target=monitor, args=(lab, identity, scenario, stopped), daemon=True)
    thread.start()
    try:
        outcome = run_aws.run(aws, Assets(lab, identity, scenario), request, inputs, base / "bundles", settings, base / "controller", verify_builds=verify)
    finally:
        stopped.set()
        thread.join(timeout=10)
    root = lab.roots.get(identity)
    if scenario in SCENARIOS[:4] or scenario == "concurrent":
        network_checks(lab, root)
    if witness:
        other = lab.roots[witness["identity"]]
        denied = docker("exec", root["containers"]["harness"], "aws", "s3api", "get-object", "--bucket", lab.bucket,
                       "--key", "runs/" + witness["identity"] + "/control/run.json", "/tmp/forbidden", check=False)
        require(denied.returncode != 0 and "AccessDenied" in denied.stderr, "Runtime scope crossed run prefixes")
        root["checks"]["crossRunInputsDenied"] = True
        other_ip = lab.instance(other, "sendium")["PrivateIpAddress"]
        probe = "import socket,sys\ntry:\n s=socket.create_connection(('" + other_ip + "',8080),2);s.close();sys.exit(1)\nexcept OSError:sys.exit(0)"
        require(docker("exec", root["containers"]["harness"], "python3", "-c", probe, check=False).returncode == 0,
                "Traffic network crossed into another run")
        root["checks"]["crossRunTrafficIsolated"] = True
    # Snapshot known protocol values before cleanup; never expose their private settings.
    try:
        path = Path("/state/private") / identity / "protocol.json"
        aws.get(lab.bucket, "runs/" + identity + "/inputs/harness/generated/runtime-settings.json", path)
        def passwords(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if "password" in key.lower() and isinstance(item, str):
                        lab.passwords.append(item)
                    else:
                        passwords(item)
        passwords(prepare.read(path))
    except AwsError:
        pass
    diagnostics = recover.run(LocalAws(lab, identity, "diagnostics"), request, REGION, BOOT, ROLE + PREFIX + "-diagnostics",
                              base / "diagnostics", True, credits=LocalAws(lab, identity, "diagnostics"))
    if scenario == "bad-tls":
        require(root["checks"].get("invalidCaRejected"), "Invalid CA handshake failure was not observed")
    if scenario == "abandoned":
        lab.real.call("s3api", "create-multipart-upload", bucket=lab.bucket, key="runs/" + identity + "/inputs/unfinished")
        future = datetime.now(timezone.utc) + timedelta(hours=5)
        sweep_settings = {"region": REGION, "repository": request["repository"], "bootstrapStack": BOOT, "bucket": lab.bucket, "ttlSeconds": 14400}
        sweep = LocalAws(lab, identity, "expiry")
        Sweeper(sweep, sweep_settings, 60, now=future).run()
        Sweeper(sweep, sweep_settings, 60, now=future + timedelta(minutes=15)).run()
        require(root["StackStatus"] == "DELETE_COMPLETE", "Independent expiry did not remove local resources")
        root["checks"]["independentExpiryRecovered"] = True
    cleanup = recover.run(LocalAws(lab, identity, "cleanup"), request, REGION, BOOT, ROLE + PREFIX + "-cleanup", base / "cleanup", False)
    require(cleanup["exitCode"] == 0, "Local cleanup failed")
    assert_no_run_resources(lab, identity)
    repeated = recover.run(LocalAws(lab, identity, "cleanup"), request, REGION, BOOT, ROLE + PREFIX + "-cleanup", base / "cleanup-repeat", False)
    require(repeated["exitCode"] == 0, "Repeated cleanup was not idempotent")
    if witness:
        other = lab.roots[witness["identity"]]
        require(all(inspect(name) and inspect(name)["State"]["Running"] for name in other["containers"].values()), "Cleanup affected another live run")
        require(lab.real.call("s3api", "list-objects-v2", bucket=lab.bucket, prefix="runs/" + witness["identity"] + "/").get("Contents"), "Cleanup affected another prefix")
        root["checks"]["otherRunSurvivedCleanup"] = True
        finish_witness(lab, witness)
    outcomes = {"aws-execution": "success" if outcome["exitCode"] == 0 else "failure", "diagnostics": "success" if diagnostics["exitCode"] == 0 else "failure", "cleanup": "success"}
    final = prepare_publication(lab, identity, base, request, inputs, base / "bundles", outcomes)
    published = Path("/state/publish") / identity / "result"
    report = publish.read(published / "results.json")
    publish.validate(report, request)
    # Preserve validated observations even when a strict load qualification assertion fails.
    destination = Path("/state/public") / scenario
    shutil.copytree(published, destination, ignore=shutil.ignore_patterns("_work"))
    if scenario in SCENARIOS[:4] or scenario == "concurrent":
        measurement = [event for event in report["eventIntervals"] if event["phase"] == "measurement"]
        stability = {"schemaVersion": 1, "workload": request["preset"]["workload"], "overall": report["overall"], "drain": report["drain"],
            "minutes": [{"startSeconds": event["startOffsetSeconds"], "durationSeconds": event["durationSeconds"],
                "providerResponses": event["providerResponses"], "receiptsForwarded": event["receiptsForwarded"],
                "rates": event["rates"], "outstanding": event["outstanding"]} for event in measurement],
            "resources": report["resources"].get("summary", {}), "observationStatus": report["validity"]["status"]}
        prepare.write(published / "load-stability.json", stability)
        shutil.copyfile(published / "load-stability.json", destination / "load-stability.json")
    if scenario in SCENARIOS[:4] or scenario == "concurrent":
        require(outcome["exitCode"] == 0 and final["exitCode"] == 0, "Positive local scenario failed")
        counts = report["overall"]["counts"]
        expected = math.ceil(request["preset"]["workload"]["offeredMessagesPerSecond"] * request["preset"]["workload"]["measurementSeconds"])
        qualified = counts["scheduled"] == expected and counts["attempted"] == expected and counts["missed"] == 0 \
                    and counts["accepted"] == expected and counts["providerResponses"] == expected and report["validity"]["status"] == "valid"
        root["checks"]["allTargetsOffered"] = counts["attempted"] == expected and counts["missed"] == 0
        root["checks"]["loadQualified"] = qualified
        if lab.offered_rate is None:
            require(qualified, "Measurement/warm-up accounting mismatch")
        require(counts["receiptsForwarded"] == (counts["accepted"] if request["preset"]["workload"]["dlrEnabled"] else 0), "DLR accounting mismatch")
        require(root["checks"].get("verifiedTlsConnectionSeen"), "No actual verified-TLS database connection observed")
    else:
        require(outcome["exitCode"] != 0 and final["exitCode"] != 0, "Injected failure was reported successful")
    if scenario in ("app-crash", "interrupted"):
        require(root["checks"].get("faultInjected"), "Fault did not reach the running measurement")
    for path in destination.rglob("*"):
        if path.is_file():
            body = path.read_bytes()
            require(not any(password.encode() in body for password in lab.passwords), "Private database credential entered public artifacts")
    print("PASS " + scenario, flush=True)
    return {"scenario": scenario, "passed": True, "observationStatus": report["validity"]["status"], "pipelineExitCode": final["exitCode"],
            "elapsedSeconds": round(time.monotonic() - started, 3), "checks": root["checks"] if root else {"partialStagingRecovered": True}}


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "publish":
        return publish_mode()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenarios", default="load")
    parser.add_argument("--offered-rate", type=float)
    args = parser.parse_args()
    Path("/state/public").mkdir(exist_ok=True)
    def interrupted(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    report = {"schemaVersion": 1, "mode": "local-functional-rehearsal", "cases": [], "exitCode": 1}
    try:
        lab = Lab(os.environ["LOCAL_SESSION"])
        lab.offered_rate = args.offered_rate
        report["boundaries"] = lab.simulation
        selected = select(args.scenarios)
        run = str(time.time_ns())
        for index, scenario in enumerate(selected, 1):
            identity = "gh-" + run + "-" + str(index)
            try:
                report["cases"].append(run_case(lab, identity, scenario))
            except Exception:
                # Retain only already-public diagnostic paths even when a rehearsal assertion fails.
                destination = Path("/state/public") / scenario / "failed-rehearsal-evidence"
                destination.mkdir(parents=True, exist_ok=True)
                base = Path("/state/runs") / identity
                for path in (base / "diagnostics/retrieved", base / "controller/execution/retrieved", base / "controller/retrieved"):
                    if path.is_dir():
                        shutil.copytree(path, destination / path.parent.name, dirs_exist_ok=True)
                for name in ("controller-outcome.json", "resolution.json", "stack-events.json"):
                    if (base / "controller" / name).is_file():
                        shutil.copyfile(base / "controller" / name, destination / name)
                raise
        report["exitCode"] = 0
    except Exception as error:
        report["failure"] = type(error).__name__ + ": " + str(error)
        print("Local rehearsal failed: " + report["failure"], flush=True)
    finally:
        if "lab" in locals():
            redacted = redact_known(Path("/state/public"), lab.passwords)
            if redacted:
                report.update(exitCode=1, failure="Known private fixture values were rejected from export", rejectedArtifacts=redacted)
        prepare.write(Path("/state/public/rehearsal.json"), report)
    return report["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
