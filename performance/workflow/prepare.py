"""Unprivileged dispatch validation and build-artifact identity/packaging helpers."""

import argparse
from decimal import Decimal, InvalidOperation
import hashlib
import html
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "contracts"))
from jsonschema import Draft202012Validator, ValidationError
from preset_validation import validate_preset


INPUT_FIELDS = {
    "offered_rate": "offeredMessagesPerSecond", "worker_tps": "workerTps",
    "warmup_seconds": "warmupSeconds", "measurement_seconds": "measurementSeconds", "drain_seconds": "drainTimeoutSeconds",
    "provider_response_delay_ms": "providerResponseDelayMillis", "provider_dlr_delay_ms": "providerDlrDelayMillis"}
ARCHITECTURES = {"x86_64", "arm64"}
FILES = {"application": "application.tar.gz", "harness": "harness.jar"}
MAX_ARTIFACT_BYTES = 1024 ** 3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    require(Path(path).stat().st_size <= 1024 * 1024, "Preparation metadata exceeds size budget")
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, document):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(document, stream, indent=2, allow_nan=False)
        stream.write("\n")


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def number(value, integral):
    require(not isinstance(value, bool), "Boolean is not a numeric workload setting")
    try:
        result = Decimal(str(value))
    except InvalidOperation:
        raise ValueError("Invalid numeric workload setting") from None
    require(result.is_finite() and 0 <= result <= (2147483647 if integral else 1000000000), "Numeric setting is outside runtime bounds")
    require(not integral or result == result.to_integral_value(), "Whole number required for durations, delays and worker TPS")
    return int(result) if integral else float(result)


def boolean(value):
    if type(value) is bool:
        return value
    require(value in ("true", "false"), "Delivery reports must be true or false")
    return value == "true"


def normalize(inputs, context, preset):
    allowed = set(INPUT_FIELDS) | {"ingress_mode", "sendium_instance_type", "harness_instance_type", "dlr_enabled"}
    require(isinstance(inputs, dict) and set(inputs) <= allowed, "Unknown dispatch input")
    result = json.loads(json.dumps(preset))
    workload = result["workload"]
    for name, field in INPUT_FIELDS.items():
        workload[field] = number(inputs.get(name, workload[field]), integral=name != "offered_rate")
    workload["ingressMode"] = inputs.get("ingress_mode", workload["ingressMode"])
    workload["dlrEnabled"] = boolean(inputs.get("dlr_enabled", workload["dlrEnabled"]))
    for source in ("sendium", "harness"):
        instance_type = inputs.get(source + "_instance_type", result["aws"]["instanceDefaults"][source])
        require(isinstance(instance_type, str) and re.fullmatch(r"[a-z0-9][a-z0-9-]*\.[a-z0-9]+", instance_type), "Invalid EC2 instance type")
        result["aws"]["instanceDefaults"][source] = instance_type
    validate_preset(result, Draft202012Validator(read(HERE.parent / "contracts/run-config.schema.json")))
    require(math.isfinite(workload["offeredMessagesPerSecond"]), "Non-finite offered rate")
    for key in ("applicationCommit", "controllerRevision", "workflowRevision"):
        require(isinstance(context.get(key), str) and re.fullmatch(r"[a-f0-9]{40}", context[key]), "Exact commit SHA required")
    require(isinstance(context.get("ref"), str) and context["ref"].startswith("refs/heads/") and len(context["ref"]) > 11
            and not any(ord(character) < 32 for character in context["ref"]), "Select an application branch using Run workflow")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", context["repository"]), "Invalid repository")
    for key in ("runId", "runAttempt"):
        require(re.fullmatch(r"[1-9][0-9]*", str(context[key])), "Invalid workflow run identity")
    identity = "gh-" + str(context["runId"]) + "-" + str(context["runAttempt"])
    require(len(identity) <= 27, "Run identity exceeds IAM name budget")
    return {"schemaVersion": 1, "runIdentity": identity, "repository": context["repository"], "applicationRef": context["ref"],
            "controllerRevision": context["controllerRevision"], "workflowRevision": context["workflowRevision"],
            "provenance": {"branch": context["ref"][11:], "applicationCommit": context["applicationCommit"],
                           "harnessRevision": context["controllerRevision"], "workflowRunUrl": context["workflowRunUrl"], "runIdentity": identity},
            "preset": result}


