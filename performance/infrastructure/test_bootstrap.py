"""Offline policy invariants, not a substitute for IAM authorization testing in AWS."""

import fnmatch
import json
from pathlib import Path
import re
import unittest

from cfnlint.decode import decode


TEMPLATE, ERRORS = decode(str(Path(__file__).with_name("bootstrap.yaml")))


def resolve(value, existing=False, maximum=False):
    parameters = {key: spec.get("Default") for key, spec in TEMPLATE["Parameters"].items()}
    parameters.update({"AWS::Partition": "aws", "AWS::Region": "eu-west-1", "AWS::AccountId": "123456789012", "AWS::URLSuffix": "amazonaws.com"})
    parameters["AWS::StackId"] = "arn:aws:cloudformation:eu-west-1:123456789012:stack/" + "b" * 128 + "/bootstrap-id"
    if maximum:
        parameters.update(ResourcePrefix="p" * 24, RepositoryOwner="o" * 39, RepositoryName="r" * 100,
                          GitHubEnvironment="e" * 100, GitHubRecoveryEnvironment="x" * 100)
    bucket = "b" * 63
    provider = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    parameters["ExistingOidcProviderArn"] = provider if existing else ""

    def reference(name):
        if name in parameters:
            return parameters[name]
        if name == "Artifacts":
            return bucket
        if name == "GitHubOidc":
            return provider
        resource = TEMPLATE["Resources"][name]
        if resource["Type"] == "AWS::IAM::ManagedPolicy":
            return "arn:aws:iam::123456789012:policy/" + walk(resource["Properties"]["ManagedPolicyName"])
        if resource["Type"] == "AWS::IAM::Role":
            return walk(resource["Properties"]["RoleName"])
        if resource["Type"] == "AWS::Logs::LogGroup":
            return walk(resource["Properties"]["LogGroupName"])
        raise AssertionError("Unrecognized bootstrap reference: " + name)

    def attribute(name):
        resource, attribute_name = name.split(".", 1)
        assert attribute_name == "Arn"
        if resource == "Artifacts":
            return "arn:aws:s3:::" + bucket
        kind = TEMPLATE["Resources"][resource]["Type"]
        if kind == "AWS::IAM::Role":
            return "arn:aws:iam::123456789012:role/" + reference(resource)
        if kind == "AWS::Logs::LogGroup":
            return "arn:aws:logs:eu-west-1:123456789012:log-group:" + reference(resource) + ":*"
        raise AssertionError("Unrecognized bootstrap attribute: " + name)

    def walk(node):
        if isinstance(node, list):
            return [walk(item) for item in node]
        if not isinstance(node, dict):
            return node
        if "Ref" in node:
            return reference(node["Ref"])
        if "Fn::GetAtt" in node:
            raw = node["Fn::GetAtt"]
            return attribute(".".join(raw) if isinstance(raw, list) else raw)
        if "Fn::If" in node:
            condition, yes, no = node["Fn::If"]
            assert condition == "CreateOidcProvider"
            return walk(no if existing else yes)
        if "Fn::Sub" in node:
            expression = node["Fn::Sub"]
            assert isinstance(expression, str)
            return re.sub(r"\$\{([^}]+)\}", lambda match: attribute(match[1]) if "." in match[1] else str(reference(match[1])), expression)
        return {key: walk(item) for key, item in node.items()}

    return walk(value)


def policies(role, maximum=False):
    return [resolve(policy["PolicyDocument"], maximum=maximum) for policy in TEMPLATE["Resources"][role]["Properties"].get("Policies", [])]


def allowed(role, action):
    return [statement for document in policies(role) for statement in document["Statement"]
            if statement["Effect"] == "Allow" and any(fnmatch.fnmatchcase(action.lower(), pattern.lower())
                for pattern in ([statement["Action"]] if isinstance(statement["Action"], str) else statement["Action"]))]


