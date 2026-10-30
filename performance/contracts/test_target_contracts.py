"""Target identity must survive reporting without relaxing existing Sendium contracts."""

import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parent
SCHEMAS = {name: json.loads((ROOT / name).read_text(encoding="utf-8"))
           for name in ("run-config.schema.json", "results.schema.json")}
REGISTRY = Registry().with_resources((name, Resource.from_contents(schema)) for name, schema in SCHEMAS.items())
VALIDATOR = Draft202012Validator(SCHEMAS["results.schema.json"], registry=REGISTRY)


def fixture(target="sendium"):
    definitions = SCHEMAS["results.schema.json"]["$defs"]
    result = {
        "schemaVersion": 1,
        "provenance": {"branch": "fixture", "applicationCommit": "a" * 40, "harnessRevision": "b" * 40,
                       "workflowRunUrl": None, "runIdentity": "fixture", "artifacts": []},
        "configuration": {
            "workload": json.loads((ROOT.parent / "harness/src/test/resources/run-config.json").read_text()),
            "application": {"jvmArguments": [], "connections": {}, "dlrStorage": "postgresql"},
            "infrastructure": {"preset": "fixture", "region": "local", "sendium": {}, "harness": {}, "database": {}},
        },
        "validity": {"status": "failed", "reasons": [{"code": "SETUP_FAILED", "message": "Fixture"}]},
        "overall": {
            "durationSeconds": 0,
            "counts": dict.fromkeys(definitions["measurements"]["properties"]["counts"]["required"], 0),
            "rates": dict.fromkeys(definitions["measurements"]["properties"]["rates"]["required"]),
            "latencies": {name: {key: 0 if key == "count" else None for key in definitions["distribution"]["required"]}
                          for name in definitions["measurements"]["properties"]["latencies"]["required"]},
        },
        "cohortIntervals": [], "eventIntervals": [],
        "resources": {"sendium": [], "harness": [], "cpuCredits": []},
        "drain": {"durationSeconds": 0, "timeoutReached": False, "unfinishedAccepted": 0, "pendingAdmission": 0},
        "measurementMetadata": {"histogramAlgorithm": "not-started", "histogramPrecision": {},
                                "histogramOverflowCount": 0, "duplicateDetectionHorizonSeconds": 0, "limitations": []},
        "diagnostics": [],
    }
    if target == "kannel":
        result["provenance"]["applicationCommit"] = None
        result["configuration"]["application"].update(
            benchmarkTarget="kannel", runtime="kannel", dlrStorage="internal", messageStore="spool",
            source={"version": "1.4.5", "archiveSha256": "c" * 64,
                    "patchArchiveSha256": "d" * 64, "patches": ["build-fix.patch"]})
        result["configuration"]["infrastructure"]["kannel"] = result["configuration"]["infrastructure"].pop("sendium")
        result["resources"]["kannel"] = result["resources"].pop("sendium")
    return result


class TargetContractTests(unittest.TestCase):
    def test_legacy_and_explicit_sendium_and_kannel_reports_are_valid(self):
        Draft202012Validator.check_schema(SCHEMAS["results.schema.json"])
        legacy = fixture()
        VALIDATOR.validate(legacy)
        legacy["configuration"]["application"]["benchmarkTarget"] = "sendium"
        VALIDATOR.validate(legacy)
        legacy["configuration"]["application"].update(runtime="jvm", jvmArguments=["-Xmx512m"])
        VALIDATOR.validate(legacy)
        legacy["configuration"]["application"].update(runtime="native", jvmArguments=[], nativeArguments=["-Xmx512m"])
        VALIDATOR.validate(legacy)
        VALIDATOR.validate(fixture("kannel"))
        postgres = fixture("kannel")
        postgres["configuration"]["application"]["dlrStorage"] = "postgresql"
        postgres["configuration"]["infrastructure"]["database"] = {"engine": "postgresql", "provisioned": True, "sslMode": "verify-full"}
        VALIDATOR.validate(postgres)

    def test_kannel_rejects_smpp_heap_flags_commit_claims_and_other_runtime_labels(self):
        mutations = [
            lambda doc: doc["configuration"]["workload"].update(ingressMode="smpp"),
            lambda doc: doc["configuration"]["application"].update(runtime="native"),
            lambda doc: doc["configuration"]["application"].update(runtime="jvm"),
            lambda doc: doc["configuration"]["application"].update(jvmArguments=["-Xmx512m"]),
            lambda doc: doc["configuration"]["application"].update(nativeArguments=["-Xmx512m"]),
            lambda doc: doc["configuration"]["application"].update(dlrStorage="postgresql"),
            lambda doc: doc["provenance"].update(applicationCommit="a" * 40),
        ]
        for mutate in mutations:
            document = fixture("kannel")
            mutate(document)
            self.assertFalse(VALIDATOR.is_valid(document))

    def test_kannel_requires_source_identity_and_correct_resource_labels(self):
        document = fixture("kannel")
        for field in ("version", "archiveSha256", "patchArchiveSha256", "patches"):
            broken = copy.deepcopy(document)
            del broken["configuration"]["application"]["source"][field]
            self.assertFalse(VALIDATOR.is_valid(broken), field)
        for node in ("resources", "configuration/infrastructure"):
            broken = copy.deepcopy(document)
            value = broken["resources"] if node == "resources" else broken["configuration"]["infrastructure"]
            value["sendium"] = value.pop("kannel")
            self.assertFalse(VALIDATOR.is_valid(broken), node)

    def test_sendium_still_requires_a_commit_and_cannot_inherit_kannel_storage_or_runtime(self):
        for field, value in (("runtime", "kannel"), ("dlrStorage", "internal"), ("benchmarkTarget", "unknown")):
            document = fixture()
            document["configuration"]["application"][field] = value
            self.assertFalse(VALIDATOR.is_valid(document), field)
        document = fixture()
        document["provenance"]["applicationCommit"] = None
        self.assertFalse(VALIDATOR.is_valid(document))


if __name__ == "__main__":
    unittest.main()
