"""Offline regional resolution, protected orchestration and runtime-byte contracts."""

from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import yaml

import prepare
import resolve_aws
import run_aws
import runtime_assets
from aws_cli import AwsError
import test_prepare
from test_prepare import CONTEXT, PRESET, ROOT, WORKFLOW, request


class RegionalAws:
    def __init__(self):
        self.calls = []
        self.responses = {
            "describe-instance-types": {"InstanceTypes": [self.instance("t4g.small", "arm64", True), self.instance("m7i.large", "x86_64", False)]},
            "describe-availability-zones": {"AvailabilityZones": [{"ZoneName": "eu-west-1" + suffix, "ZoneId": "euw1-az" + str(index),
                "State": "available", "ZoneType": "availability-zone", "OptInStatus": "opt-in-not-required"} for index, suffix in enumerate(("a", "b"), 1)]},
            "describe-vpc-endpoint-services": {"ServiceDetails": [{"ServiceName": "com.amazonaws.eu-west-1." + service,
                "ServiceType": [{"ServiceType": "Gateway" if service == "s3" else "Interface"}],
                "AvailabilityZones": ["eu-west-1a", "eu-west-1b"], "PrivateDnsName": service + ".eu-west-1.amazonaws.com"}
                for service in ("ssm", "ssmmessages", "secretsmanager", "s3")]},
            "describe-db-engine-versions": {"DBEngineVersions": [{"Status": "available", "EngineVersion": version} for version in ("17.5", "17.12", "17.9", "18.1")]},
            "describe-orderable-db-instance-options": {"OrderableDBInstanceOptions": [{"StorageType": "gp3", "SupportsStorageEncryption": True,
                "Vpc": True, "MinStorageSize": 20, "MaxStorageSize": 16384, "AvailabilityZones": [{"Name": "eu-west-1a"}, {"Name": "eu-west-1b"}]}]},
            "describe-managed-prefix-lists": {"PrefixLists": [{"OwnerId": "AWS", "AddressFamily": "IPv4", "PrefixListId": "pl-12345678"}]}}

    @staticmethod
    def instance(name, architecture, burstable):
        return {"InstanceType": name, "ProcessorInfo": {"SupportedArchitectures": [architecture]}, "MemoryInfo": {"SizeInMiB": 2048},
                "VCpuInfo": {"DefaultVCpus": 2}, "SupportedRootDeviceTypes": ["ebs"], "SupportedVirtualizationTypes": ["hvm"],
                "SupportedUsageClasses": ["on-demand"], "SupportedBootModes": ["uefi"], "BurstablePerformanceSupported": burstable}

    def call(self, service, operation, **parameters):
        self.calls.append((service, operation, parameters))
        if operation == "get-parameter":
            return {"Parameter": {"Name": parameters["name"], "Version": 7, "Value": "ami-11111111" if parameters["name"].endswith("arm64") else "ami-22222222"}}
        if operation == "describe-images":
            image = parameters["image_ids"][0]
            return {"Images": [{"ImageId": image, "State": "available", "Architecture": "arm64" if image == "ami-11111111" else "x86_64",
                "RootDeviceType": "ebs", "VirtualizationType": "hvm", "Name": "al2023-ami-approved", "ImageOwnerAlias": "amazon",
                "OwnerId": "137112412989", "RootDeviceName": "/dev/xvda", "BootMode": "uefi-preferred",
                "BlockDeviceMappings": [{"DeviceName": "/dev/xvda", "Ebs": {"VolumeSize": 8, "SnapshotId": "snap-12345678"}}]}]}
        if operation == "describe-instance-type-offerings":
            name = parameters["filters"][0]["Values"][0]
            return {"InstanceTypeOfferings": [{"InstanceType": name, "Location": zone} for zone in ("eu-west-1a", "eu-west-1b")]}
        return deepcopy(self.responses[operation])


