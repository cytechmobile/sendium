"""Evidence availability, finalization invariants and immediate cleanup workflow contracts."""

from copy import deepcopy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import prepare
import publish
import recover
import test_prepare
from test_prepare import WORKFLOW, request


def measured():
    req = request({"offered_rate": 1, "measurement_seconds": 1, "warmup_seconds": 0, "dlr_enabled": False})
    report = publish.unavailable(req, "FIXTURE")
    report["validity"] = {"status": "inconclusive", "exitCode": 0, "reasons": [{"code": "RESOURCE_GAPS", "message": "Synthetic fixture lacks resources",
        "scope": "resources", "severity": "INCONCLUSIVE"}]}
    report["overall"]["durationSeconds"] = 1
    for name in ("scheduled", "attempted", "accepted", "providerArrivals", "providerResponses", "acceptedProviderCompleted", "peakOutstanding", "peakTracked"):
        report["overall"]["counts"][name] = 1
    report["overall"]["rates"] = {"requestedInputPerSecond": 1, "attemptedInputPerSecond": 1, "providerArrivalsPerSecond": 1,
                                   "providerResponsesPerSecond": 1, "receiptsForwardedPerSecond": 0}
    for name in ("acknowledgement", "submissionToProvider", "providerResponseCompletion"):
        report["overall"]["latencies"][name] = {"count": 1, "p50Millis": 1, "p95Millis": 1, "p99Millis": 1, "minMillis": 1, "maxMillis": 1}
    cohort = {**deepcopy(report["overall"]), "startOffsetSeconds": 0, "endOffsetSeconds": 1}
    for name in ("providerArrivalsPerSecond", "providerResponsesPerSecond", "receiptsForwardedPerSecond"):
        cohort["rates"][name] = None
    report["cohortIntervals"] = [cohort]
    report["eventIntervals"] = [{"phase": "measurement", "startOffsetSeconds": 0, "durationSeconds": 1,
        "providerArrivals": 1, "providerResponses": 1, "receiptsGenerated": 0, "receiptsForwarded": 0,
        "rates": {"providerArrivalsPerSecond": 1, "providerResponsesPerSecond": 1, "receiptsForwardedPerSecond": 0},
        "outstanding": {"pendingAdmission": 0, "acceptedAwaitingProvider": 0, "acceptedAwaitingReceipt": 0}}]
    report["measurementMetadata"].update(resourceFinalizationVersion=1, measurementStartUtc="2026-10-16T12:00:00Z",
                                          observedDurationNanos=1000000000, observationAvailable=True)
    publish.validate(report, req)
    return req, report


