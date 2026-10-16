"""Static compatibility checks; AWS availability/identity is resolved by deployment preflight."""

import ipaddress
import math
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_preset(preset, workload_validator):
    require(preset["schemaVersion"] == 1, "Unsupported preset version")
    require(re.fullmatch(r"[a-z0-9-]+", preset["id"]), "Invalid preset identity")
    workload = preset["workload"]
    workload_validator.validate(workload)
    aws, runtime, database = preset["aws"], preset["runtime"], preset["database"]
    deployment, measurement, bounds = preset["deployment"], preset["measurement"], preset["bounds"]
    require(aws["regionEnvironmentVariable"] == "AWS_REGION", "AWS region must be explicitly supplied")
    require(set(aws["supportedArchitectures"]) == set(runtime["amiParameters"]), "AMI/architecture coverage differs")
    require(set(aws["supportedArchitectures"]) == set(runtime["java"]["archiveArchitectures"]), "Runtime archive architecture coverage differs")
    require(aws["burstableCreditMode"] in ("standard", "unlimited"), "Invalid burstable mode")
    require(runtime["java"]["featureVersion"] == 25, "Preset must match the Java 25 application")
    for source in ("sendium", "harness"):
        arguments = runtime["java"][source + "Arguments"]
        maxima = [re.fullmatch(r"-Xmx(\d+)([mg])", argument) for argument in arguments if argument.startswith("-Xmx")]
        require(len(maxima) == 1 and maxima[0] is not None, "Explicit JVM heap maximum required")
        heap_mib = int(maxima[0][1]) * (1024 if maxima[0][2] == "g" else 1)
        require(0 < heap_mib < aws["minimumMemoryMiB"][source], "Heap exceeds the instance memory compatibility budget")
        require(bool(aws["instanceDefaults"][source]), "Default instance type required")
    root = runtime["rootVolume"]
    if root["sizeGiB"] is None:
        require(root.get("sizeResolution") == "ami-root-device-minimum", "Unresolved root storage needs an explicit AMI-minimum policy")
    else:
        require(type(root["sizeGiB"]) is int and root["sizeGiB"] > 0, "Invalid root volume size")
    require(database["storageType"] in ("gp2", "gp3") and database["allocatedStorageGiB"] >= 20,
            "New PostgreSQL general-purpose SSD storage requires at least 20 GiB")
    network = preset["network"]
    require(network["ownership"] == "per-run", "Default network must be cleaned with its run")
    require(not any(network[key] for key in ("publicIps", "internetGateway", "natGateway")), "Default network must remain private")
    vpc = ipaddress.ip_network(network["vpcCidr"])
    subnets = [ipaddress.ip_network(cidr) for cidr in network["privateSubnetCidrs"]]
    require(len(subnets) == 2 and all(subnet.subnet_of(vpc) for subnet in subnets) and not subnets[0].overlaps(subnets[1]),
            "Two distinct in-VPC subnets are required for RDS")
    require({"ssm", "ssmmessages", "secretsmanager"}.issubset(network["interfaceEndpoints"])
            and "s3" in network["gatewayEndpoints"], "Missing private management/artifact connectivity")
    require(network["privateDns"] and network["dnsSupport"] and network["dnsHostnames"], "Private endpoint DNS must work")
    require(not database["publiclyAccessible"] and database["storageEncrypted"] and database["sslMode"] == "verify-full",
            "Private encrypted PostgreSQL with verified TLS is required")
    require(database["deletionPolicy"] == database["updateReplacePolicy"] == "Delete" and not database["finalSnapshot"]
            and not database["deletionProtection"] and database["backupRetentionDays"] == 0, "Temporary database cleanup would retain state")
    require(not database["autoMinorVersionUpgrade"] and not database["allowMajorVersionUpgrade"]
            and database["maximumAllocatedStorageGiB"] == 0 and database["forceSsl"], "Database version/storage/TLS can drift from the resolved preset")
    require(0 <= database["poolMinSize"] <= database["poolMaxSize"] and database["poolMaxSize"] > 0, "Invalid pool sizing")
    ports = [deployment[key] for key in ("httpPort", "smppPort", "providerPort", "callbackPort")]
    require(all(type(value) is int and value > 0 for value in deployment.values()), "Deployment limits must be positive integers")
    require(len(set(ports)) == 4 and all(isinstance(port, int) and 1 <= port <= 65535 for port in ports), "Invalid listener ports")
    require(workload["maxOutstandingRequests"] <= deployment["ingressWindow"] and workload["maxOutstandingRequests"] <= workload["maxTrackedMessages"],
            "Ingress/correlation capacity does not cover outstanding requests")
    require(bounds["maximumProviderResponseDelayMillis"] < deployment["outgoingRequestTimeoutMillis"], "Provider delay can exhaust application request timeout")
    require(workload["offeredMessagesPerSecond"] <= bounds["maximumOfferedMessagesPerSecond"] <= 1_000_000_000,
            "Rate exceeds the generator clock resolution")
    for field, bound in (("warmupSeconds", "maximumWarmupSeconds"), ("measurementSeconds", "maximumMeasurementSeconds"),
                         ("drainTimeoutSeconds", "maximumDrainSeconds"), ("providerResponseDelayMillis", "maximumProviderResponseDelayMillis"),
                         ("providerDlrDelayMillis", "maximumProviderDlrDelayMillis")):
        require(workload[field] <= bounds[bound], "Default workload exceeds preset bounds")
    maximum_seconds = sum(bounds[key] for key in ("maximumWarmupSeconds", "maximumMeasurementSeconds", "maximumDrainSeconds"))
    interval = measurement["sampleIntervalSeconds"]
    require(measurement["cloudWatchCreditPeriodSeconds"] == 300, "EC2 credit metrics require five-minute periods")
    require(0 < interval <= 60 and math.ceil((maximum_seconds + 5 + 2 * interval) / interval) + 1 <= measurement["sampleBudgetPerSource"],
            "Sampler cannot cover maximum configured phases")
    require(math.ceil(bounds["maximumMeasurementSeconds"] / 60) + math.ceil(bounds["maximumDrainSeconds"] / 60) <= measurement["intervalBudget"],
            "Minute histograms exceed configured memory budget")
    lifetime = preset["lifecycle"]
    require(all(type(value) is int and value > 0 for key, value in lifetime.items() if key != "expiryMechanism"),
            "Lifecycle budgets must be positive integers")
    require(lifetime["expiryMechanism"] == "eventbridge-scheduled-lambda", "Unsupported independent expiry mechanism")
    needed_minutes = lifetime["provisioningTimeoutMinutes"] + measurement["readinessTimeoutSeconds"] / 60 + maximum_seconds / 60 + lifetime["retrievalTimeoutMinutes"]
    require(needed_minutes <= lifetime["executionJobTimeoutMinutes"], "Execution timeout cannot cover setup/traffic/retrieval")
    require(lifetime["executionJobTimeoutMinutes"] + lifetime["cleanupTimeoutMinutes"] + lifetime["expiryScheduleMinutes"] < lifetime["resourceTtlMinutes"],
            "Expiry can race ordinary execution and cleanup")
    require(lifetime["githubArtifactRetentionDays"] > 0 and lifetime["temporaryArtifactExpirationDays"] > 0,
            "Explicit positive artifact retention required")