class ResolutionTests(unittest.TestCase):
    def test_s3_gateway_and_interface_records_do_not_overwrite_each_other(self):
        for reverse in (False, True):
            with self.subTest(reverse=reverse):
                aws = RegionalAws()
                details = aws.responses["describe-vpc-endpoint-services"]["ServiceDetails"]
                gateway = details[-1]
                gateway.pop("PrivateDnsName")
                interface = {"ServiceName": gateway["ServiceName"], "ServiceType": [{"ServiceType": "Interface"}],
                             "AvailabilityZones": [], "PrivateDnsName": "*.s3.eu-west-1.amazonaws.com"}
                details.append(interface)
                if reverse:
                    details.reverse()
                result = resolve_aws.resolve(aws, PRESET, "eu-west-1")
                self.assertEqual(result["placement"]["primary"], "eu-west-1a")
                self.assertEqual(len(result["endpointServices"]), 4)

    def test_missing_gateway_and_ambiguous_interface_records_are_rejected(self):
        for mutation in ("missing-gateway", "ambiguous-interface"):
            with self.subTest(mutation=mutation):
                aws = RegionalAws()
                details = aws.responses["describe-vpc-endpoint-services"]["ServiceDetails"]
                if mutation == "missing-gateway":
                    details[-1]["ServiceType"] = [{"ServiceType": "Interface"}]
                else:
                    details.append(deepcopy(details[0]))
                with self.assertRaisesRegex(ValueError, "endpoint type is unavailable or ambiguous"):
                    resolve_aws.resolve(aws, PRESET, "eu-west-1")

    def test_small_defaults_resolve_ami_minimum_and_latest_numeric_postgres(self):
        aws = RegionalAws()
        result = resolve_aws.resolve(aws, PRESET, "eu-west-1")
        self.assertEqual(result["nodes"]["sendium"]["architecture"], "arm64")
        self.assertEqual(result["nodes"]["harness"]["rootVolumeGiB"], 8)
        self.assertEqual(result["database"]["engineVersion"], "17.12")
        self.assertEqual(result["placement"], {"primary": "eu-west-1a", "primaryZoneId": "euw1-az1", "secondary": "eu-west-1b"})
        self.assertEqual(len([call for call in aws.calls if call[1] == "get-parameter"]), 1)
        self.assertTrue(all(operation.startswith("describe-") or operation == "get-parameter" for _, operation, _ in aws.calls))

    def test_mixed_architectures_are_derived_from_metadata(self):
        preset = deepcopy(PRESET)
        preset["aws"]["instanceDefaults"]["harness"] = "m7i.large"
        result = resolve_aws.resolve(RegionalAws(), preset, "eu-west-1")
        self.assertEqual(result["nodes"]["harness"]["architecture"], "x86_64")
        self.assertEqual(result["nodes"]["sendium"]["architecture"], "arm64")

    def test_endpoint_zones_participate_in_common_placement(self):
        aws = RegionalAws()
        aws.responses["describe-vpc-endpoint-services"]["ServiceDetails"][0]["AvailabilityZones"] = ["eu-west-1b"]
        self.assertEqual(resolve_aws.resolve(aws, PRESET, "eu-west-1")["placement"]["primary"], "eu-west-1b")

    def test_unavailable_memory_storage_and_dns_fail_before_creation(self):
        for mutation in (lambda aws: aws.responses["describe-instance-types"]["InstanceTypes"][0]["MemoryInfo"].update(SizeInMiB=1024),
                         lambda aws: aws.responses["describe-orderable-db-instance-options"]["OrderableDBInstanceOptions"][0].update(MinStorageSize=400),
                         lambda aws: aws.responses["describe-vpc-endpoint-services"]["ServiceDetails"][0].update(PrivateDnsName="")):
            with self.subTest(mutation=mutation):
                aws = RegionalAws()
                mutation(aws)
                with self.assertRaises(ValueError):
                    resolve_aws.resolve(aws, PRESET, "eu-west-1")

    def test_pinned_unavailable_database_is_not_silently_replaced(self):
        preset = deepcopy(PRESET)
        preset["database"]["engineVersion"] = "17.99"
        with self.assertRaisesRegex(ValueError, "Pinned database"):
            resolve_aws.resolve(RegionalAws(), preset, "eu-west-1")

    def test_repeated_pagination_tokens_are_bounded(self):
        aws = Mock(call=lambda *_args, **_kwargs: {"Items": [], "NextToken": "same"})
        with self.assertRaisesRegex(ValueError, "Repeated"):
            resolve_aws.pages(aws, "ec2", "describe", "Items")