def revision(request, component):
    return request["provenance"]["applicationCommit" if component == "application" else "harnessRevision"]


def machine_architecture(machine=None):
    return {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "arm64", "arm64": "arm64"}.get((machine or platform.machine()).lower())


def archive_application(directory, target):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink() and (directory / "quarkus-run.jar").is_file(), "Complete quarkus-app directory required")
    entries = sorted(directory.rglob("*"))
    require(len(entries) <= 100000 and all(not path.is_symlink() and (path.is_file() or path.is_dir()) for path in entries),
            "Application bundle contains unsupported filesystem entries")
    require(sum(path.stat().st_size for path in entries if path.is_file()) <= 4 * MAX_ARTIFACT_BYTES, "Expanded application exceeds disk budget")
    with tarfile.open(target, "w:gz") as archive:
        archive.add(directory, arcname="quarkus-app", recursive=False)
        for path in entries:
            archive.add(path, arcname="quarkus-app/" + path.relative_to(directory).as_posix(), recursive=False)


def build_bundle(request, component, architecture, checkout, output, java_version, machine=None):
    require(component in FILES and architecture in ARCHITECTURES, "Unknown build target")
    observed = machine_architecture(machine)
    require(observed == architecture, "Build runner architecture mismatch")
    require(re.fullmatch(r"25(?:[.+-].*)?", java_version), "Java 25 build required")
    checkout, output = Path(checkout), Path(output)
    head = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], check=True, capture_output=True, text=True, timeout=30).stdout.strip()
    require(head == revision(request, component), "Build checkout does not match captured revision")
    output.mkdir(exist_ok=False)
    target = output / FILES[component]
    if component == "application":
        archive_application(checkout / "sendium-app/target/quarkus-app", target)
    else:
        candidates = [path for path in (checkout / "performance/harness/target").glob("sendium-performance-harness-*.jar")
                      if not path.name.endswith(("-shaded.jar", "-sources.jar", "-javadoc.jar", "-tests.jar"))]
        require(len(candidates) == 1 and candidates[0].is_file() and not candidates[0].is_symlink(), "Expected one packaged harness JAR")
        shutil.copyfile(candidates[0], target)
    require(0 < target.stat().st_size <= MAX_ARTIFACT_BYTES, "Build artifact exceeds transport budget")
    manifest = {"schemaVersion": 1, "runIdentity": request["runIdentity"], "component": component,
                "sourceRevision": head, "architecture": architecture, "javaVersion": java_version,
                "file": target.name, "sha256": digest(target), "size": target.stat().st_size}
    write(output / "build-manifest.json", manifest)
    return manifest


def verify_bundle(request, directory, component, architecture):
    require(component in FILES and architecture in ARCHITECTURES, "Unknown build target")
    relative = component + "-" + architecture
    bundle = Path(directory) / relative
    require(bundle.is_dir() and not bundle.is_symlink() and not (bundle / "build-manifest.json").is_symlink(), "Invalid artifact directory")
    manifest = read(bundle / "build-manifest.json")
    require(isinstance(manifest, dict) and set(manifest) == {"schemaVersion", "runIdentity", "component", "sourceRevision",
            "architecture", "javaVersion", "file", "sha256", "size"}, "Build manifest fields mismatch")
    require(manifest.get("schemaVersion") == 1 and manifest.get("runIdentity") == request["runIdentity"]
            and manifest.get("component") == component and manifest.get("architecture") == architecture
            and manifest.get("sourceRevision") == revision(request, component)
            and manifest.get("file") == FILES[component], "Build manifest identity mismatch")
    target = bundle / FILES[component]
    require(target.is_file() and not target.is_symlink() and 0 < target.stat().st_size <= MAX_ARTIFACT_BYTES
            and type(manifest["size"]) is int and target.stat().st_size == manifest["size"]
            and digest(target) == manifest["sha256"], "Build artifact size/digest mismatch")
    require(re.fullmatch(r"25(?:[.+-].*)?", manifest["javaVersion"]), "Build Java version mismatch")
    return {**manifest, "path": relative + "/" + target.name}


