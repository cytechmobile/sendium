"""Run-stack contracts and effective runtime grants, without provisioning AWS resources."""

import fnmatch
import json
from pathlib import Path
import re
import unittest

from cfnlint.decode import decode
from test_bootstrap import TEMPLATE as BOOTSTRAP, resolve as resolve_bootstrap


TEMPLATE, ERRORS = decode(str(Path(__file__).with_name("run.yaml")))
RESOURCES = TEMPLATE["Resources"]
PRESET = json.loads((Path(__file__).parents[1] / "presets/aws-private-jvm-v1.json").read_text())
ACCOUNT = "123456789012"
REGION = "eu-west-1"
BUCKET = "benchmark-artifacts"
RUN = "gh-12345678901-1"


def resolve(value, overrides=None):
    parameters = {key: spec.get("Default") for key, spec in TEMPLATE["Parameters"].items()}
    parameters.update({"AWS::Partition": "aws", "AWS::Region": REGION, "AWS::AccountId": ACCOUNT,
                       "AWS::URLSuffix": "amazonaws.com", "ArtifactBucketName": BUCKET, "RunIdentity": RUN,
                       "RuntimeBoundaryArn": f"arn:aws:iam::{ACCOUNT}:policy/sendium-perf-runtime-boundary"})
    parameters.update(overrides or {})

    def walk(node):
        if isinstance(node, list):
            return [walk(item) for item in node]
        if not isinstance(node, dict):
            return node
        if "Ref" in node:
            name = node["Ref"]
            if name in parameters:
                return parameters[name]
            if name == "DatabaseCredentials":
                return f"arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:" + walk(RESOURCES[name]["Properties"]["Name"]) + "-Ab1234"
            raise AssertionError("Unhandled reference: " + name)
        if "Fn::Sub" in node:
            return re.sub(r"\$\{([^}]+)\}", lambda match: str(walk({"Ref": match[1]})), node["Fn::Sub"])
        if "Fn::Equals" in node:
            left, right = walk(node["Fn::Equals"])
            return left == right
        if "Fn::Or" in node:
            return any(walk(node["Fn::Or"]))
        if "Fn::Split" in node:
            separator, text = walk(node["Fn::Split"])
            return text.split(separator)
        if "Fn::Select" in node:
            index, items = walk(node["Fn::Select"])
            return items[int(index)]
        return {key: walk(item) for key, item in node.items()}

    return walk(value)


def statements(role, overrides=None):
    return [statement for policy in RESOURCES[role]["Properties"]["Policies"]
            for statement in resolve(policy["PolicyDocument"], overrides)["Statement"]]


def grants(documents, action, resource):
    """These templates use Allow only; region conditions are checked separately."""
    for statement in documents:
        actions = statement["Action"]
        resources = statement["Resource"]
        if isinstance(actions, str):
            actions = [actions]
        if isinstance(resources, str):
            resources = [resources]
        if (statement["Effect"] == "Allow"
                and any(fnmatch.fnmatchcase(action.lower(), pattern.lower()) for pattern in actions)
                and any(fnmatch.fnmatchcase(resource, pattern) for pattern in resources)):
            return True
    return False


