"""Unprivileged evidence finalization, validation and publication; never AWS API access."""

import argparse
from copy import deepcopy
import html
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import prepare
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from semantics import validate_results


HERE = Path(__file__).resolve().parent
IMMUTABLE = ("provenance", "configuration", "overall", "cohortIntervals", "eventIntervals", "drain")
PRIVILEGED_ENVIRONMENT = ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE",
                          "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
                          "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
                          "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", "ACTIONS_ID_TOKEN_REQUEST_TOKEN")


def read(path):
    path = Path(path)
    prepare.require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 64 * 1024 * 1024, "Invalid report/evidence file")
    def reject_constant(_):
        raise ValueError("Non-finite report value")
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_constant)


def validator():
    schemas = {name: prepare.read(HERE.parent / "contracts" / name) for name in ("results.schema.json", "run-config.schema.json")}
    registry = Registry().with_resources((name, Resource.from_contents(schema)) for name, schema in schemas.items())
    return Draft202012Validator(schemas["results.schema.json"], registry=registry)


def validate(document, request):
    validator().validate(document)
    validate_results(document)
    for key in ("runIdentity", "applicationCommit", "harnessRevision", "branch"):
        prepare.require(document["provenance"][key] == request["provenance"][key], "Published report provenance mismatch")
    prepare.require(document["configuration"]["workload"] == request["preset"]["workload"], "Published workload mismatch")


def unavailable(request, code):
    definitions = prepare.read(HERE.parent / "contracts/results.schema.json")["$defs"]
    fields = definitions["measurements"]["properties"]
    overall = {"durationSeconds": 0, "counts": {name: 0 for name in fields["counts"]["required"]},
               "rates": {name: None for name in fields["rates"]["required"]},
               "latencies": {name: {key: 0 if key == "count" else None for key in definitions["distribution"]["required"]}
                             for name in fields["latencies"]["required"]}}
    preset = request["preset"]
    return {"schemaVersion": 1, "provenance": {**request["provenance"], "artifacts": []},
            "configuration": {"workload": preset["workload"],
                "application": {"jvmArguments": preset["runtime"]["java"]["sendiumArguments"], "connections": {}, "dlrStorage": "postgresql"},
                "infrastructure": {"preset": preset["id"], "region": "unresolved",
                    "sendium": {"requestedInstanceType": preset["aws"]["instanceDefaults"]["sendium"]},
                    "harness": {"requestedInstanceType": preset["aws"]["instanceDefaults"]["harness"]},
                    "database": {"requestedInstanceType": preset["database"]["instanceType"]}}},
            "validity": {"status": "failed", "exitCode": 1, "reasons": [{"code": code, "severity": "FAILED", "scope": "workload",
                "message": "Validated measurement evidence is unavailable; this is a setup/publication failure, not a zero-throughput observation."}]},
            "overall": overall, "cohortIntervals": [], "eventIntervals": [],
            "resources": {"sendium": [], "harness": [], "cpuCredits": []},
            "drain": {"durationSeconds": 0, "timeoutReached": False, "unfinishedAccepted": 0, "pendingAdmission": 0},
            "measurementMetadata": {"histogramAlgorithm": "unavailable", "histogramPrecision": {}, "histogramOverflowCount": 0,
                "duplicateDetectionHorizonSeconds": 0, "observationAvailable": False,
                "limitations": ["Zero counters represent absent evidence; no measured rates, latency percentiles or resource values are claimed."]},
            "diagnostics": []}


def first_file(paths):
    return next((path for path in paths if path.is_file() and not path.is_symlink()), None)


