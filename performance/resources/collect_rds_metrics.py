"""Read-only, run-owned AWS/RDS evidence. CloudWatch buckets are never interpolated."""

import argparse
from datetime import datetime, timedelta, timezone
import json
import math
from pathlib import Path
import re
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "deployment"))
from aws_cli import AwsCli


PHASES = ("warmup", "measurement", "drain")
GAUGE_STATISTICS = ("Average", "Minimum", "Maximum", "SampleCount")
METRICS = {
    "CPUUtilization": "Percent", "FreeableMemory": "Bytes", "SwapUsage": "Bytes",
    "DatabaseConnections": "Count", "ReadIOPS": "Count/Second", "WriteIOPS": "Count/Second",
    "ReadThroughput": "Bytes/Second", "WriteThroughput": "Bytes/Second",
    "ReadLatency": "Seconds", "WriteLatency": "Seconds", "DiskQueueDepth": "Count", "FreeStorageSpace": "Bytes",
    "CPUCreditBalance": "Count", "CPUCreditUsage": "Count",
    "CPUSurplusCreditBalance": "Count", "CPUSurplusCreditsCharged": "Count",
}
MAX_POINTS = 1440
MAX_ATTEMPTS = 3
COLLECTION_SECONDS = 180
RETRY_SECONDS = 30


def require(condition, message):
    if not condition:
        raise ValueError(message)


def utc(value):
    require(isinstance(value, str) and len(value) <= 64, "Expected a UTC observation boundary")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.utcoffset() is not None, "Observation boundaries must identify a timezone")
    return parsed.astimezone(timezone.utc)


def text(value):
    return value.isoformat().replace("+00:00", "Z")


