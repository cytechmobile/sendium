"""Local Docker controller for the shared Kannel benchmark engine; no AWS calls."""

import argparse
import copy
from decimal import Decimal, ROUND_CEILING
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import sys
import time
import uuid

import smoke
from database import Database

PERFORMANCE = Path(__file__).resolve().parents[1]
STATE = Path("/state")
sys.path.insert(0, str(PERFORMANCE / "contracts"))
sys.path.insert(0, str(PERFORMANCE / "local"))
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from preset_validation import validate_preset
from semantics import validate_results
from scenarios import select, add_workload_arguments, workload_overrides, WORKLOAD_ARGUMENTS


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def read(path, maximum=1048576):
    path = Path(path)
    if path.stat().st_size > maximum:
        raise ValueError("Metadata exceeds its byte budget")
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, value):
    with Path(path).open("x", encoding="utf-8") as target:
        json.dump(value, target, indent=2, allow_nan=False)
        target.write("\n")


def failure_code(error):
    code = str(error)
    return code if isinstance(error, (RuntimeError, ValueError)) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", code) else type(error).__name__


def workload(preset, arguments, scenario):
    merged = copy.deepcopy(preset)
    overrides = workload_overrides(arguments, preset)
    for name, value in overrides.items():
        merged["workload"][WORKLOAD_ARGUMENTS[name][0]] = value
    if arguments.offered_rate is not None:
        merged["workload"]["offeredMessagesPerSecond"] = arguments.offered_rate
    merged["workload"]["offeredMessagesPerSecond"] = float(merged["workload"]["offeredMessagesPerSecond"])
    merged["workload"].update(ingressMode="http", dlrEnabled=scenario != "http-no-dlr")
    validate_preset(merged, Draft202012Validator(read(PERFORMANCE / "contracts/run-config.schema.json")))
    # This local deployment uses the same reviewed listener topology as the preset.
    for key, value in (("httpPort", 8080), ("providerPort", 27779), ("callbackPort", 8081)):
        if merged["deployment"][key] != value:
            raise ValueError("Unsupported local listener topology")
    return merged


def image_metadata(image):
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
        raise ValueError("Exact local image ID required")
    value = smoke.docker("run", "--rm", "--network", "none", "--entrypoint", "python3", image,
                         "/opt/probe/metadata.py", "show")
    if len(value) > 65536:
        raise ValueError("Image metadata exceeds its budget")
    return json.loads(value)


def validate_images(source, gateway, harness, engine):
    architecture = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(engine)
    if (gateway.get("schemaVersion") != 1 or harness.get("schemaVersion") != 1
            or gateway.get("role") != "gateway" or harness.get("role") != "harness"
            or harness["sourceContext"] != source or gateway["samplerSha256"] != harness["samplerSha256"]
            or gateway["samplerSha256"] != digest(PERFORMANCE / "resources/sample_process.py")
            or gateway["supervisorSha256"] != digest(PERFORMANCE / "kannel/gateway.py")
            or gateway.get("postgresqlProbeSha256") != digest(PERFORMANCE / "kannel/postgresql_probe.py")
            or gateway["architecture"] != harness["architecture"] or gateway["architecture"] != architecture
            or gateway.get("postgresqlSupport") is not True):
        raise ValueError("BUILD_IDENTITIES_DIFFER")