def verify_bundles(request, directory):
    artifacts = [verify_bundle(request, directory, component, architecture) for component in FILES for architecture in sorted(ARCHITECTURES)]
    return {"schemaVersion": 1, "runIdentity": request["runIdentity"], "provenance": request["provenance"],
            "controllerRevision": request["controllerRevision"], "artifacts": artifacts}


def summary(request, path, ready=False):
    provenance = request["provenance"]
    lines = ["## Performance test build preparation", "", "| Setting | Value |", "|---|---|"]
    fields = {"Run": request["runIdentity"], "Application branch": provenance["branch"],
              "Application commit": provenance["applicationCommit"], "Harness/controller revision": request["controllerRevision"],
              "Workflow revision": request["workflowRevision"], **request["preset"]["aws"]["instanceDefaults"], **request["preset"]["workload"]}
    for key, value in fields.items():
        lines.append("| " + html.escape(key) + " | <code>" + html.escape(str(value)).replace("|", "&#124;") + "</code> |")
    if ready:
        lines.extend(["", "Both Linux architectures are built and their input digests verified.",
                      "Verified inputs are ready for protected AWS resolution and execution."])
    with Path(path).open("a", encoding="utf-8") as stream:
        stream.write("\n".join(lines) + "\n")


def read_inputs():
    return json.loads(os.environ["DISPATCH_INPUTS_JSON"])


def github_context(controller_revision):
    return {"ref": os.environ["GITHUB_REF"], "repository": os.environ["GITHUB_REPOSITORY"],
            "applicationCommit": os.environ["GITHUB_SHA"], "controllerRevision": controller_revision,
            "workflowRevision": os.environ["GITHUB_WORKFLOW_SHA"], "runId": os.environ["GITHUB_RUN_ID"],
            "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
            "workflowRunUrl": os.environ["GITHUB_SERVER_URL"] + "/" + os.environ["GITHUB_REPOSITORY"] + "/actions/runs/" + os.environ["GITHUB_RUN_ID"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    normalize_command = commands.add_parser("normalize")
    normalize_command.add_argument("--output", required=True)
    bundle = commands.add_parser("bundle")
    bundle.add_argument("--request", required=True)
    bundle.add_argument("--component", choices=FILES, required=True)
    bundle.add_argument("--architecture", choices=sorted(ARCHITECTURES), required=True)
    bundle.add_argument("--checkout", required=True)
    bundle.add_argument("--output", required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--request", required=True)
    verify.add_argument("--directory", required=True)
    verify.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if args.command == "normalize":
            request = normalize(read_inputs(), github_context(os.environ["CONTROLLER_REVISION"]), read(HERE.parent / "presets/aws-private-jvm-v1.json"))
            write(args.output, request)
            with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
                stream.write("controller_revision=" + request["controllerRevision"] + "\nrun_identity=" + request["runIdentity"] + "\n")
            summary(request, os.environ["GITHUB_STEP_SUMMARY"])
        elif args.command == "bundle":
            build_bundle(read(args.request), args.component, args.architecture, args.checkout, args.output, os.environ["BUILD_JAVA_VERSION"])
        else:
            request = read(args.request)
            write(args.output, verify_bundles(request, args.directory))
            summary(request, os.environ["GITHUB_STEP_SUMMARY"], ready=True)
        return 0
    except (OSError, ValueError, KeyError, TypeError, ValidationError, subprocess.SubprocessError):
        print("PERFORMANCE_PREPARATION_FAILED: " + args.command, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