def validate_context(context):
    database = context["database"]
    patterns = {"identifier": r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", "instanceClass": r"db\.[a-z][a-z0-9]*\.[a-z0-9]+",
                "region": r"[a-z0-9-]{3,32}", "accountId": r"[0-9]{12}", "partition": r"aws(?:-cn|-us-gov)?",
                "repository": r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", "runIdentity": r"gh-[0-9]+-[0-9]+"}
    for field, pattern in patterns.items():
        require(isinstance(database.get(field), str) and re.fullmatch(pattern, database[field]), "Invalid database " + field)
    require(len(database["identifier"]) <= 63 and database["identifier"].endswith("-run-" + database["runIdentity"]),
            "Database identifier must belong to the requested run namespace")
    require(set(context["phases"]) == set(PHASES), "Expected warmup, measurement and drain windows")
    windows = {phase: (utc(context["phases"][phase]["start"]), utc(context["phases"][phase]["end"])) for phase in PHASES}
    require(all(start <= end for start, end in windows.values()) and windows["measurement"][0] < windows["measurement"][1],
            "Invalid observation window")
    require(windows["warmup"][1] == windows["measurement"][0] and windows["measurement"][1] == windows["drain"][0],
            "Phase windows must be contiguous")
    require(0 < (windows["drain"][1] - windows["warmup"][0]).total_seconds() <= (MAX_POINTS - 1) * 60,
            "Database observation exceeds the point budget")
    return {field: database[field] for field in patterns}, windows


def bucket_starts(start, end, period):
    if start == end:
        return []
    first = math.floor(start.timestamp() / period) * period
    last = math.ceil(end.timestamp() / period) * period
    return [datetime.fromtimestamp(value, timezone.utc) for value in range(first, last, period)]


def series(name, unit, family, windows):
    credit = "Credit" in name
    applicable = not credit or family in ("t2", "t3", "t4g")
    if name.startswith("CPUSurplus"):
        applicable = family in ("t3", "t4g")
    if credit and family.startswith("t") and family not in ("t2", "t3", "t4g"):
        applicable = None
    period = 300 if credit else 60
    buckets = bucket_starts(windows["warmup"][0], windows["drain"][1], period)
    return {"name": name, "unit": unit, "periodSeconds": period,
            "statistics": ["Sum"] if name in ("CPUCreditUsage", "CPUSurplusCreditsCharged") else list(GAUGE_STATISTICS),
            "applicable": applicable, "queryStart": text(buckets[0]), "queryEnd": text(buckets[-1] + timedelta(seconds=period)),
            "datapoints": [], "attempts": 0, "attemptIssues": [], "collectionIssues": []}


def coverage(item, windows):
    present = {utc(point["timestamp"]) for point in item["datapoints"]
               if all(statistic[0].lower() + statistic[1:] in point for statistic in item["statistics"])}
    def window(start, end):
        expected = set(bucket_starts(start, end, item["periodSeconds"])) if item["applicable"] else set()
        return {"expectedBuckets": len(expected), "completeBuckets": len(expected & present),
                "missingBucketStarts": [text(value) for value in sorted(expected - present)]}
    return {**window(windows["warmup"][0], windows["drain"][1]),
            "phases": {phase: window(*windows[phase]) for phase in PHASES}}


def parse_points(response, item):
    require(isinstance(response, dict) and isinstance(response.get("Datapoints"), list)
            and len(response["Datapoints"]) <= MAX_POINTS, "Invalid metric response")
    points, issues, conflicts = {}, set(), set()
    start, end = utc(item["queryStart"]), utc(item["queryEnd"])
    for raw in response["Datapoints"]:
        try:
            timestamp = utc(raw["Timestamp"])
            require(start <= timestamp < end and timestamp.timestamp() % item["periodSeconds"] == 0
                    and raw.get("Unit") == item["unit"], "Invalid metric timestamp/unit")
            point = {"timestamp": text(timestamp)}
            for statistic in item["statistics"]:
                value = raw.get(statistic)
                if (type(value) not in (int, float) or not math.isfinite(value) or value < 0
                        or (statistic == "SampleCount" and value == 0)
                        or (item["unit"] == "Percent" and statistic != "SampleCount" and value > 100)):
                    issues.add("RDS_METRIC_INVALID_DATAPOINT")
                    continue
                point[statistic[0].lower() + statistic[1:]] = value
            if all(field in point for field in ("minimum", "average", "maximum")):
                require(point["minimum"] <= point["average"] <= point["maximum"], "Inconsistent metric statistics")
            key = point["timestamp"]
            if key in points and points[key] != point:
                conflicts.add(key)
                issues.add("RDS_METRIC_CONFLICTING_DATAPOINTS")
            points[key] = point
        except (ValueError, KeyError, TypeError, OverflowError):
            issues.add("RDS_METRIC_INVALID_DATAPOINT")
    return {key: value for key, value in points.items() if key not in conflicts}, sorted(issues)


def verify_database(aws, database):
    response = aws.call("rds", "describe-db-instances", db_instance_identifier=database["identifier"])
    actual, = response["DBInstances"]
    arn = "arn:{partition}:rds:{region}:{accountId}:db:{identifier}".format(**database)
    require(actual.get("DBInstanceIdentifier") == database["identifier"] and actual.get("DBInstanceArn") == arn
            and actual.get("DBInstanceClass") == database["instanceClass"] and actual.get("Engine") == "postgres",
            "Database identity mismatch")
    tags = aws.call("rds", "list-tags-for-resource", resource_name=arn)["TagList"]
    expected = {"sendium:managed-by": "performance-tests", "sendium:repository": database["repository"],
                "sendium:run-identity": database["runIdentity"]}
    for key, value in expected.items():
        require([tag.get("Value") for tag in tags if tag.get("Key") == key] == [value], "Database ownership mismatch")
    return arn


def collect(aws, context, sleep=time.sleep, clock=time.monotonic):
    database, windows = validate_context(context)
    require(getattr(aws, "region", database["region"]) == database["region"], "Database/collector region mismatch")
    document = {"schemaVersion": 1, "namespace": "AWS/RDS", "runIdentity": database["runIdentity"],
                "collectionStartedUtc": text(datetime.now(timezone.utc)),
                "database": {**database, "verified": False}, "phases": {
                    phase: {"start": text(start), "end": text(end)} for phase, (start, end) in windows.items()},
                "metrics": [], "collectionIssues": []}
    deadline = clock() + COLLECTION_SECONDS
    try:
        document["database"].update(arn=verify_database(aws, database), verified=True)
    except ValueError:
        document["collectionIssues"].append("RDS_IDENTITY_MISMATCH")
        return document
    except (OSError, RuntimeError, KeyError, TypeError):
        document["collectionIssues"].append("RDS_IDENTITY_UNAVAILABLE")
        return document
    except KeyboardInterrupt:
        document["collectionIssues"].append("RDS_COLLECTION_INTERRUPTED")
        return document
    family = database["instanceClass"].split(".")[1]
    if family.startswith("t") and family not in ("t2", "t3", "t4g"):
        document["collectionIssues"].append("RDS_CREDIT_APPLICABILITY_UNKNOWN")
    document["metrics"] = [series(name, unit, family, windows) for name, unit in METRICS.items()]
    interrupted = False
    for attempt in range(1, MAX_ATTEMPTS + 1):
        pending = [item for item in document["metrics"] if item["applicable"] and
                   (coverage(item, windows)["missingBucketStarts"] or item["collectionIssues"])]
        if not pending:
            break
        for item in pending:
            if clock() >= deadline:
                break
            item["attempts"] += 1
            try:
                response = aws.call("cloudwatch", "get-metric-statistics", namespace="AWS/RDS", metric_name=item["name"],
                    dimensions=[{"Name": "DBInstanceIdentifier", "Value": database["identifier"]}],
                    start_time=item["queryStart"], end_time=item["queryEnd"], period=item["periodSeconds"], statistics=item["statistics"])
                points, issues = parse_points(response, item)
                retained = {point["timestamp"]: point for point in item["datapoints"]}
                for key, point in points.items():
                    if len(point) >= len(retained.get(key, {})):
                        retained[key] = point
                item["datapoints"] = [retained[key] for key in sorted(retained)]
            except (OSError, RuntimeError, ValueError, KeyError, TypeError):
                issues = ["RDS_METRIC_COLLECTION_FAILED"]
            except KeyboardInterrupt:
                document["collectionIssues"].append("RDS_COLLECTION_INTERRUPTED")
                interrupted = True
                break
            item["collectionIssues"] = issues
            if issues:
                item["attemptIssues"].append({"attempt": attempt, "issues": issues})
        if interrupted:
            break
        incomplete = any(item["applicable"] and
                         (coverage(item, windows)["missingBucketStarts"] or item["collectionIssues"]) for item in document["metrics"])
        if not incomplete:
            break
        if clock() >= deadline:
            document["collectionIssues"].append("RDS_COLLECTION_DEADLINE")
            break
        if attempt < MAX_ATTEMPTS:
            try:
                sleep(min(RETRY_SECONDS, max(0, deadline - clock())))
            except KeyboardInterrupt:
                document["collectionIssues"].append("RDS_COLLECTION_INTERRUPTED")
                break
    for item in document["metrics"]:
        item["coverage"] = coverage(item, windows)
        if item["coverage"]["missingBucketStarts"]:
            item["collectionIssues"].append("RDS_METRIC_MISSING_BUCKETS")
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True, help="Public database identity and UTC phase windows")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        path = Path(args.context)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536, "Invalid collector context")
        context = json.loads(path.read_text(encoding="utf-8"))
        database, _ = validate_context(context)
        aws = AwsCli(database["region"])
        aws.timeout_seconds = 10
        document = collect(aws, context)
        with Path(args.output).open("x", encoding="utf-8") as stream:
            json.dump(document, stream, indent=2, allow_nan=False)
            stream.write("\n")
        return 1 if not document["database"]["verified"] else 0
    except (OSError, RuntimeError, ValueError, KeyError, TypeError):
        print("RDS_COLLECTION_UNAVAILABLE", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