class PublicationTests(unittest.TestCase):
    def test_setup_failure_has_null_rates_no_intervals_and_no_fabricated_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            req = request()
            output = Path(directory) / "published"
            result = publish.publish(req, directory, directory, directory, directory, {}, {"aws-execution": "failure"}, output)
            report = publish.read(output / "results.json")
            publish.validate(report, req)
            self.assertEqual(result["exitCode"], 1)
            self.assertEqual(report["validity"]["status"], "failed")
            self.assertFalse(report["measurementMetadata"]["observationAvailable"])
            self.assertTrue(all(value is None for value in report["overall"]["rates"].values()))
            self.assertEqual(report["eventIntervals"], [])
            self.assertIn("Observation unavailable", (output / "summary.md").read_text())

    def test_invalid_report_body_is_not_republished_as_results_or_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "retrieved/harness"
            path.mkdir(parents=True)
            (path / "results.json").write_text('{"password":"private-input-value"}')
            output = root / "published"
            publish.publish(request(), root, root, root, root, {}, {"aws-execution": "failure"}, output)
            self.assertNotIn("private-input-value", (output / "results.json").read_text())
            self.assertFalse(any(path.name in ("environment.json", "runtime-settings.json") for path in output.rglob("*")))

    def test_resource_failure_keeps_every_workload_measurement_and_fails_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, original = measured()
            path = root / "retrieved/harness"
            path.mkdir(parents=True)
            prepare.write(path / "results.json", original)
            with patch.object(publish, "finalize", side_effect=ValueError("Unavailable")):
                result = publish.publish(req, root, root, root, root, {}, {"aws-execution": "success"}, root / "published")
            final = publish.read(root / "published/results.json")
            self.assertEqual(result["exitCode"], 1)
            self.assertEqual(final["validity"]["status"], "inconclusive")
            self.assertTrue(all(final[key] == original[key] for key in publish.IMMUTABLE))

    def test_cleanup_failure_changes_pipeline_status_without_rewriting_valid_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, report = measured()
            report["validity"] = {"status": "valid", "exitCode": 0, "reasons": []}
            path = root / "retrieved/harness"
            path.mkdir(parents=True)
            prepare.write(path / "results.json", report)
            with patch.object(publish, "finalize", return_value=(deepcopy(report), "# Verified observation\n")):
                result = publish.publish(req, root, root, root, root, {}, {"aws-execution": "success", "cleanup": "failure"}, root / "published")
            self.assertEqual(result["exitCode"], 1)
            self.assertEqual(result["observationStatus"], "valid")
            self.assertIn("CLEANUP_UNCONFIRMED", result["issues"])

    def test_expected_inconclusive_observation_can_complete_with_confirmed_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, report = measured()
            path = root / "retrieved/harness"
            path.mkdir(parents=True)
            prepare.write(path / "results.json", report)
            prepare.write(root / "cleanup-outcome.json", {"schemaVersion": 1, "runIdentity": req["runIdentity"], "exitCode": 0,
                "stackDeleted": True, "artifactsDeleted": True, "issues": []})
            with patch.object(publish, "finalize", return_value=(deepcopy(report), "# Inconclusive observation\n")):
                result = publish.publish(req, root, root, root, root, {}, {"aws-execution": "success", "cleanup": "success"}, root / "published")
            self.assertEqual(result["exitCode"], 0)
            self.assertTrue(result["cleanupConfirmed"])

    def test_finalization_rejects_changed_counts_or_lost_workload_failure(self):
        _, original = measured()
        changed = deepcopy(original)
        changed["overall"]["counts"]["scheduled"] += 1
        with self.assertRaises(ValueError):
            publish.immutable(original, changed)
        changed = deepcopy(original)
        changed["measurementMetadata"]["observedDurationNanos"] += 1000000000
        with self.assertRaisesRegex(ValueError, "observation durations differ"):
            publish.validate(changed, measured()[0])
        changed = deepcopy(original)
        original["validity"]["reasons"].append({"code": "ACCOUNTING_VIOLATION", "message": "Failure", "scope": "workload", "severity": "FAILED"})
        original["validity"].update(status="failed", exitCode=1)
        with self.assertRaises(ValueError):
            publish.immutable(original, changed)
        changed = deepcopy(original)
        changed["validity"].update(status="valid", exitCode=0)
        with self.assertRaisesRegex(ValueError, "downgraded a workload failure"):
            publish.immutable(original, changed)

    def test_finalizer_refuses_cloud_credentials_before_launching_any_jar(self):
        req, original = measured()
        with patch.dict("os.environ", {"AWS_ACCESS_KEY_ID": "test-only"}), patch("publish.subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "unprivileged"):
                publish.finalize(req, original, "unused", {}, Path("unused"), {})
            process.assert_not_called()

    @unittest.skipUnless(os.environ.get("SENDIUM_PUBLISH_JAR"), "Opt-in packaged-JVM publication smoke check")
    def test_real_packaged_jvm_finalizes_resources_without_changing_measurements(self):
        jar = Path(os.environ["SENDIUM_PUBLISH_JAR"]).resolve()
        with tempfile.TemporaryDirectory() as directory:
            _, bundles = test_prepare.ArtifactTests().fixtures(directory)
            req, original = measured()
            path = bundles / "harness-x86_64/harness.jar"
            shutil.copyfile(jar, path)
            metadata = prepare.read(path.parent / "build-manifest.json")
            metadata.update(sha256=prepare.digest(path), size=path.stat().st_size)
            (path.parent / "build-manifest.json").write_text(json.dumps(metadata))
            inputs = prepare.verify_bundles(req, bundles)
            work = Path(directory) / "work"
            work.mkdir()
            resources = {"schemaVersion": 1, "sendiumSamples": None, "harnessSamples": None, "cpuCredits": None, "sampleBudget": 8192}
            for key in publish.PRIVILEGED_ENVIRONMENT:
                self.assertFalse(bool(os.environ.get(key)), "Run this fixture without cloud credentials")
            final, markdown = publish.finalize(req, original, bundles, inputs, work, resources)
            self.assertTrue(final["measurementMetadata"]["resourcesFinalized"])
            publish.immutable(original, final)
            self.assertIn("One-minute", markdown)


class RecoveryTests(unittest.TestCase):
    def test_diagnostics_collects_exact_window_and_preserves_partial_metric_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, report = measured()
            ids = {"sendium": "i-11111111111111111", "harness": "i-22222222222222222"}
            report["configuration"]["infrastructure"]["region"] = "eu-west-1"
            report["measurementMetadata"]["measurementStartUtc"] = "2026-10-16T12:00:00.123456789Z"
            for source, identity in ids.items():
                report["configuration"]["infrastructure"][source] = {"instanceId": identity, "instanceType": "t4g.small"}
            path = root / "retrieved/harness"
            path.mkdir(parents=True)
            prepare.write(path / "results.json", report)
            tags = [{"Key": name, "Value": value} for name, value in {"sendium:managed-by": "performance-tests",
                "sendium:repository": req["repository"], "sendium:run-identity": req["runIdentity"]}.items()]
            aws = Mock(call=lambda *_args, **_kwargs: {"Reservations": [{"Instances": [
                {"InstanceId": identity, "InstanceType": "t4g.small", "Tags": tags + [{"Key": "sendium:role", "Value": source}]}
                for source, identity in ids.items()]}]})
            partial = {"schemaVersion": 1, "instances": [], "collectionIssues": ["CREDIT_BALANCE_MISSING"]}
            with patch.object(recover.deploy, "retrieve", return_value={}), patch.object(recover.collect_cpu_credits, "collect", return_value=partial) as collect:
                result = recover.diagnostics(aws, Mock(), {"region": "eu-west-1"}, req, root)
            self.assertEqual(result["exitCode"], 0)
            self.assertEqual(collect.call_args.args[1], ids)
            self.assertEqual(collect.call_args.args[2].microsecond, 123456)
            self.assertEqual(collect.call_args.args[3].microsecond, 123457)
            self.assertEqual((collect.call_args.args[3] - collect.call_args.args[2]).total_seconds(), 1.000001)
            self.assertEqual(prepare.read(root / "cpu-credits.json"), partial)
            self.assertEqual(publish.read(path / "results.json"), report)

    def test_diagnostics_missing_report_is_explicit_and_never_runs_credit_queries(self):
        with tempfile.TemporaryDirectory() as directory:
            credits = Mock()
            with patch.object(recover.deploy, "retrieve", side_effect=lambda *_: {"issues": ["UNAVAILABLE"]}):
                result = recover.diagnostics(Mock(), credits, {}, request(), directory)
            self.assertIn("REPORT_UNAVAILABLE", result["issues"])
            credits.call.assert_not_called()

    def test_cleanup_reconstructs_identity_without_controller_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            req = request()
            plan = {"schemaVersion": 1, "runIdentity": req["runIdentity"], "region": "eu-west-1", "repository": req["repository"],
                    "bootstrapStack": "bootstrap", "runStack": "sendium-perf-run-" + req["runIdentity"], "preset": req["preset"]}
            with patch.object(recover, "ownership", return_value=plan), patch.object(recover, "Cleaner") as cleaner:
                cleaner.return_value.run.return_value = {"exitCode": 0}
                result = recover.run(Mock(), req, "eu-west-1", "bootstrap", "role", Path(directory) / "cleanup", False)
            self.assertEqual(result["exitCode"], 0)
            self.assertEqual(cleaner.call_args.kwargs["timeout_seconds"], 1800)
            self.assertTrue((Path(directory) / "cleanup/cleanup-outcome.json").is_file())

    def test_recovery_role_error_is_preserved_as_failed_outcome(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(recover, "ownership", side_effect=ValueError("Wrong role")):
            result = recover.run(Mock(), request(), "eu-west-1", "bootstrap", "wrong", Path(directory) / "diagnostics", True)
            self.assertEqual(result["exitCode"], 1)
            self.assertEqual(result["issues"], ["DIAGNOSTIC_RECOVERY_FAILED"])


class PublicationWorkflowTests(unittest.TestCase):
    def test_diagnostics_precedes_cleanup_and_publication_has_no_cloud_access(self):
        jobs = WORKFLOW["jobs"]
        self.assertIn("always()", jobs["diagnostics"]["if"])
        self.assertIn("always()", jobs["cleanup"]["if"])
        self.assertIn("diagnostics", jobs["cleanup"]["needs"])
        self.assertIn("cleanup", jobs["publish"]["needs"])
        self.assertEqual(jobs["publish"]["permissions"], {"contents": "read"})
        self.assertNotIn("environment", jobs["publish"])
        self.assertFalse(any("configure-aws" in step.get("uses", "") for step in jobs["publish"]["steps"]))
        diagnostic_role = next(step for step in jobs["diagnostics"]["steps"] if "configure-aws" in step.get("uses", ""))
        self.assertIn("AWS_DIAGNOSTICS_ROLE_ARN", diagnostic_role["with"]["role-to-assume"])


if __name__ == "__main__":
    unittest.main()