def network_creation_allowed(action, resource, context):
    """Limited allow matching for network-creation cases, not a general IAM evaluator."""
    for statement in allowed("CloudFormationExecution", action):
        patterns = statement["Resource"] if isinstance(statement["Resource"], list) else [statement["Resource"]]
        if not any(fnmatch.fnmatchcase(resource, pattern) for pattern in patterns):
            continue
        conditions = statement.get("Condition", {})
        if set(conditions) - {"StringEquals"}:
            raise AssertionError("Review unsupported creation-policy condition")
        if all(context.get(key) in (value if isinstance(value, list) else [value])
               for key, value in conditions.get("StringEquals", {}).items()):
            return True
    return False


class BootstrapTests(unittest.TestCase):
    def test_new_network_resources_require_request_tags_and_matching_account_region(self):
        context = {"aws:RequestTag/sendium:managed-by": "performance-tests",
                   "aws:RequestTag/sendium:repository": "cytechmobile/sendium", "aws:RequestedRegion": "eu-west-1"}
        for action, kind in (("CreateVpc", "vpc"), ("CreateSubnet", "subnet"), ("CreateRouteTable", "route-table"),
                             ("CreateSecurityGroup", "security-group"), ("CreateVpcEndpoint", "vpc-endpoint")):
            resource = "arn:aws:ec2:eu-west-1:123456789012:" + kind + "/test"
            with self.subTest(action=action):
                self.assertTrue(network_creation_allowed("ec2:" + action, resource, context))
                self.assertFalse(network_creation_allowed("ec2:" + action, resource, {}))
                self.assertFalse(network_creation_allowed("ec2:" + action, resource,
                                 {**context, "aws:RequestTag/sendium:repository": "other/repository"}))
                self.assertFalse(network_creation_allowed("ec2:" + action, resource.replace("eu-west-1", "us-east-1"), context))
                self.assertFalse(network_creation_allowed("ec2:" + action, resource.replace("123456789012", "999999999999"), context))

    def test_existing_vpc_authorization_uses_resource_tags_not_new_resource_tags(self):
        resource = "arn:aws:ec2:eu-west-1:123456789012:vpc/existing"
        context = {"aws:ResourceTag/sendium:managed-by": "performance-tests",
                   "aws:ResourceTag/sendium:repository": "cytechmobile/sendium", "aws:RequestedRegion": "eu-west-1"}
        for action in ("CreateSubnet", "CreateRouteTable", "CreateSecurityGroup", "CreateVpcEndpoint"):
            with self.subTest(action=action):
                self.assertTrue(network_creation_allowed("ec2:" + action, resource, context))
                request_tags_only = {"aws:RequestTag/sendium:managed-by": "performance-tests",
                                     "aws:RequestTag/sendium:repository": "cytechmobile/sendium", "aws:RequestedRegion": "eu-west-1"}
                self.assertFalse(network_creation_allowed("ec2:" + action, resource, request_tags_only))
                self.assertFalse(network_creation_allowed("ec2:" + action, resource,
                                 {**context, **request_tags_only, "aws:ResourceTag/sendium:repository": "other/repository"}))

    def test_endpoint_dependency_authorization_is_limited_to_owned_network_objects(self):
        context = {"aws:ResourceTag/sendium:managed-by": "performance-tests",
                   "aws:ResourceTag/sendium:repository": "cytechmobile/sendium", "aws:RequestedRegion": "eu-west-1"}
        for kind in ("subnet", "security-group", "route-table"):
            resource = "arn:aws:ec2:eu-west-1:123456789012:" + kind + "/existing"
            with self.subTest(kind=kind):
                self.assertTrue(network_creation_allowed("ec2:CreateVpcEndpoint", resource, context))
                self.assertFalse(network_creation_allowed("ec2:CreateVpcEndpoint", resource,
                                 {**context, "aws:ResourceTag/sendium:managed-by": "other-system"}))
                self.assertFalse(network_creation_allowed("ec2:CreateVpcEndpoint", resource, {}))

    def test_template_decodes_and_expiry_installation_is_conditional(self):
        self.assertFalse(ERRORS)
        types = {resource["Type"] for resource in TEMPLATE["Resources"].values()}
        self.assertNotIn("AWS::EC2::Instance", types)
        self.assertNotIn("AWS::RDS::DBInstance", types)
        self.assertIn("AWS::Lambda::Function", types)
        for name in ("ExpiryFunction", "ExpiryAsync", "ExpirySchedule", "ExpirySchedulePermission"):
            self.assertEqual(TEMPLATE["Resources"][name]["Condition"], "InstallExpiry")
        self.assertEqual({"Fn::If": ["InstallExpiry", "true", "false"]}, TEMPLATE["Outputs"]["ExpiryHandlerInstalled"]["Value"])
        function = TEMPLATE["Resources"]["ExpiryFunction"]["Properties"]
        self.assertEqual(function["Handler"], "expiry.handler")
        self.assertEqual(function["Timeout"], 840)
        self.assertEqual(function["Runtime"], "python3.13")
        preset = json.loads((Path(__file__).parents[1] / "presets/aws-private-jvm-v1.json").read_text())
        self.assertEqual(TEMPLATE["Parameters"]["RunResourceTtlMinutes"]["Default"], preset["lifecycle"]["resourceTtlMinutes"])
        self.assertLess(function["Timeout"], preset["lifecycle"]["expiryScheduleMinutes"] * 60)
        self.assertEqual(TEMPLATE["Resources"]["ExpirySchedule"]["Properties"]["ScheduleExpression"], "rate(15 minutes)")
        self.assertEqual(TEMPLATE["Resources"]["ExpirySchedulePermission"]["Properties"]["SourceAccount"], {"Ref": "AWS::AccountId"})

    def test_expiry_role_reads_only_control_markers_and_cannot_modify_its_implementation(self):
        readers = allowed("ExpiryRole", "s3:GetObject")
        self.assertEqual(len(readers), 1)
        self.assertTrue(readers[0]["Resource"].endswith("/runs/*/control/run.json"))
        for action in ("ssm:SendCommand", "iam:PassRole", "lambda:UpdateFunctionCode", "s3:PutObject", "s3:DeleteBucket"):
            self.assertFalse(allowed("ExpiryRole", action))
        self.assertTrue(allowed("ExpiryRole", "s3:AbortMultipartUpload"))
        self.assertFalse(allowed("GitHubOrchestrator", "lambda:UpdateFunctionCode"))

    def test_oidc_trust_requires_exact_repository_environment_and_audience(self):
        protected = "repo:cytechmobile/sendium:environment:sendium-performance"
        recovery = "repo:cytechmobile/sendium:environment:sendium-performance-recovery"
        subjects = {"GitHubOrchestrator": protected, "GitHubDiagnostics": [protected, recovery], "GitHubCleanup": recovery}
        for name in subjects:
            for existing in (False, True):
                trust = resolve(TEMPLATE["Resources"][name]["Properties"]["AssumeRolePolicyDocument"], existing=existing)
                statement = trust["Statement"][0]
                self.assertEqual("sts:AssumeRoleWithWebIdentity", statement["Action"])
                self.assertEqual("arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com", statement["Principal"]["Federated"])
                expected = statement["Condition"]["StringEquals"]
                self.assertEqual(expected["token.actions.githubusercontent.com:aud"], "sts.amazonaws.com")
                self.assertEqual(expected["token.actions.githubusercontent.com:sub"], subjects[name])
                for replacement in ("repo:other/sendium:environment:sendium-performance", "repo:cytechmobile/sendium:environment:production",
                                    "repo:cytechmobile/sendium:ref:refs/heads/main"):
                    allowed_subjects = subjects[name] if isinstance(subjects[name], list) else [subjects[name]]
                    self.assertNotIn(replacement, allowed_subjects)
                self.assertNotEqual("wrong-audience", expected["token.actions.githubusercontent.com:aud"])

    def test_diagnostics_and_runtime_boundary_cannot_provision_or_control_other_instances(self):
        for action in ("ec2:RunInstances", "rds:CreateDBInstance", "cloudformation:CreateStack", "iam:CreateRole", "ssm:SendCommand"):
            self.assertFalse(allowed("GitHubDiagnostics", action))
        boundary = resolve(TEMPLATE["Resources"]["RuntimeBoundary"]["Properties"]["PolicyDocument"])
        actions = [action for statement in boundary["Statement"] for action in
                   ([statement["Action"]] if isinstance(statement["Action"], str) else statement["Action"])]
        for action in ("ec2:RunInstances", "iam:PassRole", "ssm:SendCommand", "ssm:GetParameter", "cloudformation:DeleteStack"):
            self.assertFalse(any(fnmatch.fnmatchcase(action.lower(), pattern.lower()) for pattern in actions))
        self.assertTrue(allowed("GitHubDiagnostics", "cloudwatch:GetMetricStatistics"))
        self.assertFalse(allowed("GitHubDiagnostics", "secretsmanager:GetSecretValue"))

    def test_rds_diagnostics_can_only_describe_and_read_tags_in_the_run_database_namespace(self):
        for action in ("rds:DescribeDBInstances", "rds:ListTagsForResource"):
            statements = allowed("GitHubDiagnostics", action)
            self.assertEqual(len(statements), 1)
            statement = statements[0]
            self.assertEqual(statement["Resource"], "arn:aws:rds:eu-west-1:123456789012:db:sendium-perf-run-*")
            self.assertEqual(statement["Condition"]["StringEquals"]["aws:RequestedRegion"], "eu-west-1")
        for action in ("rds:ModifyDBInstance", "rds:DeleteDBInstance", "rds:StartDBInstance", "rds:StopDBInstance",
                       "rds:CreateDBSnapshot", "rds:DownloadDBLogFilePortion", "pi:GetResourceMetrics"):
            self.assertFalse(allowed("GitHubDiagnostics", action))

    def test_orchestrator_delegates_infrastructure_and_runtime_roles_require_boundary(self):
        self.assertFalse(allowed("GitHubOrchestrator", "ec2:RunInstances"))
        self.assertFalse(allowed("GitHubOrchestrator", "rds:CreateDBInstance"))
        denied = [statement for document in policies("CloudFormationExecution") for statement in document["Statement"]
                  if statement["Effect"] == "Deny" and statement["Action"] == "ec2:RunInstances"]
        self.assertEqual("true", denied[0]["Condition"]["Bool"]["ec2:AssociatePublicIpAddress"])
        create = allowed("GitHubOrchestrator", "cloudformation:CreateStack")[0]
        self.assertEqual("arn:aws:iam::123456789012:role/sendium-perf-cloudformation", create["Condition"]["ArnEquals"]["cloudformation:RoleArn"])
        self.assertEqual("gh-*", create["Condition"]["StringLike"]["aws:RequestTag/sendium:run-identity"])
        self.assertEqual("false", create["Condition"]["Null"]["aws:RequestTag/sendium:expires-at"])
        boundary = allowed("CloudFormationExecution", "iam:CreateRole")[0]
        self.assertEqual("arn:aws:iam::123456789012:policy/sendium-perf-runtime-boundary", boundary["Condition"]["ArnEquals"]["iam:PermissionsBoundary"])
        self.assertFalse(allowed("CloudFormationExecution", "iam:DeleteRolePermissionsBoundary"))
        self.assertFalse(allowed("CloudFormationExecution", "iam:AttachRolePolicy"))
        for statement in allowed("CloudFormationExecution", "iam:PutRolePolicy"):
            resources = statement["Resource"]
            self.assertFalse(any(fnmatch.fnmatchcase("arn:aws:iam::123456789012:role/sendium-perf-cloudformation", pattern) for pattern in resources))

    def test_cleanup_and_ssm_commands_require_owned_repository_tags_and_prefix(self):
        for name in ("GitHubOrchestrator", "GitHubCleanup", "ExpiryRole"):
            delete = allowed(name, "cloudformation:DeleteStack")[0]
            self.assertIn("stack/sendium-perf-run-", delete["Resource"])
            tags = delete["Condition"]["StringEquals"]
            self.assertEqual("performance-tests", tags["aws:ResourceTag/sendium:managed-by"])
            self.assertEqual("cytechmobile/sendium", tags["aws:ResourceTag/sendium:repository"])
        target = [statement for statement in allowed("GitHubOrchestrator", "ssm:SendCommand") if ":ec2:" in statement["Resource"]][0]
        self.assertEqual("performance-tests", target["Condition"]["StringEquals"]["ssm:resourceTag/sendium:managed-by"])
        self.assertFalse(allowed("GitHubOrchestrator", "ssm:CancelCommand"))

    def test_automatic_cleanup_is_deletion_only_without_private_input_access(self):
        for action in ("cloudformation:CreateStack", "cloudformation:UpdateStack", "ec2:RunInstances", "ssm:SendCommand",
                       "iam:PassRole", "iam:CreateRole", "secretsmanager:GetSecretValue", "s3:GetObject", "s3:PutObject", "s3:DeleteBucket"):
            self.assertFalse(allowed("GitHubCleanup", action), action)
        self.assertTrue(allowed("GitHubCleanup", "cloudformation:DescribeStacks"))
        self.assertTrue(allowed("GitHubCleanup", "s3:AbortMultipartUpload"))
        self.assertTrue(allowed("GitHubCleanup", "s3:DeleteObject")[0]["Resource"].endswith("/runs/*"))
        self.assertEqual(resolve(TEMPLATE["Outputs"]["CleanupRoleArn"]["Value"]),
                         "arn:aws:iam::123456789012:role/sendium-perf-cleanup")

    def test_storage_is_private_encrypted_temporary_and_retained_bootstrap_is_explicit(self):
        bucket = TEMPLATE["Resources"]["Artifacts"]
        self.assertEqual("Retain", bucket["DeletionPolicy"])
        self.assertTrue(all(bucket["Properties"]["PublicAccessBlockConfiguration"].values()))
        self.assertEqual("AES256", bucket["Properties"]["BucketEncryption"]["ServerSideEncryptionConfiguration"][0]["ServerSideEncryptionByDefault"]["SSEAlgorithm"])
        rule = resolve(bucket["Properties"]["LifecycleConfiguration"])["Rules"][0]
        self.assertEqual("runs/", rule["Prefix"])
        self.assertEqual(1, rule["ExpirationInDays"])
        self.assertEqual("Retain", TEMPLATE["Resources"]["GitHubOidc"]["DeletionPolicy"])
        self.assertEqual("CreateOidcProvider", TEMPLATE["Resources"]["GitHubOidc"]["Condition"])

    def test_cleanup_metadata_reads_are_limited_to_the_bootstrap_bucket(self):
        for action in ("s3:GetBucketVersioning", "s3:ListBucketMultipartUploads"):
            grants = allowed("GitHubOrchestrator", action)
            self.assertEqual(len(grants), 1)
            self.assertEqual(grants[0]["Resource"], "arn:aws:s3:::" + "b" * 63)
            self.assertFalse(allowed("GitHubDiagnostics", action))
        self.assertFalse(allowed("GitHubOrchestrator", "s3:DeleteBucket"))
        self.assertFalse(allowed("GitHubOrchestrator", "s3:DeleteObjectVersion"))

    def test_policy_documents_fit_iam_limits_at_maximum_parameter_lengths(self):
        for name, resource in TEMPLATE["Resources"].items():
            if resource["Type"] == "AWS::IAM::Role":
                size = sum(len(json.dumps(document, separators=(",", ":"))) for document in policies(name, maximum=True))
                self.assertLessEqual(size, 10240, name)
            elif resource["Type"] == "AWS::IAM::ManagedPolicy":
                size = len(json.dumps(resolve(resource["Properties"]["PolicyDocument"], maximum=True), separators=(",", ":")))
                self.assertLessEqual(size, 6144, name)


if __name__ == "__main__":
    unittest.main()
