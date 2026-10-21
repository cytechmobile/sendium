"""Manual-input, build-identity and workflow permission/architecture contracts."""

import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import yaml

import prepare


ROOT = Path(__file__).resolve().parents[2]
PRESET = prepare.read(ROOT / "performance/presets/aws-private-jvm-v1.json")
CONTEXT = {"ref": "refs/heads/feature/selected", "repository": "cytechmobile/sendium", "applicationCommit": "ab" * 20,
           "controllerRevision": "cd" * 20, "workflowRevision": "ef" * 20, "runId": "12345", "runAttempt": "2",
           "workflowRunUrl": "https://github.com/cytechmobile/sendium/actions/runs/12345"}
WORKFLOW = yaml.load((ROOT / ".github/workflows/performance-tests.yml").read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


def request(inputs=None):
    return prepare.normalize(inputs or {}, CONTEXT, PRESET)


class InputTests(unittest.TestCase):
    def test_defaults_match_preset_and_dispatch_commit_is_independent_from_harness(self):
        result = request()
        self.assertEqual(result["preset"], PRESET)
        self.assertEqual(result["runIdentity"], "gh-12345-2")
        self.assertEqual(result["provenance"]["applicationCommit"], CONTEXT["applicationCommit"])
        self.assertEqual(result["provenance"]["harnessRevision"], CONTEXT["controllerRevision"])
        self.assertEqual(result["workflowRevision"], CONTEXT["workflowRevision"])
        self.assertEqual(result["provenance"]["branch"], "feature/selected")

    def test_all_settings_are_individually_configurable_and_false_survives(self):
        inputs = {"ingress_mode": "smpp", "sendium_instance_type": "c7g.large", "harness_instance_type": "m7i.large",
                  "offered_rate": 0.5, "worker_tps": "200", "warmup_seconds": 0, "measurement_seconds": 30,
                  "drain_seconds": 0, "dlr_enabled": False, "provider_response_delay_ms": 0, "provider_dlr_delay_ms": 500}
        effective = request(inputs)["preset"]
        self.assertEqual(effective["aws"]["instanceDefaults"], {"sendium": "c7g.large", "harness": "m7i.large"})
        self.assertEqual(effective["workload"]["offeredMessagesPerSecond"], 0.5)
        self.assertFalse(effective["workload"]["dlrEnabled"])
        self.assertEqual(effective["workload"]["warmupSeconds"], 0)
        self.assertEqual(effective["workload"]["workerTps"], 200)
        self.assertFalse(request({"dlr_enabled": "false"})["preset"]["workload"]["dlrEnabled"])

    def test_invalid_rates_durations_and_delays_are_rejected(self):
        cases = [{"offered_rate": value} for value in (0, -1, "NaN", "Infinity", "1e1000000", True)]
        cases += [{"measurement_seconds": 0}, {"measurement_seconds": 3601}, {"warmup_seconds": 601},
                  {"drain_seconds": 601}, {"worker_tps": -1}, {"worker_tps": 1.5}, {"worker_tps": 2147483648},
                  {"provider_response_delay_ms": 10001}, {"provider_dlr_delay_ms": 60001}, {"dlr_enabled": "yes"}]
        for inputs in cases:
            with self.subTest(inputs=inputs), self.assertRaises((ValueError, prepare.ValidationError)):
                request(inputs)

    def test_unknown_inputs_and_shell_fragments_are_not_executable_settings(self):
        for inputs in ({"application_ref": "other"}, {"offered_rate": "1; touch /tmp/unwanted"},
                       {"sendium_instance_type": "t4g.small;false"}):
            with self.subTest(inputs=inputs), patch("prepare.subprocess.run") as process:
                with self.assertRaises(ValueError):
                    request(inputs)
                process.assert_not_called()

    def test_tags_moving_ref_names_and_non_sha_revisions_cannot_replace_dispatch_identity(self):
        for change in ({"ref": "refs/tags/v1"}, {"applicationCommit": "main"}, {"controllerRevision": "HEAD"}, {"runAttempt": "0"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                prepare.normalize({}, {**CONTEXT, **change}, PRESET)

    def test_summary_escapes_branch_markup(self):
        with tempfile.TemporaryDirectory() as directory:
            result = request()
            result["provenance"]["branch"] = "feature/<script>|test"
            path = Path(directory) / "summary.md"
            prepare.summary(result, path)
            content = path.read_text(encoding="utf-8")
            self.assertNotIn("<script>", content)
            self.assertIn("&lt;script&gt;&#124;test", content)


class ArtifactTests(unittest.TestCase):
    def fixtures(self, directory):
        directory = Path(directory)
        application = directory / "application-checkout"
        app = application / "sendium-app/target/quarkus-app"
        app.mkdir(parents=True)
        (app / "quarkus-run.jar").write_bytes(b"runner")
        (app / "lib").mkdir()
        (app / "lib/dependency.jar").write_bytes(b"dependency")
        harness = directory / "control-checkout"
        target = harness / "performance/harness/target"
        target.mkdir(parents=True)
        (target / "sendium-performance-harness-9.8.7-SNAPSHOT.jar").write_bytes(b"harness")
        (target / "sendium-performance-harness-9.8.7-SNAPSHOT-shaded.jar").write_bytes(b"intermediate")
        output = directory / "bundles"
        output.mkdir()
        result = request()
        for component, checkout in (("application", application), ("harness", harness)):
            for architecture in sorted(prepare.ARCHITECTURES):
                head = prepare.revision(result, component)
                with patch("prepare.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout=head)):
                    prepare.build_bundle(result, component, architecture, checkout, output / (component + "-" + architecture),
                                         "25.0.1+9", machine="aarch64" if architecture == "arm64" else "x86_64")
        return result, output

    def test_complete_application_and_version_independent_harness_are_packaged(self):
        with tempfile.TemporaryDirectory() as directory:
            result, output = self.fixtures(directory)
            with tarfile.open(output / "application-arm64/application.tar.gz") as archive:
                self.assertIn("quarkus-app/quarkus-run.jar", archive.getnames())
                self.assertIn("quarkus-app/lib/dependency.jar", archive.getnames())
            manifest = prepare.verify_bundles(result, output)
            self.assertEqual(len(manifest["artifacts"]), 4)
            self.assertEqual({item["sourceRevision"] for item in manifest["artifacts"]}, {CONTEXT["applicationCommit"], CONTEXT["controllerRevision"]})
            self.assertEqual((output / "harness-arm64/harness.jar").read_bytes(), b"harness")

    def test_missing_bundle_or_changed_binary_fails_aggregation(self):
        with tempfile.TemporaryDirectory() as directory:
            result, output = self.fixtures(directory)
            (output / "harness-arm64/harness.jar").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                prepare.verify_bundles(result, output)

    def test_wrong_attempt_revision_architecture_and_paths_are_rejected(self):
        for change in ({"runIdentity": "gh-12345-1"}, {"sourceRevision": "ef" * 20}, {"architecture": "x86_64"},
                       {"file": "../../outside"}, {"unexpectedCode": "do-not-execute"}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                result, output = self.fixtures(directory)
                path = output / "harness-arm64/build-manifest.json"
                manifest = prepare.read(path)
                manifest.update(change)
                path.write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    prepare.verify_bundles(result, output)

    def test_wrong_runner_architecture_fails_before_packaging(self):
        with tempfile.TemporaryDirectory() as directory, patch("prepare.subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "architecture mismatch"):
                prepare.build_bundle(request(), "application", "arm64", directory, Path(directory) / "bundle", "25", machine="x86_64")
            process.assert_not_called()

    def test_changed_checkout_head_fails_before_packaging(self):
        with tempfile.TemporaryDirectory() as directory, patch("prepare.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout="ef" * 20)):
            with self.assertRaisesRegex(ValueError, "captured revision"):
                prepare.build_bundle(request(), "application", "x86_64", directory, Path(directory) / "bundle", "25", machine="x86_64")

    @unittest.skipUnless(os.name == "posix", "Creating filesystem symlinks requires POSIX or Windows developer privileges")
    def test_application_symlinks_are_not_archived(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / "app"
            app.mkdir()
            (app / "quarkus-run.jar").write_bytes(b"runner")
            (app / "outside").symlink_to("/etc/passwd")
            with self.assertRaisesRegex(ValueError, "filesystem entries"):
                prepare.archive_application(app, Path(directory) / "app.tar.gz")


class WorkflowTests(unittest.TestCase):
    def test_manual_inputs_match_defaults_and_current_documented_input_limit(self):
        self.assertEqual(set(WORKFLOW["on"]), {"workflow_dispatch"})
        inputs = WORKFLOW["on"]["workflow_dispatch"]["inputs"]
        self.assertEqual(len(inputs), 11)
        self.assertLessEqual(len(inputs), 25)
        normalized = request({key: value["default"] for key, value in inputs.items()})
        self.assertEqual(normalized["preset"], PRESET)

    def test_unprivileged_jobs_do_not_receive_oidc_environments_secrets_or_persisted_checkout_tokens(self):
        for name in ("validate", "application", "harness", "build-ready"):
            job = WORKFLOW["jobs"][name]
            self.assertEqual(job["permissions"], {"contents": "read"})
            self.assertNotIn("environment", job)
            for step in job["steps"]:
                self.assertNotIn("secrets.", str(step))
                self.assertNotIn("${{", step.get("run", ""))
                if step.get("uses", "").startswith("actions/checkout@"):
                    self.assertEqual(step["with"]["persist-credentials"], "false")

    def test_build_sources_are_immutable_and_both_architectures_use_native_runners(self):
        application = WORKFLOW["jobs"]["application"]
        checkout = [step for step in application["steps"] if step.get("with", {}).get("path") == "application"][0]
        self.assertEqual(checkout["with"]["ref"], "${{ github.sha }}")
        harness = WORKFLOW["jobs"]["harness"]
        self.assertEqual(harness["steps"][0]["with"]["ref"], "${{ needs.validate.outputs.controller_revision }}")
        for job in (application, harness):
            self.assertEqual({row["architecture"]: row["runner"] for row in job["strategy"]["matrix"]["include"]},
                             {"x86_64": "ubuntu-24.04", "arm64": "ubuntu-24.04-arm"})
        for job in WORKFLOW["jobs"].values():
            for step in job["steps"]:
                if step.get("uses", "").startswith("actions/upload-artifact@"):
                    self.assertEqual(int(step["with"]["retention-days"]), PRESET["lifecycle"]["githubArtifactRetentionDays"])

    def test_opt_in_harness_parent_remains_in_release_updates(self):
        config = prepare.read(ROOT / "release-please-config.json")
        self.assertIn({"type": "generic", "path": "performance/harness/pom.xml"}, config["packages"]["."]["extra-files"])
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        parent_version = ET.parse(ROOT / "pom.xml").getroot().find("m:version", namespace).text
        harness_path = ROOT / "performance/harness/pom.xml"
        self.assertEqual(ET.parse(harness_path).getroot().find("m:parent/m:version", namespace).text, parent_version)
        self.assertIn("<version>" + parent_version + "</version> <!-- x-release-please-version -->", harness_path.read_text())


if __name__ == "__main__":
    unittest.main()
