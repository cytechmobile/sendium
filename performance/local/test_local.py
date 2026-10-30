"""Local authority/ownership/context invariants; full Docker matrix is a separate command."""

import io
import copy
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace

import agent
import build_application
import compare_load
import rehearse
from public_artifacts import redact_known
from scenarios import DEFAULT, LOAD, select, workload_overrides

sys.path.insert(0, str(rehearse.ROOT / "performance/deployment"))
from backend import LocalAws
from aws_cli import AwsError


class LocalBoundaryTests(unittest.TestCase):
    def test_modeled_rds_identity_tags_and_metrics_satisfy_the_unchanged_read_only_verifier(self):
        identity = "gh-12345-1"
        name = "local-perf-run-" + identity
        tags = [{"Key": key, "Value": value} for key, value in {
            "sendium:managed-by": "performance-tests", "sendium:repository": "cytechmobile/sendium", "sendium:run-identity": identity}.items()]
        lab = SimpleNamespace(simulation={}, calls=[], roots={identity: {"name": name, "Tags": tags, "dbVersion": "17.0"}}, real=Mock())
        aws = LocalAws(lab, identity, "diagnostics")
        sys.path.insert(0, str(rehearse.ROOT / "performance/resources"))
        from collect_rds_metrics import verify_database
        database = {"identifier": name, "instanceClass": "db.local.small", "partition": "aws", "region": "local",
                    "accountId": "000000000000", "repository": "cytechmobile/sendium", "runIdentity": identity}
        self.assertEqual("arn:aws:rds:local:000000000000:db:" + name, verify_database(aws, database))
        with self.assertRaises(ValueError):
            verify_database(aws, {**database, "repository": "other/repository"})
        response = aws.call("cloudwatch", "get-metric-statistics", namespace="AWS/RDS", metric_name="CPUUtilization",
                            dimensions=[{"Name": "DBInstanceIdentifier", "Value": name}], start_time="2026-10-30T08:00:00Z",
                            end_time="2026-10-30T08:02:00Z", period=60, statistics=["Average", "Minimum", "Maximum", "SampleCount"])
        self.assertEqual(2, len(response["Datapoints"]))
        self.assertEqual("Percent", response["Datapoints"][0]["Unit"])
        with self.assertRaises(AwsError):
            aws.call("cloudwatch", "get-metric-statistics", namespace="AWS/RDS", dimensions=[])
        lab.real.call.assert_not_called()

    def test_application_overlay_preserves_the_base_controller_recovery_version(self):
        original = b'context = {"ref": "refs/heads/local-rehearsal", "applicationCommit": lab.source["baseCommit"]}\nlegacy_recovery_api()\n'
        updated = build_application.application_provenance(original)
        self.assertIn(b'lab.source.get("applicationCommit", lab.source["baseCommit"])', updated)
        self.assertTrue(updated.endswith(b'legacy_recovery_api()\n'))
        self.assertEqual(build_application.application_provenance(updated), updated)
        with self.assertRaisesRegex(ValueError, "does not support selected application provenance"):
            build_application.application_provenance(b"unknown_controller()")

    def test_local_cleanup_mode_cannot_provision_or_read_objects(self):
        lab = SimpleNamespace(simulation={}, calls=[], roots={}, real=Mock())
        aws = LocalAws(lab, "gh-12345-1", "cleanup")
        for service, operation in (("cloudformation", "create-stack"), ("ssm", "send-command"),
                                   ("s3api", "get-object"), ("s3api", "put-object")):
            with self.subTest(operation=operation), self.assertRaises(AwsError):
                aws.call(service, operation)
        with self.assertRaises(AwsError):
            aws.get("bucket", "runs/gh-12345-1/inputs/private", "unused")
        lab.real.call.assert_not_called()
        identity = aws.call("sts", "get-caller-identity")
        self.assertIn("assumed-role/local-perf-cleanup/", identity["Arn"])
        aws.call("s3api", "list-objects-v2", bucket="bucket", prefix="runs/gh-12345-1/")
        lab.real.call.assert_called_once_with("s3api", "list-objects-v2", bucket="bucket", prefix="runs/gh-12345-1/")

    def test_comparison_rejects_different_workloads_harness_and_runtime(self):
        document = {"provenance": {"artifacts": [{"name": name, "sha256": "ab" * 32} for name in
                    ("harness", "sendiumJre", "harnessJre", "node", "transport", "sampler", "preset", "network", "runTemplate")]},
                    "configuration": {"workload": {"dlrEnabled": True, "offeredMessagesPerSecond": 100},
                    "application": {"jvmArguments": ["-Xmx512m"], "connections": {}, "dlrStorage": "postgresql"},
                    "infrastructure": {"resolution": {"nodes": {}, "database": {}, "executionKind": "container-jvm"},
                                       "database": {key: 1 for key in ("poolMinSize", "poolMaxSize", "acquisitionTimeoutMillis", "claimDurationSeconds", "sslMode")}}}}
        self.assertEqual(len(compare_load.matched_configuration(document, copy.deepcopy(document))), 9)
        changed = copy.deepcopy(document)
        changed["configuration"]["application"]["runtime"] = "native"
        with self.assertRaisesRegex(ValueError, "Application runtime differs"):
            compare_load.matched_configuration(document, changed)
        changed = copy.deepcopy(document)
        changed["configuration"]["application"]["nativeBuild"] = {"compilerVersion": "different compiler"}
        with self.assertRaisesRegex(ValueError, "runtime setting differs: nativeBuild"):
            compare_load.matched_configuration(document, changed)
        changed = copy.deepcopy(document)
        changed["provenance"]["artifacts"][0]["sha256"] = "cd" * 32
        with self.assertRaisesRegex(ValueError, "artifact differs: harness"):
            compare_load.matched_configuration(document, changed)
        changed = copy.deepcopy(document)
        changed["configuration"]["workload"]["offeredMessagesPerSecond"] = 30
        with self.assertRaisesRegex(ValueError, "workloads differ"):
            compare_load.matched_configuration(document, changed)
        changed = copy.deepcopy(document)
        changed["configuration"]["application"]["jvmArguments"] = ["-Xmx1g"]
        with self.assertRaisesRegex(ValueError, "runtime setting differs"):
            compare_load.matched_configuration(document, changed)

    def test_selected_application_context_preserves_sources_and_separates_controller_overlay(self):
        application = io.BytesIO()
        with tarfile.open(fileobj=application, mode="w", pax_headers={"comment": "git-commit-identity"}) as archive:
            entry = tarfile.TarInfo("sendium-app/src/main/resources/application.properties")
            content = b"branch-specific-configuration"
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
        data = build_application.application_context(application.getvalue(), {"applicationCommit": "ab" * 20}, b"controller")
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            self.assertEqual(archive.extractfile(entry.name).read(), content)
            self.assertNotIn("comment", archive.getmember(entry.name).pax_headers)
            self.assertEqual(archive.extractfile("local/lab.py").read(), b"controller")
            self.assertEqual(json.load(archive.extractfile("source-context.json"))["applicationCommit"], "ab" * 20)
            self.assertFalse(any(name.startswith("performance/harness") for name in archive.getnames()))

    def test_selected_application_context_rejects_links_traversal_and_unexpected_components(self):
        for name, kind in (("../outside", tarfile.REGTYPE), ("sendium-app/link", tarfile.SYMTYPE),
                           ("performance/harness/pom.xml", tarfile.REGTYPE)):
            application = io.BytesIO()
            with tarfile.open(fileobj=application, mode="w") as archive:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                archive.addfile(entry, io.BytesIO())
            with self.subTest(name=name), self.assertRaises(ValueError):
                build_application.application_context(application.getvalue(), {}, b"controller")

    def test_default_runs_use_dlr_and_no_dlr_requires_explicit_selection(self):
        self.assertEqual(select("load"), ("http-dlr", "smpp-dlr"))
        self.assertEqual(DEFAULT, select("load"))
        self.assertEqual(select("http-no-dlr,smpp-no-dlr"), ("http-no-dlr", "smpp-no-dlr"))
        self.assertTrue(set(LOAD) <= set(select("all")))
        for invalid in ("", "http", "no-dlr", "load,http-no-dlr"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                select(invalid)

    def test_kannel_defaults_to_http_dlr_and_rejects_smpp_before_docker_execution(self):
        self.assertEqual(select("load", "kannel"), ("http-dlr",))
        self.assertEqual(select("http-dlr,http-no-dlr", "kannel"), ("http-dlr", "http-no-dlr"))
        with patch.object(sys, "argv", ["rehearse.py", "--benchmark-target", "kannel", "--scenarios", "smpp-dlr"]), \
                patch("rehearse.call") as docker, patch("sys.stderr"):
            with self.assertRaises(SystemExit) as error:
                rehearse.main()
            self.assertEqual(2, error.exception.code)
            docker.assert_not_called()

    def test_shared_local_phase_overrides_are_bounded_by_the_preset(self):
        preset = json.loads((rehearse.ROOT / "performance/presets/aws-private-jvm-v1.json").read_text())
        self.assertEqual({"warmup_seconds": 0, "measurement_seconds": 65},
                         workload_overrides(SimpleNamespace(warmup_seconds=0, measurement_seconds=65), preset))
        for field, value in (("warmup_seconds", 601), ("measurement_seconds", 0), ("worker_tps", -1)):
            with self.assertRaises(ValueError):
                workload_overrides(SimpleNamespace(**{field: value}), preset)

    def test_wrong_target_resume_does_not_clean_up_the_original_session(self):
        session = "12345-abcdef"
        original = {"Config": {"Labels": {"sendium.local.session": session, "sendium.benchmark.target": "kannel"}}}
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(sys, "argv", ["rehearse.py", "--resume-session", session, "--skip-build", "--output", str(Path(temporary) / "new")]), \
                patch("rehearse.call", return_value=subprocess.CompletedProcess([], 0, stdout=json.dumps([original]))), \
                patch("rehearse.clean_session") as cleanup:
            with self.assertRaisesRegex(SystemExit, "original benchmark target"):
                rehearse.main()
            cleanup.assert_not_called()

    def test_cancelling_resume_before_ownership_verification_cannot_stop_or_delete_the_session(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(sys, "argv", ["rehearse.py", "--resume-session", "12345-abcdef", "--skip-build", "--output", str(Path(temporary) / "new")]), \
                patch("rehearse.call", side_effect=KeyboardInterrupt) as docker, patch("rehearse.clean_session") as cleanup:
            self.assertEqual(130, rehearse.main())
            self.assertEqual(["inspect", "sendium-local-control-12345-abcdef"], docker.call_args_list[0].args[0])
            self.assertEqual(1, docker.call_count)
            cleanup.assert_not_called()

    def test_kannel_context_omits_application_sources_and_generated_private_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "performance/harness/src").mkdir(parents=True)
            (root / "performance/harness/pom.xml").write_text("<project/>")
            (root / "pom.xml").write_text("<project/>"); (root / "mvnw").write_text("wrapper")
            (root / "performance/kannel/smoke-results/private").mkdir(parents=True)
            (root / "performance/kannel/smoke-results/private/runtime.json").write_text("private-password")
            (root / "performance/kannel/private-runtime.json").write_text("private-password")
            (root / "performance/kannel/benchmark.py").write_text("controller")
            (root / "sendium-app/src").mkdir(parents=True)
            (root / "sendium-app/src/unrelated").write_text("application")
            with patch("rehearse.ROOT", root), patch("rehearse.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout="ab" * 20)):
                data = rehearse.context("kannel")
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                self.assertFalse(any(name.startswith("sendium-app/") or "smoke-results" in name for name in archive.getnames()))
                self.assertNotIn("performance/kannel/private-runtime.json", archive.getnames())
                self.assertIn("performance/kannel/benchmark.py", archive.getnames())
                self.assertEqual("local-working-tree", json.load(archive.extractfile("source-context.json"))["sourceMode"])

    def test_failed_rehearsal_exports_redact_known_values_without_logging_them(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "failed.log"
            path.write_text("failure contains private-fixture-password")
            changed = redact_known(Path(directory), ["private-fixture-password"])
            self.assertEqual(changed, ["failed.log"])
            self.assertEqual(path.read_text(), "failure contains [REDACTED]")
            self.assertNotIn("private-fixture-password", json.dumps(changed))
    def environment(self, role="harness"):
        return {"LOCAL_NODE_ROLE": role, "LOCAL_RUN_IDENTITY": "gh-12345-1", "LOCAL_BUCKET": "bucket", "LOCAL_DATABASE_SECRET_ARN": "arn:local:secret:own"}

    def test_harness_and_cross_run_reads_are_denied_before_cli_execution(self):
        for arguments in (["secretsmanager", "get-secret-value", "--secret-id", "arn:local:secret:own"],
                          ["s3api", "get-object", "--bucket", "bucket", "--key", "runs/gh-12345-2/control/run.json", "output"],
                          ["s3api", "get-object", "--bucket", "another", "--key", "runs/gh-12345-1/inputs/common/runtime", "output"]):
            with self.subTest(arguments=arguments), patch.dict("os.environ", self.environment(), clear=True), \
                 patch("agent.subprocess.call") as process, patch("sys.stderr"):
                self.assertNotEqual(agent.aws(arguments), 0)
                process.assert_not_called()

    def test_allowed_runtime_request_is_forced_to_local_endpoint(self):
        arguments = ["s3api", "get-object", "--region", "local", "--bucket", "bucket", "--key", "runs/gh-12345-1/inputs/common/runtime", "output"]
        with patch.dict("os.environ", self.environment(), clear=True), patch("agent.subprocess.call", return_value=0) as process:
            self.assertEqual(agent.aws(arguments), 0)
        command = process.call_args.args[0]
        self.assertEqual(command[-2:], ["--endpoint-url", "http://objects:4566"])
        self.assertEqual(command[command.index("--region") + 1], "us-east-1")

    def test_sendium_secret_permission_is_exact_not_a_name_substring(self):
        with patch.dict("os.environ", self.environment("sendium"), clear=True), patch("agent.subprocess.call") as process, patch("sys.stderr"):
            self.assertNotEqual(agent.aws(["secretsmanager", "get-secret-value", "--secret-id", "arn:local:secret:own-other"]), 0)
            process.assert_not_called()

    def test_session_cleanup_only_uses_exact_owned_labels_and_no_prune(self):
        observed, removed = [], set()
        def call(arguments, **options):
            observed.append(arguments)
            if "--filter" in arguments:
                self.assertEqual(arguments[-1], "label=sendium.local.session=12345-abcdef")
                kind = "container" if arguments[0] == "ps" else arguments[0]
                return subprocess.CompletedProcess(arguments, 0, stdout="" if kind in removed else kind + "-owned\n")
            kind = "container" if arguments[0] == "rm" else arguments[0]
            removed.add(kind)
            return subprocess.CompletedProcess(arguments, 0, stdout="")
        with patch("rehearse.call", side_effect=call):
            result = rehearse.clean_session("12345-abcdef")
        self.assertEqual(result["remaining"], {"container": 0, "network": 0, "volume": 0})
        self.assertFalse(any("prune" in arguments for arguments in observed))

    def test_build_context_excludes_targets_git_and_records_actual_tree_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for component in ("sendium-core", "sendium-app", "performance/harness"):
                (root / component / "src").mkdir(parents=True)
                (root / component / "pom.xml").write_text("<project/>")
            (root / "pom.xml").write_text("<project/>")
            (root / "mvnw").write_text("wrapper")
            (root / "performance/harness/target").mkdir()
            (root / "performance/harness/target/private-file").write_text("not a build input")
            with patch("rehearse.ROOT", root), patch("rehearse.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout="ab" * 20)):
                context = rehearse.context()
            with tarfile.open(fileobj=io.BytesIO(context)) as archive:
                self.assertFalse(any("target" in name.split("/") or ".git" in name.split("/") for name in archive.getnames()))
                source = json.load(archive.extractfile("source-context.json"))
                self.assertEqual(source["sourceMode"], "local-working-tree")
                self.assertEqual(len(source["workingTreeDigest"]), 64)


if __name__ == "__main__":
    unittest.main()
