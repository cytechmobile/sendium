"""Public evidence preservation, runtime identity, and packaging boundary tests."""

import hashlib
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import archive_package
import prepare
import publish
from test_prepare import request
from test_publication import measured
from test_native_contracts import identity


class ArchivePackageTests(unittest.TestCase):
    def fixture(self, root, req=None, report=None):
        if req is None:
            req, report = measured()
        source = root / "published"
        source.mkdir()
        prepare.write(source / "results.json", report)
        (source / "summary.md").write_bytes(b"# Original summary\n")
        prepare.write(source / "workflow-outcome.json", {"schemaVersion": 1, "runIdentity": req["runIdentity"]})
        (source / "evidence").mkdir()
        (source / "evidence/rds-metrics.json").write_bytes(b'{"schemaVersion":1,"metrics":[]}\n')
        (source / "evidence/sendium-resources.jsonl").write_bytes(b'{"sample":1}\n')
        (source / "diagnostics").mkdir()
        (source / "diagnostics/sendium-process.log").write_bytes(b"original process log\n")
        (source / "_work").mkdir()
        (source / "_work/private-input.json").write_bytes(b"excluded work file")
        return req, source

    def test_preserves_original_bytes_rds_evidence_and_checksums_in_readable_files_and_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, source = self.fixture(root)
            before = {path.relative_to(source): path.read_bytes() for path in source.rglob("*") if path.is_file()}
            output = root / "archive"
            manifest = archive_package.package(req, source, output)
            self.assertEqual(manifest["prefix"], "runs/cytechmobile/sendium/12345/2/")
            self.assertEqual(manifest["applicationRuntime"], "jvm")  # Legacy reports omit runtime.
            self.assertEqual(manifest["missingFiles"], [])
            with tarfile.open(output / "evidence.tar.gz") as bundle:
                self.assertEqual(sorted(bundle.getnames()), sorted(item["path"] for item in manifest["files"]))
                for item in manifest["files"]:
                    contents = before[Path(item["path"])]
                    self.assertEqual((output / item["path"]).read_bytes(), contents)
                    self.assertEqual(bundle.extractfile(item["path"]).read(), contents)
                    self.assertEqual(item["sha256"], hashlib.sha256(contents).hexdigest())
                    self.assertEqual(item["sizeBytes"], len(contents))
            self.assertFalse((output / "_work").exists())
            self.assertEqual(manifest["bundle"]["sha256"], hashlib.sha256((output / "evidence.tar.gz").read_bytes()).hexdigest())
            self.assertEqual(before, {path.relative_to(source): path.read_bytes() for path in source.rglob("*") if path.is_file()})
            second = archive_package.package(req, source, root / "retry")
            self.assertEqual(second["bundle"], manifest["bundle"])

    def test_native_smpp_and_failed_setup_keep_their_original_status(self):
        for status in ("failed", "inconclusive"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                req = request({"application_runtime": "native", "ingress_mode": "smpp"})
                report = publish.unavailable(req, "SETUP_FAILURE")
                if status == "inconclusive":
                    report["validity"] = {"status": status, "exitCode": 0, "reasons": []}
                root = Path(directory)
                req, source = self.fixture(root, req, report)
                manifest = archive_package.package(req, source, root / "archive")
                self.assertEqual(manifest["observationStatus"], status)
                self.assertEqual(manifest["applicationRuntime"], "native")
                self.assertEqual(manifest["ingressMode"], "smpp")
                self.assertFalse(manifest["observationAvailable"])

    def test_measured_native_identity_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            req, report = measured()
            req["preset"]["runtime"]["applicationRuntime"] = "native"
            report["configuration"]["application"].update(runtime="native", jvmArguments=[],
                nativeArguments=req["preset"]["runtime"]["native"]["arguments"], nativeBuild=identity("arm64"))
            report["configuration"]["infrastructure"]["sendium"]["architecture"] = "arm64"
            root = Path(directory)
            req, source = self.fixture(root, req, report)
            archive_package.package(req, source, root / "archive")
            self.assertEqual(publish.read(root / "archive/results.json"), report)

    def test_missing_optional_summary_and_workflow_are_disclosed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, source = self.fixture(root)
            (source / "summary.md").unlink()
            (source / "workflow-outcome.json").unlink()
            manifest = archive_package.package(req, source, root / "archive")
            self.assertEqual(manifest["missingFiles"], ["summary.md", "workflow-outcome.json"])

    def test_rejects_mismatched_report_and_workflow_without_creating_package(self):
        for target in ("results.json", "workflow-outcome.json"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                req, source = self.fixture(root)
                document = publish.read(source / target)
                if target == "results.json":
                    document["provenance"]["runIdentity"] = "gh-99999-1"
                else:
                    document["runIdentity"] = "gh-99999-1"
                (source / target).unlink()
                prepare.write(source / target, document)
                with self.assertRaises(ValueError):
                    archive_package.package(req, source, root / "archive")
                self.assertFalse((root / "archive").exists())

    def test_rejects_over_budget_evidence_and_output_inside_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, source = self.fixture(root)
            with patch.object(archive_package, "MAX_TOTAL_BYTES", 1), self.assertRaisesRegex(ValueError, "byte budget"):
                archive_package.package(req, source, root / "archive")
            with self.assertRaisesRegex(ValueError, "outside"):
                archive_package.package(req, source, source / "archive")
            with patch.object(archive_package, "MAX_FILES", 1), self.assertRaisesRegex(ValueError, "file count"):
                archive_package.package(req, source, root / "archive")

    def test_rejects_linked_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, source = self.fixture(root)
            link = source / "evidence/external.json"
            try:
                link.symlink_to(source / "results.json")
            except OSError:
                self.skipTest("Creating symlinks requires Windows developer mode or elevation")
            with self.assertRaisesRegex(ValueError, "links"):
                archive_package.package(req, source, root / "archive")


if __name__ == "__main__":
    unittest.main()
