"""Validate contract schemas and boundary examples (requires jsonschema)."""

import copy
import argparse
import json
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from semantics import validate_results
from preset_validation import validate_preset


ROOT = Path(__file__).resolve().parent
schemas = {
    name: json.loads((ROOT / name).read_text(encoding="utf-8"))
    for name in ("run-config.schema.json", "results.schema.json")
}
for schema in schemas.values():
    Draft202012Validator.check_schema(schema)
registry = Registry().with_resources(
    (name, Resource.from_contents(schema)) for name, schema in schemas.items()
)
config_validator = Draft202012Validator(schemas["run-config.schema.json"])
result_validator = Draft202012Validator(
    schemas["results.schema.json"], registry=registry
)

config = {
    "schemaVersion": 1,
    "ingressMode": "http",
    "offeredMessagesPerSecond": 100,
    "workerTps": 100,
    "warmupSeconds": 120,
    "measurementSeconds": 600,
    "drainTimeoutSeconds": 120,
    "dlrEnabled": True,
    "providerResponseDelayMillis": 0,
    "providerDlrDelayMillis": 1000,
    "admissionTimeoutMillis": 10000,
    "maxOutstandingRequests": 1000,
    "maxTrackedMessages": 10000,
}
config_validator.validate(config)
for field, value in (
    ("measurementSeconds", 0),
    ("offeredMessagesPerSecond", 0),
    ("maxTrackedMessages", -1),
    ("dlrEnabled", "false"),
    ("ingressMode", "smtp"),
):
    invalid = dict(config, **{field: value})
    assert not config_validator.is_valid(invalid), field

definitions = schemas["results.schema.json"]["$defs"]
measurements = {
    "durationSeconds": 0,
    "counts": {key: 0 for key in definitions["measurements"]["properties"]["counts"]["required"]},
    "rates": {key: None for key in definitions["measurements"]["properties"]["rates"]["required"]},
    "latencies": {
        key: {field: 0 if field == "count" else None for field in definitions["distribution"]["required"]}
        for key in definitions["measurements"]["properties"]["latencies"]["required"]
    },
}
result = {
    "schemaVersion": 1,
    "provenance": {"branch": "test", "applicationCommit": "a" * 40, "harnessRevision": "b" * 40, "workflowRunUrl": None, "runIdentity": "local-test", "artifacts": []},
    "configuration": {"workload": config, "application": {"jvmArguments": [], "connections": {}, "dlrStorage": "postgresql"}, "infrastructure": {"preset": "test", "region": "test", "sendium": {}, "harness": {}, "database": {}}},
    "validity": {"status": "failed", "reasons": [{"code": "SETUP_FAILED", "message": "Readiness failed before measurement"}]},
    "overall": measurements,
    "cohortIntervals": [],
    "eventIntervals": [],
    "resources": {"sendium": [], "harness": [], "cpuCredits": []},
    "drain": {"durationSeconds": 0, "timeoutReached": False, "unfinishedAccepted": 0, "pendingAdmission": 0},
    "measurementMetadata": {"histogramAlgorithm": "not-started", "histogramPrecision": {}, "histogramOverflowCount": 0, "duplicateDetectionHorizonSeconds": 0, "limitations": []},
    "diagnostics": [],
}
result_validator.validate(result)
invalid = copy.deepcopy(result)
invalid["configuration"]["workload"]["measurementSeconds"] = 0
assert not result_validator.is_valid(invalid), "External workload schema must resolve"
invalid = copy.deepcopy(result)
invalid["overall"]["counts"]["accepted"] = -1
assert not result_validator.is_valid(invalid), "Counts must reject negative values"
invalid = copy.deepcopy(result)
invalid["overall"]["latencies"]["acknowledgement"]["p99Millis"] = -1
assert not result_validator.is_valid(invalid), "Latency must reject negative values"
print("Both schemas valid; resolved workload, setup-failure output, and invalid boundaries verified.")

parser = argparse.ArgumentParser(description="Validate performance contracts and generated results")
parser.add_argument("--results-directory", type=Path)
parser.add_argument("--preset", type=Path, action="append", default=[])
arguments = parser.parse_args()
for path in arguments.preset:
    validate_preset(json.loads(path.read_text(encoding="utf-8")), config_validator)
    print(f"Validated preset compatibility and lifecycle budgets: {path.name}")
if arguments.results_directory is not None:
    results = sorted(arguments.results_directory.rglob("results.json"))
    if not results:
        raise SystemExit("No generated results.json files found")
    for path in results:
        document = json.loads(path.read_text(encoding="utf-8"))
        result_validator.validate(document)
        try:
            validate_results(document)
        except ValueError as exception:
            raise ValueError(f"{path}: {exception}") from exception
    print(f"Validated {len(results)} generated reports against the version 1 result schema and accounting/window semantics.")