def prepare(directory, preset, scenario, identity, source, gateway, harness, images, passwords, database=None):
    deployment, measurement, run = preset["deployment"], preset["measurement"], preset["workload"]
    (directory / "inputs").mkdir()
    storage = "postgresql" if database else "internal"
    configuration = smoke.configuration(*passwords[:3], worker_tps=run["workerTps"], outgoing_window=deployment["outgoingWindow"],
                                         ingress_window=run["maxOutstandingRequests"],
                                         request_timeout_seconds=math.ceil(deployment["outgoingRequestTimeoutMillis"] / 1000),
                                         database=database.settings if database else None)
    if database:
        private_database = dict(database.settings)
        if scenario == "bad-tls":
            private_database["caFile"] = "/probe/tls/wrong-ca.pem"
        write(directory / "database.json", private_database)
    (directory / "kannel.conf").write_text(configuration, encoding="utf-8")
    public_config = configuration
    for password in passwords:
        public_config = public_config.replace(password, "[REDACTED]")
    lifetime = measurement["readinessTimeoutSeconds"] + sum(run[key] for key in ("warmupSeconds", "measurementSeconds", "drainTimeoutSeconds")) + 30
    settings = {"schemaVersion": 1, "runIdentity": identity, "lifetimeSeconds": lifetime,
                "intervalSeconds": measurement["sampleIntervalSeconds"], "maxSamples": measurement["sampleBudgetPerSource"]}
    write(directory / "benchmark-settings.json", settings)
    profile = "local-kannel-pgsql-v1" if database else "local-kannel-v1"
    effective = {"schemaVersion": 1, "benchmarkTarget": "kannel", "runIdentity": identity, "preset": profile,
                 "basePreset": {"id": preset["id"], "sha256": digest(PERFORMANCE / "presets/aws-private-jvm-v1.json")},
                 "workload": run, "source": gateway["source"], "dlrStorage": storage, "messageStore": "spool",
                 "gatewayConfiguration": public_config,
                 "deployment": {"gatewayHost": "gateway", "harnessHost": "provider", "ingressWindow": deployment["ingressWindow"],
                                "outgoingWindow": deployment["outgoingWindow"], "providerWindow": deployment["providerWindow"],
                                 "database": database.public if database else {"provisioned": False, "reason": "Kannel internal DLR storage"}}}
    for key in ("httpPort", "providerPort", "callbackPort", "providerMaxPending", "providerIoTimeoutMillis",
                "outgoingRequestTimeoutMillis", "callbackThreads", "callbackQueueCapacity"):
        effective["deployment"][key] = deployment[key]
    write(directory / "inputs/effective-configuration.json", effective)
    write(directory / "inputs/run-config.json", run)
    runtime = {"schemaVersion": 1, "benchmarkTarget": "kannel", "runIdentity": identity,
               "readinessUri": "http://gateway:8080/sendsms", "localReadinessFile": "gateway-ready.json",
               "provider": {"host": "0.0.0.0", "port": deployment["providerPort"], "systemId": "provider", "password": passwords[0],
                            "maxConnections": 1, "windowSize": deployment["providerWindow"], "maxPending": deployment["providerMaxPending"],
                            "ioTimeoutMillis": deployment["providerIoTimeoutMillis"]},
               "ingress": {"admissionUri": "http://gateway:8080/sendsms", "username": "smoke", "password": passwords[1],
                           "source": "Benchmark", "destination": "306900000001",
                           "callbackBase": "http://provider:8081/dlr" if run["dlrEnabled"] else None},
               "callbackReceiver": {"host": "0.0.0.0", "port": deployment["callbackPort"], "path": "/dlr",
                                    "threads": deployment["callbackThreads"], "queueCapacity": deployment["callbackQueueCapacity"]}}
    write(directory / "inputs/runtime-settings.json", runtime)
    artifacts = [{"name": name, "sha256": sha} for name, sha in (
        ("kannel-bearerbox", gateway["binarySha256"]["bearerbox"]), ("kannel-smsbox", gateway["binarySha256"]["smsbox"]),
        ("harness", harness["harnessSha256"]), ("sampler", harness["samplerSha256"]),
        ("source-working-tree", source["workingTreeDigest"]), ("preset", effective["basePreset"]["sha256"]),
        ("kannel-source-archive", gateway["source"]["archiveSha256"]), ("kannel-patch-archive", gateway["source"]["patchArchiveSha256"]))]
    infrastructure = {"preset": profile, "region": "local", "sourceContext": source,
                      "kannel": {"execution": "container-c", "imageId": images["gateway"], "architecture": gateway["architecture"],
                                 "cpuLimit": 2, "memoryLimitMiB": 2048, "build": gateway},
                      "harness": {"execution": "container-jvm", "imageId": images["harness"], "architecture": harness["architecture"],
                                  "cpuLimit": 2, "memoryLimitMiB": 2048, "javaVersion": harness["javaVersion"],
                                  "jvmArguments": preset["runtime"]["java"]["harnessArguments"]},
                      "database": effective["deployment"]["database"]}
    infrastructure["resolution"] = {"executionKind": "container-kannel", "localRehearsal": {
        "mode": "local-kannel-benchmark", "limitations": [
            "Local container results are not AWS capacity findings; EC2 CPU credits and instance hardware are not reproduced.",
            "Host CPU/memory describe the shared Docker Linux host or VM; container limits are recorded separately."]}}
    if database:
        infrastructure["resolution"]["localRehearsal"]["limitations"].append(
            "PostgreSQL DLR verification adds read-only row/statistic queries once per second; these are not AWS/RDS utilization measurements.")
    if "controller" in images:
        infrastructure["controller"] = {"execution": "container-python", "imageId": images["controller"]}
    execution = {"schemaVersion": 1, "readinessTimeoutSeconds": measurement["readinessTimeoutSeconds"],
                 "retentionSeconds": measurement["retentionSeconds"], "intervalBudget": measurement["intervalBudget"],
                 "context": {"provenance": {"branch": source.get("branch", "local-working-tree"), "applicationCommit": None,
                                            "harnessRevision": source["baseCommit"], "workflowRunUrl": None, "runIdentity": identity,
                                            "artifacts": artifacts},
                             "application": {"benchmarkTarget": "kannel", "runtime": "kannel", "jvmArguments": [], "connections": {},
                                              "dlrStorage": storage, "messageStore": "spool", "source": gateway["source"]},
                             "infrastructure": infrastructure,
                             "diagnostics": ["gateway.log", "harness.log", "kannel-resources.jsonl", "effective-configuration.json"]},
                 "resources": {"schemaVersion": 1, "gatewaySamples": "kannel-resources.jsonl", "harnessSamples": None,
                               "cpuCredits": None, "rdsMetrics": None, "sampleBudget": measurement["sampleBudgetPerSource"]},
                 "sampling": {"pythonExecutable": "python3", "agentPath": "/opt/probe/sample_process.py",
                              "intervalSeconds": measurement["sampleIntervalSeconds"], "maxSamples": measurement["sampleBudgetPerSource"]}}
    write(directory / "execution.json", execution)
    return settings, effective


