"""Adversarial data and declared workflow-outcome audit; no hosted or AWS execution."""

from copy import deepcopy
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import Mock, patch

import prepare
import publish
import recover
import run_aws
import test_aws
import test_prepare
import test_publication


def condition(expression, statuses):
    """Evaluate only the workflow's declared always()/needs-result conjunction grammar."""
    result = True
    for clause in expression.split("&&"):
        clause = clause.strip()
        if clause == "always()":
            continue
        match = re.fullmatch(r"needs\.([a-z-]+)\.result\s*(==|!=)\s*'([a-z]+)'", clause)
        if not match:
            raise AssertionError("Review lifecycle condition grammar: " + clause)
        actual = statuses[match[1]]
        result &= actual == match[3] if match[2] == "==" else actual != match[3]
    return result


class PermissionAuditTests(unittest.TestCase):
    def test_oidc_exists_only_on_the_three_protected_hosted_jobs(self):
        jobs = test_prepare.WORKFLOW["jobs"]
        protected = {"aws-execution", "diagnostics", "cleanup"}
        for name, job in jobs.items():
            with self.subTest(job=name):
                expected = {"contents": "read", **({"id-token": "write"} if name in protected else {})}
                self.assertEqual(job["permissions"], expected)
                self.assertEqual(job.get("environment"), "sendium-performance" if name in protected else None)
                self.assertNotIn("self-hosted", str(job["runs-on"]))
                self.assertNotIn("secrets.", json.dumps(job))
                for step in job["steps"]:
                    self.assertNotIn("${{", step.get("run", ""))
                if name in protected:
                    auth = next(index for index, step in enumerate(job["steps"]) if "configure-aws" in step.get("uses", ""))
                    installs = [index for index, step in enumerate(job["steps"]) if "pip install" in step.get("run", "")]
                    self.assertTrue(all(index < auth for index in installs))
                    self.assertFalse(any(re.search(r"\bmvnw\b|\bjava\s+-jar|\bdocker\s+(build|run)", step.get("run", "")) for step in job["steps"]))

    def test_cleanup_and_publication_conditions_cover_failure_and_cancellation(self):
        jobs = test_prepare.WORKFLOW["jobs"]
        for status in ("success", "failure", "cancelled", "skipped"):
            states = {"validate": "success", "aws-execution": status, "diagnostics": "failure", "cleanup": "cancelled"}
            self.assertEqual(condition(jobs["diagnostics"]["if"], states), status != "skipped")
            self.assertEqual(condition(jobs["cleanup"]["if"], states), status != "skipped")
            self.assertTrue(condition(jobs["publish"]["if"], states))
        states = {"validate": "failure", "aws-execution": "skipped"}
        self.assertFalse(condition(jobs["cleanup"]["if"], states))
        self.assertFalse(condition(jobs["publish"]["if"], states))

    def test_output_uploads_exclude_private_worktrees_and_always_preserve_failures(self):
        for name in ("aws-execution", "diagnostics", "cleanup", "publish"):
            uploads = [step for step in test_prepare.WORKFLOW["jobs"][name]["steps"] if "upload-artifact" in step.get("uses", "")]
            self.assertTrue(uploads)
            for upload in uploads:
                self.assertEqual(upload["if"], "always()")
                self.assertEqual(upload["with"]["retention-days"], "30")
                paths = upload["with"]["path"]
                for forbidden in ("runtime-settings.json", "environment.json", "deployment-private.json", "/_work/", "aws-performance/**", "published/**"):
                    self.assertNotIn(forbidden, paths)


