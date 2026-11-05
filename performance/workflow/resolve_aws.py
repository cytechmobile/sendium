"""Read-only regional compatibility resolution; no guessed EC2 architecture or disk sizing."""

import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def pages(aws, service, operation, collection, token="NextToken", parameter="next_token", **arguments):
    result, seen = [], set()
    for _ in range(100):
        response = aws.call(service, operation, **arguments)
        result.extend(response.get(collection, []))
        next_token = response.get(token)
        if not next_token:
            return result
        require(next_token not in seen, "Repeated AWS preflight pagination token")
        seen.add(next_token)
        arguments[parameter] = next_token
    raise ValueError("AWS preflight page budget exceeded")


def resolve(aws, preset, region):
    requested = preset["aws"]["instanceDefaults"]
    descriptions = aws.call("ec2", "describe-instance-types", instance_types=sorted(set(requested.values())))["InstanceTypes"]
    types = {item["InstanceType"]: item for item in descriptions}
    nodes, common = {}, None
    images = {}
    for source in ("sendium", "harness"):
        instance_type = requested[source]
        info = types[instance_type]
        architecture, = set(info["ProcessorInfo"]["SupportedArchitectures"]) & set(preset["aws"]["supportedArchitectures"])
        require(info["MemoryInfo"]["SizeInMiB"] >= preset["aws"]["minimumMemoryMiB"][source], "Selected EC2 memory is below the JVM budget")
        require("ebs" in info["SupportedRootDeviceTypes"] and "hvm" in info["SupportedVirtualizationTypes"]
                and "on-demand" in info["SupportedUsageClasses"], "Unsupported EC2 execution/storage mode")
        require(type(info["BurstablePerformanceSupported"]) is bool and (not info["BurstablePerformanceSupported"]
                or instance_type.split(".")[0] in ("t2", "t3", "t3a", "t4g")), "Unsupported burstable family")
        if architecture not in images:
            parameter = None
            image_id = preset["runtime"]["amiIds"][architecture]
            if not image_id:
                parameter = aws.call("ssm", "get-parameter", name=preset["runtime"]["amiParameters"][architecture])["Parameter"]
                image_id = parameter["Value"]
            require(re.fullmatch(r"ami-[a-f0-9]{8,17}", image_id), "Invalid resolved AMI ID")
            image, = aws.call("ec2", "describe-images", image_ids=[image_id])["Images"]
            require(image["ImageId"] == image_id and image["State"] == "available" and image["Architecture"] == architecture
                    and image["RootDeviceType"] == "ebs" and image["VirtualizationType"] == "hvm"
                    and image.get("Platform") != "windows" and image["Name"].startswith("al2023-"), "AMI is not a compatible available AL2023 JVM image")
            require(parameter is None or image.get("ImageOwnerAlias") == "amazon", "Public AMI parameter did not resolve an Amazon image")
            mapping, = image["BlockDeviceMappings"]
            require(mapping["DeviceName"] == image["RootDeviceName"] and type(mapping["Ebs"]["VolumeSize"]) is int
                    and mapping["Ebs"]["VolumeSize"] > 0, "AMI must have one resolved EBS root device")
            images[architecture] = {"imageId": image_id, "name": image["Name"], "ownerId": image["OwnerId"],
                "architecture": architecture, "rootDeviceName": image["RootDeviceName"], "rootVolumeGiB": mapping["Ebs"]["VolumeSize"],
                "snapshotId": mapping["Ebs"]["SnapshotId"], "bootMode": image.get("BootMode", "legacy-bios"),
                "parameter": {key: parameter[key] for key in ("Name", "Version")} if parameter else None}
        image = images[architecture]
        modes = set(info["SupportedBootModes"])
        require(bool(modes & {"legacy-bios", "uefi"}) if image["bootMode"] == "uefi-preferred" else image["bootMode"] in modes,
                "AMI boot mode is unsupported by the instance")
        offerings = pages(aws, "ec2", "describe-instance-type-offerings", "InstanceTypeOfferings", location_type="availability-zone",
                          filters=[{"Name": "instance-type", "Values": [instance_type]}])
        zones = {item["Location"] for item in offerings if item["InstanceType"] == instance_type}
        common = zones if common is None else common & zones
        nodes[source] = {**image, "instanceType": instance_type, "memoryMiB": info["MemoryInfo"]["SizeInMiB"],
                         "vcpus": info["VCpuInfo"]["DefaultVCpus"], "burstable": info["BurstablePerformanceSupported"]}
    availability = aws.call("ec2", "describe-availability-zones")["AvailabilityZones"]
    available = {item["ZoneName"]: item for item in availability if item["State"] == "available"
                 and item.get("ZoneType") == "availability-zone" and item.get("OptInStatus") in ("opted-in", "opt-in-not-required")}
    require(len(available) >= 2, "Two available regional Availability Zones are required")
    common &= set(available)
    details = aws.call("ec2", "describe-vpc-endpoint-services",
                       service_names=["com.amazonaws." + region + "." + service for service in ("ssm", "ssmmessages", "secretsmanager", "s3")])["ServiceDetails"]
    services = {}
    for service in ("ssm", "ssmmessages", "secretsmanager", "s3"):
        name = "com.amazonaws." + region + "." + service
        required_type = "Gateway" if service == "s3" else "Interface"
        matching = [item for item in details if item["ServiceName"] == name
                    and any(kind.get("ServiceType") == required_type for kind in item["ServiceType"])]
        require(len(matching) == 1, "Required VPC endpoint type is unavailable or ambiguous: " + name)
        services[name] = matching[0]
    for service in ("ssm", "ssmmessages", "secretsmanager", "s3"):
        info = services["com.amazonaws." + region + "." + service]
        if service != "s3":
            require(info.get("PrivateDnsName") or info.get("PrivateDnsNames"), "Private endpoint DNS is unavailable")
            common &= set(info["AvailabilityZones"])
    database = preset["database"]
    versions = pages(aws, "rds", "describe-db-engine-versions", "DBEngineVersions", token="Marker", parameter="marker", engine="postgres",
                     filters=[{"Name": "status", "Values": ["available"]}])
    available_versions = {item["EngineVersion"] for item in versions if item["Status"] == "available"
                          and re.fullmatch(r"17\.[0-9]+(?:\.[0-9]+)?", item["EngineVersion"])}
    require(available_versions, "No available PostgreSQL 17 engine version")
    version = database["engineVersion"] or max(available_versions, key=lambda value: tuple(int(part) for part in value.split(".")))
    require(version in available_versions, "Pinned database version is unavailable")
    options = pages(aws, "rds", "describe-orderable-db-instance-options", "OrderableDBInstanceOptions", token="Marker", parameter="marker",
                    engine="postgres", engine_version=version, db_instance_class=database["instanceType"])
    options = [item for item in options if item["StorageType"] == "gp3" and item["SupportsStorageEncryption"] and item["Vpc"]
               and item["MinStorageSize"] <= database["allocatedStorageGiB"] <= item["MaxStorageSize"]]
    common &= {zone["Name"] for item in options for zone in item["AvailabilityZones"]}
    require(common, "No common AZ supports both EC2 types, required endpoints and PostgreSQL sizing")
    primary = sorted(common)[0]
    secondary = sorted(set(available) - {primary})[0]
    prefixes = aws.call("ec2", "describe-managed-prefix-lists", filters=[{"Name": "prefix-list-name", "Values": ["com.amazonaws." + region + ".s3"]}])["PrefixLists"]
    prefixes = [item for item in prefixes if item["OwnerId"] == "AWS" and item["AddressFamily"] == "IPv4"]
    prefix, = prefixes
    result = {"schemaVersion": 1, "region": region, "nodes": nodes,
            "placement": {"primary": primary, "primaryZoneId": available[primary]["ZoneId"], "secondary": secondary},
            "database": {"engine": "postgres", "engineVersion": version, "instanceType": database["instanceType"],
                         "allocatedStorageGiB": database["allocatedStorageGiB"], "storageType": "gp3"},
            "s3PrefixListId": prefix["PrefixListId"], "endpointServices": sorted(services)}
    if getattr(aws, "execution_kind", None) == "container-jvm":
        result.update(executionKind="container-jvm", localRehearsal=aws.simulation)
    return result