class RunTests(unittest.TestCase):
    def test_private_instances_match_preset_and_resolved_ami_storage(self):
        self.assertFalse(ERRORS)
        for node in ("Sendium", "Harness"):
            spec = RESOURCES[node]["Properties"]
            self.assertEqual(TEMPLATE["Parameters"][node + "InstanceType"]["Default"], PRESET["aws"]["instanceDefaults"][node.lower()])
            self.assertEqual(spec["AvailabilityZone"], {"Ref": "PrimaryAvailabilityZone"})
            interface, = spec["NetworkInterfaces"]
            self.assertFalse(interface["AssociatePublicIpAddress"])
            self.assertTrue(interface["DeleteOnTermination"])
            self.assertEqual(interface["SubnetId"], {"Fn::GetAtt": ["Network", "Outputs.PrimarySubnetId"]})
            self.assertEqual(interface["GroupSet"], [{"Fn::GetAtt": ["Network", "Outputs." + node + "SecurityGroupId"]}])
            self.assertNotIn("KeyName", spec)
            self.assertNotIn("UserData", spec)
            self.assertEqual(spec["MetadataOptions"]["HttpTokens"], "required")
            self.assertEqual(spec["MetadataOptions"]["HttpPutResponseHopLimit"], 1)
            self.assertTrue(spec["PropagateTagsToVolumeOnCreation"])
            mapping, = spec["BlockDeviceMappings"]
            self.assertEqual(mapping["DeviceName"], {"Ref": node + "RootDeviceName"})
            self.assertEqual(mapping["Ebs"], {"VolumeSize": {"Ref": node + "RootVolumeGiB"},
                                           "VolumeType": "gp3", "Encrypted": True, "DeleteOnTermination": True})
            for suffix in ("ImageId", "RootDeviceName", "RootVolumeGiB"):
                self.assertNotIn("Default", TEMPLATE["Parameters"][node + suffix])

    def test_standard_credits_only_for_known_burstable_families(self):
        for node in ("Sendium", "Harness"):
            for instance_type in ("t2.small", "t3.small", "t3a.small", "t4g.small", "c7g.large", "m7i.large"):
                burstable = resolve(TEMPLATE["Conditions"][node + "Burstable"], {node + "InstanceType": instance_type})
                self.assertEqual(instance_type.startswith("t"), burstable)
            self.assertEqual(RESOURCES[node]["Properties"]["CreditSpecification"],
                             {"Fn::If": [node + "Burstable", {"CPUCredits": "standard"}, {"Ref": "AWS::NoValue"}]})

    def test_database_is_disposable_private_encrypted_and_requires_tls(self):
        spec = RESOURCES["Database"]["Properties"]
        for key in ("MultiAZ", "PubliclyAccessible", "DeletionProtection", "AutoMinorVersionUpgrade",
                    "AllowMajorVersionUpgrade", "EnablePerformanceInsights"):
            self.assertFalse(spec[key], key)
        self.assertTrue(spec["StorageEncrypted"])
        self.assertEqual(spec["BackupRetentionPeriod"], 0)
        self.assertTrue(spec["DeleteAutomatedBackups"])
        self.assertNotIn("MaxAllocatedStorage", spec)
        self.assertEqual(int(spec["AllocatedStorage"]), PRESET["database"]["allocatedStorageGiB"])
        self.assertEqual(spec["StorageType"], PRESET["database"]["storageType"])
        self.assertEqual(spec["DBName"], PRESET["database"]["databaseName"])
        self.assertEqual(spec["AvailabilityZone"], {"Ref": "PrimaryAvailabilityZone"})
        self.assertEqual(spec["VPCSecurityGroups"], [{"Fn::GetAtt": ["Network", "Outputs.DatabaseSecurityGroupId"]}])
        self.assertEqual(RESOURCES["DatabaseSubnets"]["Properties"]["SubnetIds"],
                         [{"Fn::GetAtt": ["Network", "Outputs.PrimarySubnetId"]},
                          {"Fn::GetAtt": ["Network", "Outputs.SecondarySubnetId"]}])
        self.assertNotIn("Default", TEMPLATE["Parameters"]["DatabaseEngineVersion"])
        self.assertEqual(RESOURCES["DatabaseParameters"]["Properties"]["Family"], "postgres17")
        self.assertEqual(RESOURCES["DatabaseParameters"]["Properties"]["Parameters"]["rds.force_ssl"], "1")
        for policy in ("DeletionPolicy", "UpdateReplacePolicy"):
            self.assertEqual(RESOURCES["Database"][policy], "Delete")
            self.assertEqual(RESOURCES["DatabaseCredentials"][policy], "Delete")

    def test_database_secret_never_reaches_harness_or_outputs(self):
        secret = RESOURCES["DatabaseCredentials"]["Properties"]
        generation = secret["GenerateSecretString"]
        self.assertEqual(generation["PasswordLength"], PRESET["database"]["credentials"]["passwordLength"])
        self.assertTrue(generation["ExcludePunctuation"])
        self.assertEqual(json.loads(generation["SecretStringTemplate"])["username"], PRESET["database"]["username"])
        arn = resolve({"Ref": "DatabaseCredentials"})
        self.assertTrue(grants(statements("SendiumRole"), "secretsmanager:GetSecretValue", arn))
        self.assertFalse(grants(statements("HarnessRole"), "secretsmanager:GetSecretValue", arn))
        self.assertFalse(grants(statements("SendiumRole"), "secretsmanager:GetSecretValue", arn.replace(RUN, "gh-999-1")))
        self.assertNotIn("resolve:secretsmanager", json.dumps(TEMPLATE["Outputs"]))
        for key in ("MasterUsername", "MasterUserPassword"):
            self.assertIn("{{resolve:secretsmanager:${DatabaseCredentials}:SecretString:", RESOURCES["Database"]["Properties"][key]["Fn::Sub"])

    def test_runtime_roles_cannot_provision_or_cross_run_and_node_boundaries(self):
        bucket = f"arn:aws:s3:::{BUCKET}/runs/{RUN}/"
        for role, node in (("HarnessRole", "harness"), ("SendiumRole", "sendium")):
            policy = statements(role)
            self.assertEqual(RESOURCES[role]["Properties"]["PermissionsBoundary"], {"Ref": "RuntimeBoundaryArn"})
            for action in ("ec2:RunInstances", "iam:PassRole", "ssm:SendCommand", "ssm:GetParameter",
                           "cloudformation:CreateStack", "rds:DeleteDBInstance", "s3:DeleteObject"):
                for statement in policy:
                    actions = statement["Action"] if isinstance(statement["Action"], list) else [statement["Action"]]
                    self.assertFalse(any(fnmatch.fnmatchcase(action.lower(), pattern.lower()) for pattern in actions), action)
            self.assertTrue(grants(policy, "s3:GetObject", bucket + "inputs/" + node + "/bundle.tar.gz"))
            self.assertTrue(grants(policy, "s3:PutObject", bucket + "outputs/" + node + "/logs.txt"))
            self.assertFalse(grants(policy, "s3:PutObject", bucket + "inputs/common/runtime.tar.gz"))
            self.assertFalse(grants(policy, "s3:PutObject", bucket + "templates/network.yaml"))
            self.assertFalse(grants(policy, "s3:PutObject", bucket.replace(RUN, "gh-999-1") + "outputs/" + node + "/logs.txt"))
            other = "sendium" if node == "harness" else "harness"
            self.assertFalse(grants(policy, "s3:GetObject", bucket + "inputs/" + other + "/bundle.tar.gz"))
            self.assertFalse(grants(policy, "s3:PutObject", bucket + "outputs/" + other + "/logs.txt"))
            for statement in policy:
                if statement["Resource"] == "*":
                    self.assertEqual(statement["Condition"], {"StringEquals": {"aws:RequestedRegion": REGION}})
        self.assertTrue(grants(statements("SendiumRole"), "s3:PutObject", bucket + "inputs/harness/generated/config.json"))
        self.assertFalse(grants(statements("HarnessRole"), "s3:PutObject", bucket + "inputs/harness/generated/config.json"))

    def test_grants_fit_bootstrap_boundary_and_names_fit_aws_limits(self):
        boundary = resolve_bootstrap(BOOTSTRAP["Resources"]["RuntimeBoundary"]["Properties"]["PolicyDocument"])["Statement"]
        # The bootstrap test resolver uses a maximum-length bucket; substitute our test bucket.
        boundary = json.loads(json.dumps(boundary).replace("b" * 63, BUCKET))
        for role in ("SendiumRole", "HarnessRole"):
            for statement in statements(role):
                actions = statement["Action"] if isinstance(statement["Action"], list) else [statement["Action"]]
                targets = statement["Resource"] if isinstance(statement["Resource"], list) else [statement["Resource"]]
                for action in actions:
                    for target in targets:
                        self.assertTrue(grants(boundary, action, target), (role, action, target))
            maximums = {key: "p" * spec["MaxLength"] for key, spec in TEMPLATE["Parameters"].items()
                        if key == "ResourcePrefix"}
            maximums["RunIdentity"] = "gh-" + "1" * (TEMPLATE["Parameters"]["RunIdentity"]["MaxLength"] - 5) + "-1"
            self.assertLessEqual(len(resolve(RESOURCES[role]["Properties"]["RoleName"], maximums)), 64)
            maximums["ArtifactBucketName"] = "b" * 63
            self.assertLessEqual(len(json.dumps(statements(role, maximums), separators=(",", ":"))), 10240)

    def test_network_child_and_owned_resource_tags(self):
        network = RESOURCES["Network"]["Properties"]
        child, errors = decode(str(Path(__file__).with_name("network.yaml")))
        self.assertFalse(errors)
        self.assertEqual(set(network["Parameters"]), set(child["Parameters"]))
        for resource in RESOURCES.values():
            for output in re.findall(r'Outputs\.([A-Za-z0-9]+)', json.dumps(resource)):
                self.assertIn(output, child["Outputs"])
        self.assertEqual(resolve(network["TemplateURL"]),
                         f"https://{BUCKET}.s3.{REGION}.amazonaws.com/runs/{RUN}/templates/network.yaml")
        for parameter, value in network["Parameters"].items():
            self.assertEqual(value, {"Ref": parameter})
        for name, resource in RESOURCES.items():
            if resource["Type"] == "AWS::IAM::InstanceProfile":
                continue  # This CloudFormation resource does not expose Tags.
            tags = {tag["Key"]: tag["Value"] for tag in resource["Properties"]["Tags"]}
            self.assertEqual(tags["sendium:managed-by"], "performance-tests", name)
            self.assertEqual(tags["sendium:repository"], {"Ref": "Repository"}, name)
            self.assertEqual(tags["sendium:run-identity"], {"Ref": "RunIdentity"}, name)
            self.assertEqual(tags["sendium:expires-at"], {"Ref": "ExpiresAt"}, name)
        self.assertNotIn("Export", json.dumps(TEMPLATE["Outputs"]))

    def test_profiles_and_trust_bind_each_node_to_its_own_role(self):
        for node in ("Sendium", "Harness"):
            role = RESOURCES[node + "Role"]["Properties"]
            trust, = role["AssumeRolePolicyDocument"]["Statement"]
            self.assertEqual(trust, {"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"},
                                     "Action": "sts:AssumeRole"})
            self.assertEqual(RESOURCES[node + "Profile"]["Properties"]["Roles"], [{"Ref": node + "Role"}])
            self.assertEqual(RESOURCES[node]["Properties"]["IamInstanceProfile"], {"Ref": node + "Profile"})
        for name in ("NetworkStackId", "SendiumInstanceId", "SendiumPrivateAddress", "HarnessInstanceId",
                     "HarnessPrivateAddress", "DatabaseAddress", "DatabasePort", "DatabaseName",
                     "DatabaseCredentialsArn", "ArtifactRunPrefix", "RunIdentity", "ExpiresAt"):
            self.assertIn(name, TEMPLATE["Outputs"])


if __name__ == "__main__":
    unittest.main()
