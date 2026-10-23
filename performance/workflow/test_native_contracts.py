"""Native mode/artifact contracts. Fixtures are data only; no native code or AWS operations execute."""

from copy import deepcopy
import json
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import prepare
import test_prepare
from application_runtime import validate_native_build, validate_native_settings


def elf(architecture):
    header = bytearray(64)
    header[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", header, 16, 3, {"x86_64": 62, "arm64": 183}[architecture])
    return bytes(header)


def identity(architecture):
    return {"schemaVersion": 1, "architecture": architecture,
            "builderImage": test_prepare.PRESET["runtime"]["native"]["builderImage"],
            "compilerVersion": "native-image 25.0.1 Mandrel Distribution",
            "buildArguments": ["-march=compatibility"]}


class NativeContractTests(unittest.TestCase):
    def fixture(self, directory, architecture):
        root = Path(directory)
        checkout = root / "application-checkout"
        target = checkout / "sendium-app/target"
        target.mkdir(parents=True)
        (target / "sendium-runner").write_bytes(elf(architecture))
        (target / "performance-native-build.json").write_text(json.dumps(identity(architecture)))
        req = test_prepare.request({"application_runtime": "native"})
        output = root / "bundles" / ("application-" + architecture)
        output.parent.mkdir()
        return req, checkout, target, output

    def build(self, req, checkout, output, architecture):
        with patch("prepare.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout=prepare.revision(req, "application"))):
            return prepare.build_bundle(req, "application", architecture, checkout, output, "25.0.1", machine=architecture)

    def test_jvm_remains_default_and_unknown_runtime_cannot_become_a_command(self):
        self.assertEqual(test_prepare.request()["preset"]["runtime"]["applicationRuntime"], "jvm")
        self.assertTrue(test_prepare.request({"application_runtime": "native"})["preset"]["workload"]["dlrEnabled"])
        for value in ("docker", "native; false", "", True, ["native"]):
            with self.subTest(value=value), self.assertRaises(ValueError):
                test_prepare.request({"application_runtime": value})

    def test_native_package_has_fixed_executable_path_and_architecture_identity(self):
        for architecture in ("x86_64", "arm64"):
            with self.subTest(architecture=architecture), tempfile.TemporaryDirectory() as directory:
                req, checkout, _, output = self.fixture(directory, architecture)
                manifest = self.build(req, checkout, output, architecture)
                self.assertEqual(manifest["applicationRuntime"], "native")
                self.assertEqual(manifest["nativeBuild"], identity(architecture))
                with tarfile.open(output / "application.tar.gz") as archive:
                    self.assertEqual(archive.getnames(), ["native/application"])
                    self.assertEqual(archive.getmember("native/application").mode, 0o755)
                    self.assertEqual(archive.extractfile("native/application").read(), elf(architecture))
                self.assertEqual(prepare.verify_bundle(req, output.parent, "application", architecture)["sha256"], manifest["sha256"])
                with self.assertRaisesRegex(ValueError, "manifest fields"):
                    prepare.verify_bundle(test_prepare.request(), output.parent, "application", architecture)

    def test_jvm_application_cannot_be_substituted_for_requested_native_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            _, output = test_prepare.ArtifactTests().fixtures(directory)
            with self.assertRaisesRegex(ValueError, "manifest fields"):
                prepare.verify_bundle(test_prepare.request({"application_runtime": "native"}), output, "application", "arm64")

    def test_wrong_elf_architecture_and_script_are_rejected_before_archiving(self):
        for content in (elf("x86_64"), b"#!/bin/sh\nexit 0\n"):
            with self.subTest(content=content[:8]), tempfile.TemporaryDirectory() as directory:
                req, checkout, target, output = self.fixture(directory, "arm64")
                (target / "sendium-runner").write_bytes(content)
                with self.assertRaisesRegex(ValueError, "ELF"):
                    self.build(req, checkout, output, "arm64")
                self.assertFalse((output / "build-manifest.json").exists())

    def test_native_builder_flags_compiler_and_architecture_must_match_request(self):
        for change in ({"architecture": "x86_64"}, {"schemaVersion": True}, {"compilerVersion": "javac 25"},
                       {"builderImage": "quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25"},
                       {"buildArguments": ["-march=native"]}, {"unexpected": "field"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_native_build({**identity("arm64"), **change}, test_prepare.request({"application_runtime": "native"})["preset"], "arm64")

    def test_native_runtime_has_explicit_bounded_heap_without_jvm_gc_flags(self):
        for arguments in (["-Xmx2048m"], ["-Xmx512m", "-Xms1g"], ["-Xmx512m", "-Xmx256m"],
                          ["-Xmx512m", "-XX:+UseG1GC"], []):
            preset = deepcopy(test_prepare.PRESET)
            preset["runtime"]["native"]["arguments"] = arguments
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                validate_native_settings(preset)

    def test_native_application_does_not_change_the_jvm_harness_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            req, _, _, output = self.fixture(directory, "arm64")
            checkout = Path(directory) / "harness-checkout"
            target = checkout / "performance/harness/target"
            target.mkdir(parents=True)
            (target / "sendium-performance-harness-fixture.jar").write_bytes(b"harness")
            bundle = output.parent / "harness-arm64"
            with patch("prepare.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout=prepare.revision(req, "harness"))):
                prepare.build_bundle(req, "harness", "arm64", checkout, bundle, "25", machine="arm64")
            manifest = prepare.verify_bundle(req, output.parent, "harness", "arm64")
            self.assertNotIn("nativeBuild", manifest)
            self.assertNotIn("applicationRuntime", manifest)
            self.assertEqual((bundle / "harness.jar").read_bytes(), b"harness")


if __name__ == "__main__":
    unittest.main()