class FailureAuditTests(unittest.TestCase):
    def test_interruption_after_successful_execute_read_cannot_leave_exit_zero(self):
        class InterruptedResult:
            reads = 0
            def __getitem__(self, key):
                self.reads += 1
                if self.reads == 1:
                    return 0
                raise KeyboardInterrupt
        with tempfile.TemporaryDirectory() as directory:
            req, bundles = test_prepare.ArtifactTests().fixtures(directory)
            inputs = prepare.verify_bundles(req, bundles)
            resolved = test_aws.resolve_aws.resolve(test_aws.RegionalAws(), req["preset"], "eu-west-1")
            plan = {"schemaVersion": 1, "runIdentity": req["runIdentity"], "repository": req["repository"],
                    "region": "eu-west-1", "bootstrapStack": "bootstrap", "runStack": "sendium-perf-run-" + req["runIdentity"]}
            detail = {"java": {source: {"release": "jdk-25.0.4.1+1"} for source in ("sendium", "harness")}}
            with patch.object(run_aws, "preflight", return_value=(plan, {}, "aws")), \
                 patch.object(run_aws.resolve_aws, "resolve", return_value=resolved), \
                 patch.object(run_aws.runtime_assets, "resolve", return_value=({}, detail)), \
                 patch.object(run_aws.deploy, "stage", return_value=plan), patch.object(run_aws, "create_stack"), \
                 patch.object(run_aws.deploy, "execute", return_value=InterruptedResult()), \
                 patch.object(run_aws, "stack_events", return_value={}), patch.object(run_aws.deploy, "retrieve"):
                outcome = run_aws.run(Mock(), Mock(), req, inputs, bundles, {"region": "eu-west-1"}, Path(directory) / "controller")
            self.assertEqual(outcome["exitCode"], 1)
            self.assertIn("PROTECTED_CONTROLLER_FAILED", outcome["issues"])

    def test_added_workload_reasons_are_not_allowed_during_resource_finalization(self):
        _, before = test_publication.measured()
        after = deepcopy(before)
        after["validity"]["reasons"].append({"code": "INVENTED_FAILURE", "message": "Not observed", "severity": "FAILED", "scope": "workload"})
        after["validity"].update(status="failed", exitCode=1)
        with self.assertRaisesRegex(ValueError, "changed workload reasons"):
            publish.immutable(before, after)

    def test_profile_container_and_oidc_providers_block_jvm_before_any_launch(self):
        for key in ("AWS_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_WEB_IDENTITY_TOKEN_FILE", "ACTIONS_ID_TOKEN_REQUEST_TOKEN"):
            with self.subTest(key=key), patch.dict("os.environ", {key: "test-only-provider"}, clear=True), patch("publish.subprocess.run") as process:
                with self.assertRaisesRegex(ValueError, "unprivileged"):
                    publish.finalize(*test_publication.measured(), "unused", {}, Path("unused"), {})
                process.assert_not_called()

    def test_unreadable_evidence_preserves_observations_and_marks_missing_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, report = test_publication.measured()
            source = root / "retrieved/sendium"
            source.mkdir(parents=True)
            (source / "sendium-resources.jsonl").write_text("fixture")
            (root / "retrieved/harness").mkdir()
            prepare.write(root / "retrieved/harness/results.json", report)
            prepare.write(root / "cleanup-outcome.json", {"schemaVersion": 1, "runIdentity": req["runIdentity"], "exitCode": 0,
                          "stackDeleted": True, "artifactsDeleted": True, "issues": []})
            with patch.object(publish.shutil, "copyfile", side_effect=OSError("Source became unavailable")), \
                 patch.object(publish, "finalize", return_value=(deepcopy(report), "# Fixture\n")):
                result = publish.publish(req, root, root, root, root, {}, {"aws-execution": "success"}, root / "published")
            final = publish.read(root / "published/results.json")
            self.assertTrue(all(final[key] == report[key] for key in publish.IMMUTABLE))
            self.assertEqual(final["validity"]["status"], "inconclusive")
            self.assertEqual({item["source"] for item in result["evidenceIssues"]}, {"sendium-resources.jsonl", "cleanup-outcome.json"})
            self.assertTrue(result["cleanupConfirmed"])

    def test_oversized_evidence_is_not_copied(self):
        with tempfile.TemporaryDirectory() as directory, patch("publish.shutil.copyfile") as copy:
            issues = []
            source = Mock()
            source.stat.return_value.st_size = 64 * 1024 * 1024 + 1
            self.assertIsNone(publish.copy_evidence(source, Path(directory) / "cpu-credits.json", issues))
            self.assertEqual(len(issues), 1)
            copy.assert_not_called()

    def test_diagnostic_wrong_source_type_or_region_never_queries_metrics(self):
        for mutation in ("source", "type", "region"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                req, report = test_publication.measured()
                ids = {"sendium": "i-11111111111111111", "harness": "i-22222222222222222"}
                report["configuration"]["infrastructure"]["region"] = "us-east-1" if mutation == "region" else "eu-west-1"
                instances = []
                for source, identity in ids.items():
                    report["configuration"]["infrastructure"][source] = {"instanceId": identity, "instanceType": "t4g.small"}
                    tags = {"sendium:managed-by": "performance-tests", "sendium:repository": req["repository"],
                            "sendium:run-identity": req["runIdentity"], "sendium:role": "harness" if mutation == "source" else source}
                    instances.append({"InstanceId": identity, "InstanceType": "m7i.large" if mutation == "type" else "t4g.small",
                                      "Tags": [{"Key": key, "Value": value} for key, value in tags.items()]})
                (root / "retrieved/harness").mkdir(parents=True)
                prepare.write(root / "retrieved/harness/results.json", report)
                aws = Mock(call=lambda *_args, **_kwargs: {"Reservations": [{"Instances": instances}]})
                with patch.object(recover.deploy, "retrieve", return_value={}), patch.object(recover.collect_cpu_credits, "collect") as collect:
                    result = recover.diagnostics(aws, Mock(), {"region": "eu-west-1"}, req, root)
                self.assertEqual(result["exitCode"], 1)
                collect.assert_not_called()

    def test_valid_overload_accounting_failure_and_lifecycle_failure_matrix(self):
        for scenario, expected_report, expected_exit in (("valid", "valid", 0), ("overload", "valid", 0),
                ("accounting", "failed", 1), ("setup", "failed", 1), ("cancelled", "valid", 1),
                ("cleanup", "valid", 1), ("diagnostics", "inconclusive", 1)):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                req, report = test_publication.measured()
                report["validity"] = {"status": expected_report, "exitCode": 1 if expected_report == "failed" else 0, "reasons": []}
                if scenario == "overload":
                    req["preset"]["workload"]["offeredMessagesPerSecond"] = 2
                    report["configuration"]["workload"]["offeredMessagesPerSecond"] = 2
                    for measurement in (report["overall"], report["cohortIntervals"][0]):
                        measurement["counts"].update(scheduled=2, attempted=2, rejected=1)
                        measurement["rates"].update(requestedInputPerSecond=2, attemptedInputPerSecond=2)
                if scenario == "accounting":
                    report["overall"]["counts"]["violations"] = 1
                    report["cohortIntervals"][0]["counts"]["violations"] = 1
                    report["validity"]["reasons"] = [{"code": "ACCOUNTING_VIOLATION", "message": "Observed violation", "severity": "FAILED", "scope": "workload"}]
                if scenario == "diagnostics":
                    report["validity"]["reasons"] = [{"code": "INSTANCE_CREDIT_METADATA_MISSING", "message": "Credit evidence unavailable", "severity": "INCONCLUSIVE", "scope": "resources"}]
                if scenario != "setup":
                    (root / "retrieved/harness").mkdir(parents=True)
                    prepare.write(root / "retrieved/harness/results.json", report)
                prepare.write(root / "cleanup-outcome.json", {"schemaVersion": 1, "runIdentity": req["runIdentity"], "exitCode": 1 if scenario == "cleanup" else 0,
                    "stackDeleted": scenario != "cleanup", "artifactsDeleted": scenario != "cleanup", "issues": ["FIXTURE_FAILED"] if scenario == "cleanup" else []})
                outcomes = {"aws-execution": "cancelled" if scenario == "cancelled" else "failure" if scenario in ("accounting", "setup") else "success",
                            "cleanup": "failure" if scenario == "cleanup" else "success", "diagnostics": "failure" if scenario == "diagnostics" else "success"}
                with patch.object(publish, "finalize", return_value=(deepcopy(report), "# Fixture observation\n")):
                    result = publish.publish(req, root, root, root, root, {}, outcomes, root / "published")
                self.assertEqual(result["exitCode"], expected_exit)
                self.assertEqual(result["observationStatus"], expected_report)
                final = publish.read(root / "published/results.json")
                if scenario != "setup":
                    self.assertTrue(all(final[key] == report[key] for key in publish.IMMUTABLE))


if __name__ == "__main__":
    unittest.main()
