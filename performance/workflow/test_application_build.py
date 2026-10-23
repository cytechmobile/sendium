"""Build-mode commands and identity failures; native fixtures are ELF data, never executed."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build_selected_application as builder
import prepare
from application_runtime import CREDENTIAL_ENVIRONMENT
from test_native_contracts import elf
from test_prepare import CONTEXT, WORKFLOW, request


class ApplicationBuildTests(unittest.TestCase):
    def process(self, checkout, architecture, calls, fail_build=False, compiler="native-image 25.0.1 Mandrel Distribution"):
        def run(command, **options):
            calls.append(command)
            if command[0] == "git":
                return subprocess.CompletedProcess(command, 0, stdout=CONTEXT["applicationCommit"], stderr="")
            if command[:2] == ["docker", "run"]:
                self.assertIn("--network", command)
                self.assertIn("none", command)
                self.assertIn("--pull=never", command)
                return subprocess.CompletedProcess(command, 0, stdout=compiler, stderr="")
            if command[0] == "bash":
                if fail_build:
                    raise subprocess.CalledProcessError(1, command)
                if "-Pnative" in command:
                    target = checkout / "sendium-app/target"
                    target.mkdir(parents=True)
                    (target / "application-runner").write_bytes(elf(architecture))
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
        return run

    def test_jvm_build_preserves_existing_command_without_native_toolchain(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            checkout, calls = Path(directory), []
            with patch.object(builder.subprocess, "run", side_effect=self.process(checkout, "x86_64", calls)):
                result = builder.build(request(), checkout, "x86_64", "25.0.1", machine="x86_64", system="Linux")
            self.assertIsNone(result)
            self.assertEqual(calls[-1], ["bash", "./mvnw", "-B", "-ntp", "-DskipTests", "-pl", "sendium-app", "-am", "package"])
            self.assertFalse(any(command[0] == "docker" for command in calls))

    def test_native_build_pins_compiler_platform_flags_and_records_real_version(self):
        for architecture, docker_platform in builder.PLATFORMS.items():
            with self.subTest(architecture=architecture), tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
                checkout, calls = Path(directory), []
                req = request({"application_runtime": "native"})
                with patch.object(builder.subprocess, "run", side_effect=self.process(checkout, architecture, calls)):
                    identity = builder.build(req, checkout, architecture, "25", machine=architecture, system="Linux")
                image = req["preset"]["runtime"]["native"]["builderImage"]
                self.assertEqual(calls[1], ["docker", "pull", "--quiet", "--platform", docker_platform, image])
                self.assertIn("-Dquarkus.native.builder-image=" + image, calls[-1])
                self.assertIn("-Dquarkus.native.additional-build-args=-march=compatibility", calls[-1])
                self.assertIn("-Dquarkus.native.remote-container-build=true", calls[-1])
                self.assertIn("-DskipITs", calls[-1])
                self.assertEqual(calls[-1][-2:], ["clean", "package"])
                self.assertEqual(identity["architecture"], architecture)
                self.assertEqual(identity["compilerVersion"], "native-image 25.0.1 Mandrel Distribution")
                self.assertEqual(json.loads((checkout / "sendium-app/target/performance-native-build.json").read_text()), identity)
                # The resulting metadata is accepted by the separate artifact packager.
                with patch.object(prepare.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=CONTEXT["applicationCommit"])):
                    manifest = prepare.build_bundle(req, "application", architecture, checkout, checkout / "bundle", "25", machine=architecture)
                self.assertEqual(manifest["nativeBuild"], identity)

    def test_cloud_credential_providers_block_build_before_any_subprocess(self):
        for key in CREDENTIAL_ENVIRONMENT:
            with self.subTest(key=key), patch.dict(os.environ, {key: "test-only-provider"}, clear=True), patch.object(builder.subprocess, "run") as process:
                with self.assertRaisesRegex(ValueError, "unprivileged"):
                    builder.build(request(), "unused", "x86_64", "25", machine="x86_64", system="Linux")
                process.assert_not_called()

    def test_wrong_runner_os_architecture_or_java_never_builds(self):
        for machine, system, java in (("x86_64", "Linux", "25"), ("arm64", "Windows", "25"), ("arm64", "Linux", "21")):
            with self.subTest(machine=machine, system=system, java=java), patch.dict(os.environ, {}, clear=True), patch.object(builder.subprocess, "run") as process:
                with self.assertRaises(ValueError):
                    builder.build(request(), "unused", "arm64", java, machine=machine, system=system)
                process.assert_not_called()

    def test_wrong_checkout_revision_never_invokes_compiler_or_maven(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(builder.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout="ef" * 20)) as process:
            with self.assertRaisesRegex(ValueError, "revision mismatch"):
                builder.build(request({"application_runtime": "native"}), "unused", "arm64", "25", machine="arm64", system="Linux")
            self.assertEqual(process.call_count, 1)

    def test_failed_or_wrong_architecture_native_build_never_writes_identity(self):
        for fail_build, output_architecture in ((True, "arm64"), (False, "x86_64")):
            with self.subTest(fail_build=fail_build), tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
                checkout, calls = Path(directory), []
                with patch.object(builder.subprocess, "run", side_effect=self.process(checkout, output_architecture, calls, fail_build=fail_build)):
                    with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                        builder.build(request({"application_runtime": "native"}), checkout, "arm64", "25", machine="arm64", system="Linux")
                self.assertFalse((checkout / "sendium-app/target/performance-native-build.json").exists())

    def test_invalid_compiler_identity_fails_before_selected_application_build(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            checkout, calls = Path(directory), []
            with patch.object(builder.subprocess, "run", side_effect=self.process(checkout, "arm64", calls, compiler="native-image 21.0.1")):
                with self.assertRaisesRegex(ValueError, "compiler identity"):
                    builder.build(request({"application_runtime": "native"}), checkout, "arm64", "25", machine="arm64", system="Linux")
            self.assertFalse(any(command[0] == "bash" for command in calls))

    def test_workflow_uses_pinned_helper_without_exposing_native_execution_yet(self):
        job = WORKFLOW["jobs"]["application"]
        build = next(step for step in job["steps"] if step.get("name") == "Build packaged application without AWS credentials")
        self.assertIn("control/performance/workflow/build_selected_application.py", build["run"])
        self.assertIn('"$BUILD_ARCHITECTURE"', build["run"])
        self.assertNotIn("application_runtime", WORKFLOW["on"]["workflow_dispatch"]["inputs"])
        self.assertNotIn("environment", job)
        self.assertEqual(job["permissions"], {"contents": "read"})


if __name__ == "__main__":
    unittest.main()
