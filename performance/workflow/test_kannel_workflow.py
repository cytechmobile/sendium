"""Kannel dispatch, native builds, protected transport and publication contracts."""

from copy import deepcopy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import archive_package
import archive_upload
import build_selected_application
import prepare
import publish
import recover
import run_aws
import test_aws
import test_prepare
import test_publication


def request(**inputs):
    return test_prepare.request({"benchmark_target": "kannel", **inputs})


def identity(architecture="arm64"):
    return {"schemaVersion": 1, "role": "gateway", "postgresqlSupport": True,
            "architecture": {"arm64": "aarch64", "x86_64": "x86_64"}[architecture],
            "source": deepcopy(prepare.KANNEL_SOURCE), "binarySha256": {"bearerbox": "ab" * 32, "smsbox": "cd" * 32}}


def measured():
    _, report = test_publication.measured()
    req = request(offered_rate=1, measurement_seconds=1, warmup_seconds=0, dlr_enabled=False)
    empty = publish.unavailable(req, "FIXTURE")
    report["provenance"] = empty["provenance"]
    report["configuration"]["application"] = empty["configuration"]["application"]
    infrastructure = report["configuration"]["infrastructure"]
    infrastructure["kannel"] = infrastructure.pop("sendium")
    infrastructure["database"].update(provisioned=True, engine="postgresql", sslMode="verify-full")
    report["resources"]["kannel"] = report["resources"].pop("sendium")
    publish.validate(report, req)
    return req, report