def immutable(before, after):
    prepare.require(all(before[key] == after[key] for key in IMMUTABLE), "Finalization changed workload observations")
    metadata = {key: value for key, value in after["measurementMetadata"].items() if key != "resourcesFinalized"}
    prepare.require(metadata == {key: value for key, value in before["measurementMetadata"].items() if key != "resourcesFinalized"},
                    "Finalization changed measurement metadata")
    retained = [reason for reason in before["validity"]["reasons"] if reason.get("scope") != "resources"]
    prepare.require(retained == [reason for reason in after["validity"]["reasons"] if reason.get("scope") != "resources"],
                    "Finalization changed workload reasons")
    prepare.require(not any(reason.get("severity") == "FAILED" for reason in retained) or after["validity"]["status"] == "failed",
                    "Finalization downgraded a workload failure")


def finalize(request, original, builds, inputs, work, resources, java="java"):
    prepare.require(not any(os.environ.get(key) for key in PRIVILEGED_ENVIRONMENT), "Finalization requires an unprivileged job")
    manifest = prepare.verify_bundle(request, builds, "harness", "x86_64")
    prepare.require(inputs["runIdentity"] == request["runIdentity"] and manifest in inputs["artifacts"], "Finalizer build identity mismatch")
    prepare.write(work / "initial-results.json", original)
    prepare.write(work / "resource-inputs.json", resources)
    completed = subprocess.run([java, "-jar", str(Path(builds) / manifest["path"]), "finalize-report", str(work / "initial-results.json"),
                                str(work / "resource-inputs.json"), str(work / "finalized")], capture_output=True, timeout=120)
    prepare.require(completed.returncode in (0, 1), "Report finalization failed")
    result = read(work / "finalized/results.json")
    validate(result, request)
    immutable(original, result)
    prepare.require(completed.returncode == result["validity"].get("exitCode"), "Finalizer exit status mismatch")
    return result, (work / "finalized/summary.md").read_text(encoding="utf-8")


def fallback_markdown(report):
    def text(value):
        return html.escape(str(value)).replace("|", "&#124;").replace("`", "'").replace("\n", " ")
    def number(value):
        return "n/a" if value is None else format(value, ".3f")
    lines = ["# Sendium performance results", "", "**Status:** " + report["validity"]["status"], "",
             "Run: <code>" + text(report["provenance"]["runIdentity"]) + "</code>",
             "Branch: <code>" + text(report["provenance"]["branch"]) + "</code>",
             "Application: <code>" + report["provenance"]["applicationCommit"] + "</code>; harness: <code>" + report["provenance"]["harnessRevision"] + "</code>", ""]
    for reason in report["validity"]["reasons"]:
        lines.append("- **" + text(reason["code"]) + ":** " + text(reason["message"]))
    if report["measurementMetadata"].get("observationAvailable") is False:
        return "\n".join(lines + ["", "## Observation unavailable", "", "Measured throughput, latency percentiles and resource utilization: **n/a**.",
                                 "No overall or one-minute performance findings can be inferred from this failure report."]) + "\n"
    lines += ["", "## Overall", "", "| Metric | Value |", "|---|---:|"]
    for name, value in {**report["overall"]["counts"], **report["overall"]["rates"]}.items():
        lines.append("| " + text(name) + " | " + number(value) + " |")
    def latencies(title, measurements):
        lines.extend(["", "### " + title, "", "| Boundary | Count | p50 ms | p95 ms | p99 ms |", "|---|---:|---:|---:|---:|"])
        for name, value in measurements["latencies"].items():
            lines.append("| " + text(name) + " | " + str(value["count"]) + " | " + " | ".join(number(value[key]) for key in ("p50Millis", "p95Millis", "p99Millis")) + " |")
    latencies("Overall latency", report["overall"])
    lines += ["", "## One-minute submission cohorts", "", "| Start s | Observed s | Attempted | Accepted | Awaiting provider | Awaiting receipt |", "|---:|---:|---:|---:|---:|---:|"]
    for cohort in report["cohortIntervals"]:
        lines.append("| " + " | ".join(number(value) for value in (cohort["startOffsetSeconds"], cohort["durationSeconds"],
            *(cohort["counts"][key] for key in ("attempted", "accepted", "acceptedAwaitingProvider", "acceptedAwaitingReceipt")))) + " |")
    for cohort in report["cohortIntervals"]:
        latencies("Cohort " + str(cohort["startOffsetSeconds"]) + "s", cohort)
    lines += ["", "## One-minute event-time output", "", "| Phase | Start s | Duration s | Provider responses/s | Receipts/s |", "|---|---:|---:|---:|---:|"]
    for interval in report["eventIntervals"]:
        lines.append("| " + text(interval["phase"]) + " | " + " | ".join(number(value) for value in (interval["startOffsetSeconds"],
            interval["durationSeconds"], interval.get("rates", {}).get("providerResponsesPerSecond"), interval.get("rates", {}).get("receiptsForwardedPerSecond"))) + " |")
    lines.extend(["", "## Resources", "", "Available raw samples and resource summaries are in results.json; absent evidence is not zero utilization.",
                  "", "## Drain", "", "Duration seconds: " + number(report["drain"]["durationSeconds"]) + "; unfinished accepted: " + str(report["drain"]["unfinishedAccepted"])])
    return "\n".join(lines) + "\n"


