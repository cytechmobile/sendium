"""Offline network isolation checks; live routing/IAM verification remains required."""

from pathlib import Path
import unittest

from cfnlint.decode import decode


TEMPLATE, ERRORS = decode(str(Path(__file__).with_name("network.yaml")))
RESOURCES = TEMPLATE["Resources"]


def resources(kind):
    return {name: spec["Properties"] for name, spec in RESOURCES.items()
            if spec["Type"] == "AWS::EC2::" + kind}


def ref(value):
    return value["Ref"]


class NetworkTest(unittest.TestCase):
    def test_private_topology_and_database_placement(self):
        self.assertFalse(ERRORS)
        allowed = {"VPC", "Subnet", "RouteTable", "SubnetRouteTableAssociation",
                   "SecurityGroup", "SecurityGroupIngress", "SecurityGroupEgress", "VPCEndpoint"}
        self.assertTrue(all(spec["Type"].removeprefix("AWS::EC2::") in allowed
                            for spec in RESOURCES.values()))
        subnets = resources("Subnet")
        self.assertEqual(len(subnets), 2)
        self.assertTrue(all(spec["MapPublicIpOnLaunch"] is False for spec in subnets.values()))
        self.assertEqual({ref(spec["AvailabilityZone"]) for spec in subnets.values()},
                         {"PrimaryAvailabilityZone", "SecondaryAvailabilityZone"})
        self.assertIn("DistinctAvailabilityZones", TEMPLATE["Rules"])
        self.assertEqual({ref(spec["SubnetId"]) for spec in resources("SubnetRouteTableAssociation").values()},
                         set(subnets))
        self.assertTrue(resources("VPC")["Vpc"]["EnableDnsSupport"])
        self.assertTrue(resources("VPC")["Vpc"]["EnableDnsHostnames"])

    def test_exact_paired_connections(self):
        ingress = set()
        egress = set()
        for spec in resources("SecurityGroupIngress").values():
            self.assertEqual(spec["IpProtocol"], "tcp")
            self.assertEqual(spec["FromPort"], spec["ToPort"])
            ingress.add((ref(spec["SourceSecurityGroupId"]), ref(spec["GroupId"]), spec["FromPort"]))
        for spec in resources("SecurityGroupEgress").values():
            self.assertEqual(spec["IpProtocol"], "tcp")
            self.assertEqual(spec["FromPort"], spec["ToPort"])
            egress.add((ref(spec["GroupId"]), ref(spec["DestinationSecurityGroupId"]), spec["FromPort"]))
        self.assertEqual(ingress, egress)
        self.assertEqual(ingress, {
            ("HarnessSecurityGroup", "SendiumSecurityGroup", 8080),
            ("HarnessSecurityGroup", "SendiumSecurityGroup", 27777),
            ("SendiumSecurityGroup", "HarnessSecurityGroup", 27779),
            ("SendiumSecurityGroup", "HarnessSecurityGroup", 8081),
            ("SendiumSecurityGroup", "DatabaseSecurityGroup", 5432),
            ("HarnessSecurityGroup", "EndpointSecurityGroup", 443),
            ("SendiumSecurityGroup", "EndpointSecurityGroup", 443),
        })

    def test_no_implicit_or_unrestricted_security_group_egress(self):
        for name, spec in resources("SecurityGroup").items():
            self.assertNotIn("SecurityGroupIngress", spec)
            rules = spec["SecurityGroupEgress"]
            self.assertEqual(len(rules), 1)
            if name in {"HarnessSecurityGroup", "SendiumSecurityGroup"}:
                self.assertEqual(rules[0], {"IpProtocol": "tcp", "FromPort": 443,
                                           "ToPort": 443, "DestinationPrefixListId": {"Ref": "S3PrefixListId"}})
            else:
                self.assertEqual(rules[0], {"IpProtocol": "-1", "CidrIp": "127.0.0.1/32"})

    def test_endpoint_placement_and_run_scoped_s3(self):
        endpoints = resources("VPCEndpoint")
        self.assertEqual(len(endpoints), 4)
        for name in ("SsmEndpoint", "SsmMessagesEndpoint", "SecretsEndpoint"):
            spec = endpoints[name]
            self.assertEqual(spec["VpcEndpointType"], "Interface")
            self.assertTrue(spec["PrivateDnsEnabled"])
            self.assertEqual(spec["SubnetIds"], [{"Ref": "PrimarySubnet"}])
            self.assertEqual(spec["SecurityGroupIds"], [{"Ref": "EndpointSecurityGroup"}])
        spec = endpoints["S3Endpoint"]
        self.assertEqual(spec["RouteTableIds"], [{"Ref": "PrivateRoutes"}])
        statement, = spec["PolicyDocument"]["Statement"]
        self.assertEqual(statement["Resource"], {"Fn::Sub": "arn:${AWS::Partition}:s3:::${ArtifactBucketName}/runs/${RunIdentity}/*"})
        self.assertEqual(set(statement["Action"]), {"s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"})

    def test_ownership_and_deletion_contract(self):
        for name, spec in RESOURCES.items():
            self.assertNotIn("DeletionPolicy", spec, name)
            self.assertNotIn("UpdateReplacePolicy", spec, name)
            if spec["Type"] in {"AWS::EC2::SubnetRouteTableAssociation",
                                "AWS::EC2::SecurityGroupIngress", "AWS::EC2::SecurityGroupEgress"}:
                continue
            tags = {tag["Key"]: tag["Value"] for tag in spec["Properties"]["Tags"]}
            self.assertEqual(tags, {"sendium:managed-by": "performance-tests",
                                    "sendium:repository": {"Ref": "Repository"},
                                    "sendium:run-identity": {"Ref": "RunIdentity"},
                                    "sendium:expires-at": {"Ref": "ExpiresAt"}})
        self.assertEqual(set(TEMPLATE["Outputs"]), {"VpcId", "PrimarySubnetId", "SecondarySubnetId",
                         "PrimaryAvailabilityZone", "HarnessSecurityGroupId", "SendiumSecurityGroupId",
                         "DatabaseSecurityGroupId"})


if __name__ == "__main__":
    unittest.main()