class KannelWorkflowTests(unittest.TestCase):
    def test_dispatch_is_archive_based_and_builds_maintained_code(self):
        req = request()
        self.assertIsNone(req["provenance"]["applicationCommit"])
        self.assertEqual(req["dispatchRevision"], test_prepare.CONTEXT["applicationCommit"])
        self.assertEqual(prepare.revision(req, "application"), req["controllerRevision"])
        self.assertEqual(req["applicationSource"], prepare.KANNEL_SOURCE)
        self.assertEqual(req["preset"]["aws"]["instanceDefaults"], {"sendium": "t4g.small", "harness": "t4g.small"})
        for invalid in ({"ingress_mode": "smpp"}, {"application_runtime": "native"}, {"benchmark_target": "unknown"}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                request(**invalid)

    def test_unprivileged_build_uses_native_architecture_and_verifies_dependency_closure(self):
        req = request()
        checkout = Path("checkout")
        with patch.object(build_selected_application.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=req["controllerRevision"])) as run, \
                patch.object(prepare, "read", return_value=identity()):
            result = build_selected_application.build(req, checkout, "arm64", "25.0.1+9", machine="aarch64", system="Linux")
        self.assertEqual(result, identity())
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands), 4)
        self.assertTrue(all("linux/arm64" in command for command in commands[1:]))
        self.assertIn("verification", commands[2])
        self.assertIn("export", commands[3])
        self.assertFalse(any("mvnw" in " ".join(command) for command in commands))

    def test_bundle_checks_source_architecture_and_transported_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req = request()
            source = root / "checkout/performance/kannel/target/aws-runtime"
            source.mkdir(parents=True)
            (source / "kannel.tar.gz").write_bytes(b"opaque archive bytes; never executed by controller")
            prepare.write(source / "build-metadata.json", identity())
            output = root / "bundles/application-arm64"
            output.parent.mkdir()
            with patch.object(prepare.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=req["controllerRevision"])):
                manifest = prepare.build_bundle(req, "application", "arm64", root / "checkout", output, "25.0.1+9", machine="aarch64")
            self.assertEqual(manifest["applicationRuntime"], "kannel")
            self.assertEqual(manifest["kannelBuild"], identity())
            self.assertEqual(prepare.verify_bundle(req, output.parent, "application", "arm64")["sourceRevision"], req["controllerRevision"])
            (output / "application.tar.gz").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                prepare.verify_bundle(req, output.parent, "application", "arm64")

    def test_protected_execution_stages_kannel_without_a_gateway_jre(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req = request()
            inputs = {"artifacts": [{"component": component, "architecture": "arm64", "path": component, "sha256": "ab" * 32,
                                     **({"kannelBuild": identity()} if component == "application" else {})}
                                    for component in ("application", "harness")]}
            resolved = test_aws.resolve_aws.resolve(test_aws.RegionalAws(), req["preset"], "eu-west-1")
            details = {"java": {"harness": {"release": "jdk-25.0.4.1+1"}}}
            cleanup = {"runIdentity": req["runIdentity"], "runStack": "fixture", "region": "eu-west-1"}
            with patch.object(run_aws, "preflight", return_value=(cleanup, {}, "aws")), \
                    patch.object(run_aws.resolve_aws, "resolve", return_value=resolved), \
                    patch.object(run_aws.runtime_assets, "resolve", return_value=({"harnessJre": {}, "rdsCa": {}}, details)) as assets, \
                    patch.object(run_aws.deploy, "stage", return_value=cleanup) as stage, patch.object(run_aws, "create_stack"), \
                    patch.object(run_aws.deploy, "execute", return_value={"exitCode": 0}), \
                    patch.object(run_aws, "stack_events", return_value={}), patch.object(run_aws.deploy, "retrieve"):
                result = run_aws.run(Mock(), Mock(), req, inputs, root, {"region": "eu-west-1"}, root / "controller", verify_builds=lambda *_: inputs)
            self.assertEqual(result["exitCode"], 0)
            self.assertEqual(assets.call_args.kwargs["target"], "kannel")
            plan = stage.call_args.args[1]
            self.assertEqual(plan["benchmarkTarget"], "kannel")
            self.assertEqual(plan["kannelBuild"], identity())
            self.assertNotIn("sendiumJre", plan["artifacts"])

    def test_missing_observation_does_not_claim_database_provisioning(self):
        req = request()
        report = publish.unavailable(req, "BUILD_FAILED")
        publish.validate(report, req)
        self.assertIsNone(report["configuration"]["infrastructure"]["database"]["provisioned"])
        self.assertEqual(report["resources"]["kannel"], [])
        markdown = publish.fallback_markdown(report)
        self.assertIn("# Kannel performance results", markdown)
        self.assertIn("Observation unavailable", markdown)
        self.assertIn(prepare.KANNEL_SOURCE["archiveSha256"], markdown)
        self.assertNotIn("Sendium", markdown)

    def test_measured_report_cannot_use_unverified_database_or_a_different_source(self):
        req, report = measured()
        report["configuration"]["infrastructure"]["database"]["provisioned"] = None
        with self.assertRaises(prepare.ValidationError):
            publish.validate(report, req)
        req, report = measured()
        report["configuration"]["application"]["source"]["archiveSha256"] = "ab" * 32
        with self.assertRaisesRegex(ValueError, "source/storage mismatch"):
            publish.validate(report, req)

    def test_publisher_preserves_group_evidence_with_gateway_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, report = measured()
            (root / "retrieved/harness").mkdir(parents=True)
            (root / "retrieved/sendium").mkdir()
            prepare.write(root / "retrieved/harness/results.json", report)
            (root / "retrieved/sendium/kannel-resources.jsonl").write_text("{}\n")
            with patch.object(publish, "finalize", return_value=(deepcopy(report), "# Kannel\n")) as finalize:
                publish.publish(req, root, root, root, root, {}, {}, root / "published")
            resources = finalize.call_args.args[5]
            self.assertTrue(resources["gatewaySamples"].endswith("kannel-resources.jsonl"))
            self.assertNotIn("sendiumSamples", resources)

    def test_archive_carries_and_rechecks_source_and_storage_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req = request()
            source = root / "published"
            source.mkdir()
            report = publish.unavailable(req, "SETUP_FAILED")
            prepare.write(source / "results.json", report)
            (source / "summary.md").write_text(publish.fallback_markdown(report))
            manifest = archive_package.package(req, source, root / "package")
            self.assertEqual(manifest["benchmarkTarget"], "kannel")
            self.assertEqual(manifest["applicationSource"], prepare.KANNEL_SOURCE)
            archive_upload.checked_manifest(req, root / "package")
            manifest["dlrStorage"] = "internal"
            (root / "package/manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "Kannel identity"):
                archive_upload.checked_manifest(req, root / "package")

    def test_diagnostics_maps_gateway_role_to_kannel_credit_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            req, report = measured()
            infrastructure = report["configuration"]["infrastructure"]
            infrastructure["region"] = "eu-west-1"
            for source, instance in (("kannel", "i-12345678"), ("harness", "i-23456789")):
                infrastructure[source].update(instanceId=instance, instanceType="t4g.small")
            (root / "retrieved/harness").mkdir(parents=True)
            prepare.write(root / "retrieved/harness/results.json", report)
            actual = [{"InstanceId": infrastructure[source]["instanceId"], "InstanceType": "t4g.small", "Tags": [
                {"Key": key, "Value": value} for key, value in {"sendium:managed-by": "performance-tests",
                "sendium:repository": req["repository"], "sendium:run-identity": req["runIdentity"],
                "sendium:role": "sendium" if source == "kannel" else "harness"}.items()]} for source in ("kannel", "harness")]
            aws = Mock(call=Mock(return_value={"Reservations": [{"Instances": actual}]}))
            with patch.object(recover.deploy, "retrieve", return_value={}), \
                    patch.object(recover.collect_cpu_credits, "collect", return_value={}) as collect:
                result = recover.diagnostics(aws, Mock(), {"region": "eu-west-1"}, req, root)
            self.assertEqual(result["exitCode"], 0)
            self.assertEqual(collect.call_args.args[1], {"kannel": "i-12345678", "harness": "i-23456789"})