def copy_evidence(path, target, issues, maximum=64 * 1024 * 1024):
    if path is None:
        return None
    try:
        prepare.require(path.stat().st_size <= maximum, "Evidence exceeds size budget")
        shutil.copyfile(path, target)
        return str(target.resolve())
    except (OSError, ValueError):
        try:
            target.unlink(missing_ok=True)
        except OSError:
            pass
        issues.append({"code": "EVIDENCE_UNAVAILABLE_OR_OVERSIZED", "source": target.name})
        return None


def publish(request, controller, diagnostic, cleanup, builds, inputs, outcomes, directory, java="java"):
    directory = Path(directory)
    directory.mkdir(exist_ok=False)
    work, evidence, logs = directory / "_work", directory / "evidence", directory / "diagnostics"
    for path in (work, evidence, logs):
        path.mkdir()
    roots = [Path(diagnostic) / "retrieved", Path(controller) / "execution/retrieved", Path(controller) / "retrieved"]
    issues, evidence_issues, original = [], [], None
    for path in (root / "harness/results.json" for root in roots):
        if path.is_file():
            try:
                original = read(path)
                validate(original, request)
                break
            except Exception:
                original = None
                issues.append("REPORT_REJECTED")
    resources = {"schemaVersion": 1, "sendiumSamples": None, "harnessSamples": None, "cpuCredits": None,
                 "sampleBudget": request["preset"]["measurement"]["sampleBudgetPerSource"]}
    for source in ("sendium", "harness"):
        filename = source + "-resources.jsonl"
        path = first_file([root / source / filename for root in roots])
        resources[source + "Samples"] = copy_evidence(path, evidence / filename, evidence_issues)
    credit = first_file([Path(diagnostic) / "cpu-credits.json"])
    resources["cpuCredits"] = copy_evidence(credit, evidence / "cpu-credits.json", evidence_issues)
    for base, names in ((Path(controller), ("controller-outcome.json", "stack-events.json", "resolution.json")),
                        (Path(diagnostic), ("diagnostics-outcome.json",)), (Path(cleanup), ("cleanup-outcome.json",))):
        for name in names:
            path = first_file([base / name])
            copy_evidence(path, logs / name, evidence_issues, 16 * 1024 * 1024)
    for source in ("sendium", "harness"):
        for name in ("process.log", "node-status.json", "state.json", "lifecycle.jsonl"):
            path = first_file([root / source / name for root in roots])
            copy_evidence(path, logs / (source + "-" + name), evidence_issues, 16 * 1024 * 1024)
    if original is None:
        report = unavailable(request, "REPORT_UNAVAILABLE_OR_INVALID")
        markdown = fallback_markdown(report)
        issues.append("NO_VALID_MEASUREMENT_REPORT")
    else:
        try:
            report, markdown = finalize(request, original, builds, inputs, work, resources, java)
        except Exception:
            report = deepcopy(original)
            report["validity"]["reasons"].append({"code": "RESOURCE_FINALIZATION_UNAVAILABLE", "scope": "resources", "severity": "INCONCLUSIVE",
                "message": "Resource finalization failed; workload observations are preserved without claiming complete resource evidence."})
            if report["validity"]["status"] != "failed":
                report["validity"].update(status="inconclusive", exitCode=0)
            markdown = fallback_markdown(report)
            issues.append("RESOURCE_FINALIZATION_FAILED")
    expected_cleanup = outcomes.get("aws-execution") not in (None, "skipped")
    cleanup_ok = not expected_cleanup
    cleanup_path = first_file([Path(cleanup) / "cleanup-outcome.json"])
    if cleanup_path:
        try:
            state = read(cleanup_path)
            cleanup_ok = state.get("schemaVersion") == 1 and state["runIdentity"] == request["runIdentity"] \
                and type(state["exitCode"]) is int and state["exitCode"] == 0 \
                and state["stackDeleted"] is True and state["artifactsDeleted"] is True and state.get("issues") == []
        except Exception:
            cleanup_ok = False
    if not cleanup_ok:
        issues.append("CLEANUP_UNCONFIRMED")
    failed_jobs = [name for name, status in outcomes.items() if status in ("failure", "cancelled")]
    exit_code = 1 if report["validity"]["status"] == "failed" or issues or failed_jobs else 0
    workflow = {"schemaVersion": 1, "runIdentity": request["runIdentity"], "observationStatus": report["validity"]["status"],
                "jobOutcomes": outcomes, "cleanupRequired": expected_cleanup, "cleanupConfirmed": cleanup_ok if expected_cleanup else None,
                "issues": issues, "evidenceIssues": evidence_issues, "exitCode": exit_code}
    prepare.write(directory / "workflow-outcome.json", workflow)
    report["diagnostics"] = ["workflow-outcome.json"] + sorted(path.relative_to(directory).as_posix() for base in (logs, evidence) for path in base.iterdir())
    validate(report, request)
    prepare.write(directory / "results.json", report)
    cleanup_label = ("confirmed" if cleanup_ok else "unconfirmed") if expected_cleanup else "not required"
    markdown += "\n## Workflow / lifecycle outcome\n\nPipeline: **" + ("failed" if exit_code else "completed") + "**; cleanup: **" + cleanup_label + "**.\n\n"
    markdown += "\n".join("- " + html.escape(code) for code in issues) + "\n"
    markdown += "\n".join("- Evidence unavailable: " + html.escape(issue["source"]) for issue in evidence_issues) + "\n"
    (directory / "summary.md").write_text(markdown, encoding="utf-8")
    return workflow


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("request", "controller", "diagnostic", "cleanup", "builds", "build-inputs", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        request = prepare.read(args.request)
        expected = prepare.normalize(prepare.read_inputs(), prepare.github_context(os.environ["CONTROLLER_REVISION"]),
                                     prepare.read(HERE.parent / "presets/aws-private-jvm-v1.json"))
        prepare.require(request == expected, "Publication request differs from dispatch")
        outcomes = {name: info["result"] for name, info in json.loads(os.environ["WORKFLOW_OUTCOMES_JSON"]).items()}
        prepare.require(all(value in ("success", "failure", "cancelled", "skipped") for value in outcomes.values()), "Invalid workflow outcomes")
        inputs = prepare.read(args.build_inputs) if Path(args.build_inputs).is_file() else {}
        result = publish(request, args.controller, args.diagnostic, args.cleanup, args.builds, inputs, outcomes, args.output)
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as stream:
            stream.write((Path(args.output) / "summary.md").read_text(encoding="utf-8"))
        return result["exitCode"]
    except Exception:
        print("PERFORMANCE_PUBLICATION_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