def java_asset(architecture="aarch64", pinned=False):
    binary = {"architecture": architecture, "image_type": "jre", "os": "linux", "jvm_impl": "hotspot",
              "package": {"checksum": hashlib.sha256(b"runtime").hexdigest(), "size": 7,
                          "link": "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/runtime.tar.gz"}}
    return {"release_name": "jdk-25.0.4.1+1", "version_data" if pinned else "version": {"major": 25},
            "binaries" if pinned else "binary": [binary] if pinned else binary}


class RuntimeTests(unittest.TestCase):
    def test_real_latest_and_release_name_response_shapes_are_supported(self):
        downloads = Mock(metadata=lambda url: java_asset("x64", True) if "release_name" in url else [java_asset()])
        first = runtime_assets.jre_metadata(downloads, "arm64")
        second = runtime_assets.jre_metadata(downloads, "x86_64", first["release"])
        self.assertEqual(first["release"], second["release"])
        self.assertEqual(second["architecture"], "x86_64")

    def test_download_checks_hash_and_removes_only_new_invalid_files(self):
        with tempfile.TemporaryDirectory() as directory:
            downloads = runtime_assets.Downloads()
            target = Path(directory) / "runtime"
            with patch.object(downloads, "open", return_value=io.BytesIO(b"runtime")):
                result = downloads.download("https://github.com/file", target, 20, checksum=hashlib.sha256(b"runtime").hexdigest(), size=7)
            self.assertEqual(result["size"], 7)
            with self.assertRaises(FileExistsError):
                downloads.download("https://github.com/file", target, 20)
            self.assertEqual(target.read_bytes(), b"runtime")
            target.unlink()
            with patch.object(downloads, "open", return_value=io.BytesIO(b"changed")):
                with self.assertRaises(ValueError):
                    downloads.download("https://github.com/file", target, 20, checksum="a" * 64)
            self.assertFalse(target.exists())

    def test_unapproved_sources_or_wrong_architecture_are_rejected(self):
        for url in ("http://github.com/file", "https://untrusted.invalid/file", "https://user:password@github.com/file"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                runtime_assets.validate_url(url)
        with self.assertRaises(ValueError):
            runtime_assets.jre_metadata(Mock(metadata=lambda _: [java_asset("x64")]), "arm64")

    def test_same_architecture_runtime_is_fetched_once_and_ca_is_hashed(self):
        with tempfile.TemporaryDirectory() as directory:
            downloads = Mock()
            downloads.metadata.return_value = [java_asset()]
            def download(url, path, maximum, **kwargs):
                content = b"-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----" if "truststore" in url else b"runtime"
                Path(path).write_bytes(content)
                return {"path": str(path), "sha256": hashlib.sha256(content).hexdigest(), "size": len(content), "url": url}
            downloads.download.side_effect = download
            assets, detail = runtime_assets.resolve(downloads, {source: {"architecture": "arm64"} for source in ("sendium", "harness")}, PRESET, Path(directory) / "runtime")
            self.assertEqual(downloads.metadata.call_count, 1)
            self.assertEqual(downloads.download.call_count, 2)
            self.assertEqual(assets["sendiumJre"], assets["harnessJre"])
            self.assertIn("sha256", detail["rdsCa"])


class ControllerTests(unittest.TestCase):
    def test_bootstrap_preflight_requires_installed_expiry_and_exact_assumed_role(self):
        role = "arn:aws:iam::123456789012:role/sendium-perf-orchestrator"
        settings = {"region": "eu-west-1", "bootstrapStack": "bootstrap", "roleArn": role}
        output = {"RunStackPrefix": "sendium-perf-run-", "RepositoryTag": CONTEXT["repository"], "OrchestratorRoleArn": role,
                  "GitHubEnvironmentName": "sendium-performance", "RunResourceTtlMinutes": "240", "ArtifactBucketName": "bucket",
                  "ExpiryHandlerInstalled": "true"}
        boot = {"StackStatus": "CREATE_COMPLETE", "StackId": "arn:aws:cloudformation:eu-west-1:123456789012:stack/bootstrap/id",
                "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in output.items()]}
        def call(service, operation, **parameters):
            if operation == "describe-stacks":
                if parameters["stack_name"] == "bootstrap":
                    return {"Stacks": [boot]}
                raise AwsError("Absent", code="StackNotFound")
            if operation == "get-caller-identity":
                return {"Account": "123456789012", "Arn": "arn:aws:sts::123456789012:assumed-role/sendium-perf-orchestrator/session"}
            return {}
        aws = Mock(call=call)
        with patch.object(run_aws, "verify_expiry") as expiry:
            plan, _, _ = run_aws.preflight(aws, request(), settings)
            self.assertEqual(plan["runStack"], "sendium-perf-run-gh-12345-2")
            expiry.assert_called_once()
        with patch.object(run_aws, "verify_expiry"):
            with self.assertRaises(ValueError):
                run_aws.preflight(aws, request(), {**settings, "roleArn": role + "-other"})
            def user_call(service, operation, **parameters):
                if operation == "get-caller-identity":
                    return {"Account": "123456789012", "Arn": "arn:aws:iam::123456789012:user/admin"}
                return call(service, operation, **parameters)
            with self.assertRaisesRegex(ValueError, "assumed orchestration role"):
                run_aws.preflight(Mock(call=user_call), request(), settings)
        boot["Outputs"].append({"OutputKey": "ExpiryHandlerInstalled", "OutputValue": "false"})
        with self.assertRaises(RuntimeError):
            run_aws.preflight(aws, request(), settings)

    def test_changed_consolidated_inputs_fail_before_any_aws_request(self):
        with tempfile.TemporaryDirectory() as directory:
            req, bundles = test_prepare.ArtifactTests().fixtures(directory)
            inputs = prepare.verify_bundles(req, bundles)
            inputs["artifacts"][0]["sha256"] = "a" * 64
            aws = Mock()
            result = run_aws.run(aws, Mock(), req, inputs, bundles, {}, Path(directory) / "controller")
            self.assertEqual(result["phase"], "build-verification")
            self.assertEqual(result["exitCode"], 1)
            aws.call.assert_not_called()

    def test_create_parameters_match_template_and_keep_staging_expiry(self):
        resolved = resolve_aws.resolve(RegionalAws(), PRESET, "eu-west-1")
        plan = {"region": "eu-west-1", "repository": CONTEXT["repository"], "runIdentity": "gh-12345-2", "runStack": "sendium-perf-run-gh-12345-2"}
        staged = {"expiresAt": "2026-10-16T20:00:00Z", "bucket": "bucket", "preset": PRESET,
                  "artifacts": {"runTemplate": {"key": "runs/gh-12345-2/templates/run.yaml"}}}
        boot = {"RunStackPrefix": "sendium-perf-run-", "RuntimeBoundaryArn": "boundary", "CloudFormationExecutionRoleArn": "service-role"}
        identifier = "arn:aws:cloudformation:eu-west-1:123456789012:stack/" + plan["runStack"] + "/id"
        captured = []
        def call(service, operation, **parameters):
            captured.append((operation, parameters))
            if operation == "create-stack":
                return {"StackId": identifier}
            created = captured[0][1]
            return {"Stacks": [{"StackId": identifier, "StackStatus": "CREATE_COMPLETE", "RoleARN": "service-role", "Tags": created["tags"]}]}
        with tempfile.TemporaryDirectory() as directory:
            run_aws.create_stack(Mock(call=call), plan, staged, resolved, boot, "aws", directory)
        parameters = {item["ParameterKey"]: item["ParameterValue"] for item in captured[0][1]["parameters"]}
        tree = yaml.compose((ROOT / "performance/infrastructure/run.yaml").read_text())
        template_parameters = next(value for key, value in tree.value if key.value == "Parameters")
        self.assertEqual(set(parameters), {key.value for key, _ in template_parameters.value})
        self.assertEqual(parameters["ExpiresAt"], staged["expiresAt"])
        self.assertEqual(parameters["SendiumRootVolumeGiB"], "8")
        self.assertEqual(captured[0][1]["client_request_token"], plan["runIdentity"])
        self.assertEqual(captured[0][1]["role_arn"], "service-role")

    def test_cloudformation_event_export_never_includes_properties_or_provider_messages(self):
        aws = Mock(call=lambda *_args, **_kwargs: {"StackEvents": [{"LogicalResourceId": "Database", "ResourceType": "AWS::RDS::DBInstance",
            "ResourceStatus": "CREATE_FAILED", "Timestamp": "2026-10-16T12:00:00Z", "ResourceProperties": "private-password",
            "ResourceStatusReason": "private-password"}]})
        self.assertNotIn("private-password", json.dumps(run_aws.stack_events(aws, "stack")))

    def test_controller_failure_preserves_cleanup_plan_and_attempts_retrieval(self):
        with tempfile.TemporaryDirectory() as directory:
            req, bundles = test_prepare.ArtifactTests().fixtures(directory)
            inputs = prepare.verify_bundles(req, bundles)
            resolved = resolve_aws.resolve(RegionalAws(), PRESET, "eu-west-1")
            plan = {"schemaVersion": 1, "region": "eu-west-1", "repository": req["repository"], "bootstrapStack": "bootstrap",
                    "runIdentity": req["runIdentity"], "runStack": "sendium-perf-run-" + req["runIdentity"]}
            metadata = {"java": {source: {"release": "jdk-25.0.4.1+1"} for source in ("sendium", "harness")}}
            staged = {**plan, "bucket": "bucket"}
            output = Path(directory) / "controller"
            with patch.object(run_aws, "preflight", return_value=(plan, {}, "aws")), \
                 patch.object(run_aws.resolve_aws, "resolve", return_value=resolved), \
                 patch.object(run_aws.runtime_assets, "resolve", return_value=({}, metadata)), \
                 patch.object(run_aws.deploy, "stage", return_value=staged), \
                 patch.object(run_aws, "create_stack", side_effect=ValueError("provisioning failed")), \
                 patch.object(run_aws, "stack_events", return_value={"events": []}), \
                 patch.object(run_aws.deploy, "retrieve") as retrieve, patch.object(run_aws.deploy, "execute") as execute, \
                 patch("prepare.subprocess.run", side_effect=AssertionError("No build/JVM process in protected orchestration")):
                result = run_aws.run(Mock(), Mock(), req, inputs, bundles, {"region": "eu-west-1"}, output)
            self.assertEqual(result["exitCode"], 1)
            self.assertEqual(result["phase"], "provisioning")
            self.assertTrue((output / "cleanup-plan.json").is_file())
            self.assertTrue((output / "controller-outcome.json").is_file())
            retrieve.assert_called_once()
            execute.assert_not_called()

    def test_oidc_job_is_protected_and_does_not_build_or_execute_downloaded_jvms(self):
        job = WORKFLOW["jobs"]["aws-execution"]
        self.assertEqual(job["environment"], "sendium-performance")
        self.assertEqual(job["permissions"], {"contents": "read", "id-token": "write"})
        self.assertEqual(job["steps"][0]["with"]["ref"], "${{ needs.validate.outputs.controller_revision }}")
        credentials = next(step for step in job["steps"] if step.get("uses", "").startswith("aws-actions/configure-aws-credentials@"))
        self.assertEqual(credentials["with"]["role-duration-seconds"], "10800")
        self.assertTrue(all("mvnw" not in step.get("run", "") and "java -jar" not in step.get("run", "") for step in job["steps"]))
        self.assertTrue(all("${{" not in step.get("run", "") for step in job["steps"]))


if __name__ == "__main__":
    unittest.main()