def start(name, image, network, alias, volume, subpath, session, command=(), extra_network=None):
    created = smoke.docker(*(("create",) if extra_network else ("run", "--detach")), "--name", name, "--hostname", alias,
                        "--label", "sendium.local.session=" + session, "--label", "sendium.benchmark.target=kannel",
                        "--network", network, *( ("--network-alias", alias) if network != "none" else ()),
                        "--cpus", "2", "--memory", "2g", "--log-driver", "json-file", "--log-opt", "max-size=4m", "--log-opt", "max-file=1",
                        "--mount", f"type=volume,source={volume},target=/probe,volume-subpath={subpath}", image, *command)
    if extra_network:
        smoke.docker("network", "connect", extra_network, name)
        smoke.docker("start", name)
    return created


def state(container):
    return json.loads(smoke.docker("inspect", "--format", "{{json .State}}", container))


def await_listeners(directory, container, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not state(container)["Running"]:
            raise RuntimeError("HARNESS_EXITED_BEFORE_LISTENERS")
        file = directory / "initial/state.json"
        if file.is_file() and read(file, 16384).get("listenersReady"):
            return
        time.sleep(0.1)
    raise RuntimeError("HARNESS_LISTENER_TIMEOUT")


def await_completion(directory, provider, gateway, lifetime, scenario, outcome, database=None):
    deadline = time.monotonic() + lifetime
    measurement_seen = None
    next_database_sample = 0
    while state(provider)["Running"]:
        if not state(gateway)["Running"]:
            outcome["issues"].append("GATEWAY_EXITED_DURING_RUN")
            smoke.docker("stop", "--time", "90", provider, timeout=100)
            break
        current = read(directory / "initial/state.json", 16384)
        if database is not None and scenario != "database-unavailable" and time.monotonic() >= next_database_sample:
            database.observe()
            next_database_sample = time.monotonic() + 1
        if current.get("phase") == "MEASUREMENT":
            measurement_seen = measurement_seen or time.monotonic()
            if scenario in ("app-crash", "interrupted") and not outcome["faultInjected"] and time.monotonic() - measurement_seen >= 1:
                outcome["faultInjected"] = True
                if scenario == "app-crash":
                    smoke.docker("kill", gateway)
                else:
                    smoke.docker("stop", "--time", "90", provider, timeout=100)
        if time.monotonic() >= deadline:
            outcome["issues"].append("EXECUTION_TIMEOUT")
            smoke.docker("stop", "--time", "90", provider, timeout=100)
            break
        time.sleep(0.2)


def export_available(directory, output, budget):
    for name in ("results.json", "summary.md", "state.json", "lifecycle.jsonl", "harness-resources.jsonl", "resource-agent.log"):
        source = directory / "initial" / name
        if source.is_file():
            destination = output / "initial"
            destination.mkdir(exist_ok=True)
            if source.stat().st_size > max(1048576, budget * 16384):
                raise ValueError("PUBLIC_EVIDENCE_BUDGET_EXCEEDED")
            shutil.copyfile(source, destination / name)
    for name in ("kannel-resources.jsonl", "gateway-ready.json"):
        source = directory / name
        if source.is_file():
            if source.stat().st_size > budget * 8192:
                raise ValueError("RESOURCE_EVIDENCE_BUDGET_EXCEEDED")
            shutil.copyfile(source, output / name)


def validate_report(report):
    schemas = {name: read(PERFORMANCE / "contracts" / name) for name in ("run-config.schema.json", "results.schema.json")}
    registry = Registry().with_resources((name, Resource.from_contents(value)) for name, value in schemas.items())
    Draft202012Validator(schemas["results.schema.json"], registry=registry).validate(report)
    validate_results(report)


def run_case(preset, scenario, session, volume, source, gateway, harness, images, index):
    identity = "kannel-" + uuid.uuid4().hex[:16]
    directory = STATE / "private" / identity
    directory.mkdir(parents=True)
    output = STATE / "public" / scenario
    output.mkdir()
    passwords = [secrets.token_hex(4), secrets.token_hex(12), secrets.token_hex(12)]
    prefix = "sendium-local-" + session + "-kannel-" + str(index)
    provider, application, network = prefix + "-provider", prefix + "-gateway", prefix + "-network"
    candidates = []
    database = None
    outcome = {"schemaVersion": 1, "runIdentity": identity, "scenario": scenario, "benchmarkTarget": "kannel", "passed": False,
               "issues": [], "cleanupIssues": [], "faultInjected": False, "images": images, "stage": "PREPARATION"}
    network_created = False
    budget = preset["measurement"]["sampleBudgetPerSource"]
    try:
        database = Database(STATE, identity, prefix, session, volume, images["database"], preset["database"]["poolMaxSize"])
        database.start(directory, PERFORMANCE / "kannel/dlr.sql")
        passwords.append(database.password)
        settings, effective = prepare(directory, preset, scenario, identity, source, gateway, harness, images, passwords, database)
        write(output / "effective-configuration.json", effective)
        outcome["stage"] = "READINESS"
        network_created = True
        smoke.docker("network", "create", "--internal", "--label", "sendium.local.session=" + session, network)
        candidates.append(provider)
        start(provider, images["harness"], network, "provider", volume, "private/" + identity, session,
              ("java", *preset["runtime"]["java"]["harnessArguments"], "-jar", "/opt/probe/harness.jar", "run",
               "/probe/inputs", "/probe/execution.json", "/probe/initial"))
        await_listeners(directory, provider, preset["measurement"]["readinessTimeoutSeconds"])
        if scenario in ("database-unavailable", "bad-tls"):
            outcome["faultInjected"] = True
        if scenario == "database-unavailable":
            database.observe()
            smoke.docker("stop", "--time", "15", database.name)
        candidates.append(application)
        start(application, images["gateway"], network, "gateway", volume, "private/" + identity, session, extra_network=database.network)
        outcome["stage"] = "EXECUTION"
        await_completion(directory, provider, application, settings["lifetimeSeconds"], scenario, outcome, database)
        outcome["harnessExitCode"] = state(provider)["ExitCode"]
        smoke.docker("stop", "--time", str(math.ceil(settings["intervalSeconds"]) + 30), application,
                      timeout=math.ceil(settings["intervalSeconds"]) + 40)
        if scenario != "database-unavailable":
            database.finish()
        write(output / "database-evidence.json", database.evidence())
        export_available(directory, output, budget)
        if not (output / "initial/results.json").is_file():
            raise RuntimeError("HARNESS_REPORT_MISSING")
        write(output / "resource-inputs.json", {"schemaVersion": 1, "gatewaySamples": "kannel-resources.jsonl",
              "harnessSamples": "initial/harness-resources.jsonl", "cpuCredits": None, "rdsMetrics": None, "sampleBudget": budget})
        outcome["stage"] = "FINALIZATION"
        finalizer = prefix + "-finalizer"
        candidates.append(finalizer)
        start(finalizer, images["harness"], "none", "finalizer", volume, "public/" + scenario, session,
              ("java", "-Xmx512m", "-jar", "/opt/probe/harness.jar", "finalize-report", "/probe/initial/results.json",
               "/probe/resource-inputs.json", "/probe/final"))
        finalized = int(smoke.docker("wait", finalizer, timeout=90))
        report = read(output / "final/results.json", 256 * 1024 * 1024)
        initial = read(output / "initial/results.json", 256 * 1024 * 1024)
        for field in ("overall", "cohortIntervals", "eventIntervals", "drain"):
            if report[field] != initial[field]:
                raise RuntimeError("FINALIZATION_CHANGED_WORKLOAD")
        validate_report(report)
        for name in ("results.json", "summary.md"):
            shutil.copyfile(output / "final" / name, output / name)
        outcome.update(stage="COMPLETED", observationStatus=report["validity"]["status"], pipelineExitCode=finalized)
        counts, run = report["overall"]["counts"], preset["workload"]
        expected = int((Decimal(str(run["offeredMessagesPerSecond"])) * run["measurementSeconds"]).to_integral_value(rounding=ROUND_CEILING))
        outcome["checks"] = {"allTargetsOffered": counts["attempted"] == expected and counts["missed"] == 0,
                             "loadQualified": counts["accepted"] == expected and counts["acceptedProviderCompleted"] == expected
                                and (not run["dlrEnabled"] or counts["acceptedReceiptForwarded"] == expected),
                             "loadQualificationBoundary": "cohort completion through bounded drain; not sustained measurement throughput",
                             "resourcesFinalized": report["measurementMetadata"].get("resourcesFinalized", False)}
        negative = scenario in ("app-crash", "interrupted", "database-unavailable", "bad-tls")
        outcome["passed"] = (outcome["faultInjected"] and report["validity"]["status"] == "failed" and finalized == 1) if negative else (
            report["validity"]["status"] != "failed" and finalized == 0 and outcome["harnessExitCode"] == 0 and not outcome["issues"])
        if not negative:
            evidence = database.evidence()
            final = evidence["final"]
            verified = evidence["kannelTlsConnectionObserved"] and final is not None
            if run["dlrEnabled"] and counts["providerResponses"] > 0:
                verified = verified and final["insertedRows"] >= counts["providerResponses"] and final["deletedRows"] >= counts["receiptsForwarded"]
                if report["drain"].get("unfinishedAllPhases") == 0:
                    verified = verified and final["pendingRows"] == 0
            outcome["checks"]["postgresqlDlrStorageVerified"] = bool(verified)
            if not verified:
                outcome["issues"].append("POSTGRESQL_DLR_EVIDENCE_INCOMPLETE")
                outcome["passed"] = False
    except Exception as error:
        outcome["failure"] = failure_code(error)
    finally:
        if provider in candidates:
            try:
                if state(provider)["Running"]:
                    smoke.docker("stop", "--time", "90", provider, timeout=100)
            except Exception:
                outcome["issues"].append("HARNESS_STOP_FAILED")
        smoke.collect_and_remove(candidates, output, passwords, outcome,
                                 {provider: "harness", application: "gateway", prefix + "-finalizer": "finalizer"})
        try:
            export_available(directory, output, budget)
        except (OSError, ValueError):
            outcome["issues"].append("EVIDENCE_EXPORT_FAILED")
        if network_created:
            try:
                smoke.docker("network", "rm", network)
            except Exception:
                outcome["cleanupIssues"].append("NETWORK_CLEANUP_FAILED")
        if outcome["cleanupIssues"] or outcome.get("failure"):
            outcome["passed"] = False
        expected_issues = {"GATEWAY_EXITED_DURING_RUN", "Gateway supervision or resource sampling exited unsuccessfully"} if scenario in ("app-crash", "database-unavailable", "bad-tls") else set()
        if set(outcome["issues"]) - expected_issues:
            outcome["passed"] = False
        # Export only allowlisted public files; reject any known private fixture value.
        for path in output.rglob("*"):
            if path.is_file():
                body = path.read_bytes()
                redacted = body
                for password in passwords:
                    redacted = redacted.replace(password.encode(), b"[REDACTED]")
                if redacted != body:
                    path.write_bytes(redacted)
                    outcome["passed"] = False
                    outcome["issues"].append("PRIVATE_VALUE_REJECTED_FROM_EXPORT")
        if not candidates:
            try:
                shutil.rmtree(directory)
            except OSError:
                outcome["cleanupIssues"].append("PRIVATE_DIRECTORY_CLEANUP_FAILED")
                outcome["passed"] = False
        try:
            if database is not None:
                database.close(output, outcome["cleanupIssues"])
        except Exception:
            outcome["cleanupIssues"].append("DATABASE_CLEANUP_FAILED")
        if outcome["cleanupIssues"]:
            outcome["passed"] = False
        write(output / "deployment-outcome.json", outcome)
    return outcome


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenarios", default="load")
    parser.add_argument("--offered-rate", type=float)
    parser.add_argument("--dlr-storage", choices=("postgresql",), required=True)
    add_workload_arguments(parser)
    arguments = parser.parse_args()
    session, volume = os.environ["LOCAL_SESSION"], os.environ["LOCAL_STATE_VOLUME"]
    if not re.fullmatch(r"[0-9]+-[a-f0-9]{6}", session) or volume != "sendium-local-state-" + session:
        raise ValueError("Invalid owned local session")
    (STATE / "public").mkdir(exist_ok=True)
    def interrupted(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    result = {"schemaVersion": 1, "mode": "local-kannel-benchmark", "benchmarkTarget": "kannel", "cases": [], "exitCode": 1}
    try:
        source = read("/opt/probe/source-context.json")
        images = {role: os.environ["KANNEL_" + role.upper() + "_IMAGE"] for role in ("gateway", "harness", "database")}
        images["controller"] = os.environ["KANNEL_CONTROL_IMAGE"]
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", images["controller"]):
            raise ValueError("EXACT_CONTROLLER_IMAGE_REQUIRED")
        gateway, harness = image_metadata(images["gateway"]), image_metadata(images["harness"])
        validate_images(source, gateway, harness, smoke.docker("info", "--format", "{{.Architecture}}"))
        preset = read(PERFORMANCE / "presets/aws-private-jvm-v1.json")
        for index, scenario in enumerate(select(arguments.scenarios, "kannel"), 1):
            case = run_case(workload(preset, arguments, scenario), scenario, session, volume, source, gateway, harness, images, index)
            result["cases"].append(case)
            print(("PASS " if case["passed"] else "FAIL ") + scenario, flush=True)
        result["exitCode"] = 0 if all(case["passed"] for case in result["cases"]) else 1
    except (Exception, KeyboardInterrupt) as error:
        result["failure"] = failure_code(error)
    finally:
        write(STATE / "public/rehearsal.json", result)
    return result["exitCode"]


if __name__ == "__main__":
    raise SystemExit(main())
