"""Read-only AWS diagnostics for the protected orchestration layer, never the load JVM."""

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess
import sys


def utc(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() is None:
        raise ValueError("Timestamp must identify a timezone")
    return parsed.astimezone(timezone.utc)


class AwsCli:
    def __init__(self, region):
        self.region = region

    def call(self, service, operation, **parameters):
        command = ["aws", service, operation, "--region", self.region, "--output", "json", "--no-cli-pager"]
        for key, value in parameters.items():
            command.append("--" + key.replace("_", "-"))
            command.extend(value if isinstance(value, list) else [str(value)])
        response = subprocess.run(command, check=True, capture_output=True, text=True, timeout=60)
        return json.loads(response.stdout)


def collect(aws, instances, start, end):
    if start >= end or len(set(instances.values())) != 2:
        raise ValueError("Invalid observation window or instance identities")
    period = 300  # EC2 CPU-credit metrics are published at five-minute granularity.
    query_start = datetime.fromtimestamp(math.floor(start.timestamp() / period) * period, timezone.utc)
    query_end = datetime.fromtimestamp(math.ceil(end.timestamp() / period) * period, timezone.utc)
    if (query_end - query_start).total_seconds() / period > 1440:
        raise ValueError("Credit window exceeds CloudWatch point budget")
    document = {"schemaVersion": 1, "windowStart": start.isoformat(), "windowEnd": end.isoformat(),
                "periodSeconds": period, "instances": [], "collectionIssues": []}
    descriptions = aws.call("ec2", "describe-instances", instance_ids=list(instances.values()))
    by_id = {item["InstanceId"]: item for reservation in descriptions.get("Reservations", [])
             for item in reservation.get("Instances", [])}
    types = sorted({item["InstanceType"] for item in by_id.values()})
    type_info = aws.call("ec2", "describe-instance-types", instance_types=types) if types else {"InstanceTypes": []}
    burstable = {item["InstanceType"]: item.get("BurstablePerformanceSupported") for item in type_info["InstanceTypes"]}
    for source, instance_id in instances.items():
        info = by_id.get(instance_id, {})
        instance_type = info.get("InstanceType", "unknown")
        item = {"source": source, "instanceId": instance_id, "instanceType": instance_type,
                "burstable": burstable.get(instance_type),
                "mode": "unknown", "periodSeconds": period, "windowStart": start.isoformat(),
                "windowEnd": end.isoformat(), "samples": [], "collectionIssues": []}
        document["instances"].append(item)
        if burstable.get(instance_type) is False:
            item["mode"] = "not-applicable"
            continue
        if burstable.get(instance_type) is not True:
            item["collectionIssues"].append("INSTANCE_TYPE_METADATA_MISSING")
            continue
        points = {}
        try:
            credits = aws.call("ec2", "describe-instance-credit-specifications", instance_ids=[instance_id])
            matching = [entry for entry in credits.get("InstanceCreditSpecifications", []) if entry["InstanceId"] == instance_id]
            if matching:
                item["mode"] = matching[0].get("CpuCredits", "unknown")
            for metric, statistic, field in (
                ("CPUCreditBalance", "Average", "balanceAverage"),
                ("CPUCreditUsage", "Sum", "usageSum"),
                ("CPUSurplusCreditBalance", "Average", "surplusBalanceAverage"),
                ("CPUSurplusCreditsCharged", "Sum", "surplusChargedSum"),
            ):
                response = aws.call("cloudwatch", "get-metric-statistics", namespace="AWS/EC2", metric_name=metric,
                                    dimensions=[f"Name=InstanceId,Value={instance_id}"],
                                    start_time=query_start.isoformat(), end_time=query_end.isoformat(),
                                    period=period, statistics=[statistic])
                for point in response.get("Datapoints", []):
                    key = utc(point["Timestamp"]).isoformat()
                    points.setdefault(key, {"timestamp": key, "balanceAverage": None, "usageSum": None,
                                            "surplusBalanceAverage": None, "surplusChargedSum": None})[field] = point[statistic]
        except (OSError, subprocess.SubprocessError, ValueError, KeyError):
            item["collectionIssues"].append("CREDIT_COLLECTION_FAILED")
        item["samples"] = [points[key] for key in sorted(points)]
        if item["mode"] not in ("standard", "unlimited"):
            item["collectionIssues"].append("CREDIT_MODE_MISSING")
        if not any(point["balanceAverage"] is not None for point in item["samples"]):
            item["collectionIssues"].append("CREDIT_BALANCE_MISSING")
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--region", required=True)
    parser.add_argument("--sendium-instance-id", required=True)
    parser.add_argument("--harness-instance-id", required=True)
    parser.add_argument("--start-time", required=True)
    parser.add_argument("--end-time", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[a-z0-9-]{3,32}", args.region) or not all(
                re.fullmatch(r"i-[0-9a-f]{8,17}", value) for value in (args.sendium_instance_id, args.harness_instance_id)):
            raise ValueError("Invalid AWS location or identities")
        document = collect(AwsCli(args.region), {"sendium": args.sendium_instance_id, "harness": args.harness_instance_id},
                           utc(args.start_time), utc(args.end_time))
        with Path(args.output).open("x", encoding="utf-8") as output:
            json.dump(document, output, indent=2, allow_nan=False)
            output.write("\n")
        return 0
    except (OSError, subprocess.SubprocessError, ValueError, KeyError):
        print("CREDIT_COLLECTION_FAILED: AWS diagnostics or output unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
