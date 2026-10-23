"""Independent checks for whole-bucket RDS resource attribution and summaries."""

from datetime import datetime, timezone
import math
import re


PHASES = ("warmup", "measurement", "drain")
UNITS = {"CPUUtilization": "Percent", "FreeableMemory": "Bytes", "SwapUsage": "Bytes", "DatabaseConnections": "Count",
         "ReadIOPS": "Count/Second", "WriteIOPS": "Count/Second", "ReadThroughput": "Bytes/Second", "WriteThroughput": "Bytes/Second",
         "ReadLatency": "Seconds", "WriteLatency": "Seconds", "DiskQueueDepth": "Count", "FreeStorageSpace": "Bytes",
         "CPUCreditBalance": "Count", "CPUCreditUsage": "Count", "CPUSurplusCreditBalance": "Count", "CPUSurplusCreditsCharged": "Count"}
SUMS = ("CPUCreditUsage", "CPUSurplusCreditsCharged")


def require(condition, message):
    if not condition:
        raise ValueError("RDS " + message)


def nanos(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.utcoffset() is not None, "timestamps must identify a timezone")
    delta = parsed.astimezone(timezone.utc) - datetime(1970, 1, 1, tzinfo=timezone.utc)
    fraction = re.search(r"\.(\d{1,9})(?:Z|[+-][0-9]{2}:[0-9]{2})$", value)
    tail = int((fraction[1] + "000000000")[:9]) % 1000 if fraction else 0
    return (delta.days * 86400 + delta.seconds) * 1_000_000_000 + delta.microseconds * 1000 + tail


def buckets(window, period):
    start, end = window
    return list(range(start // period * period, end, period)) if start < end else []


def validate_rds_resources(report):
    source = report.get("resources", {}).get("database")
    reasons = report["validity"].get("reasons", [])
    def reason(code, severity):
        require(any(value.get("code") == code and value.get("severity") == severity and value.get("scope") == "resources"
                    for value in reasons), "resource problem lacks explicit classification: " + code)
        require(report["validity"]["status"] == "failed" if severity == "FAILED" else report["validity"]["status"] != "valid",
                "resource problem contradicts report status")
    required = report["configuration"].get("infrastructure", {}).get("database", {}).get("resourceMetricsRequired", False)
    if source is None:
        if required and report["measurementMetadata"].get("observationAvailable", True):
            reason("RDS_RESOURCES_MISSING", "INCONCLUSIVE")
        return
    require(source["schemaVersion"] == 1 and source["namespace"] == "AWS/RDS", "source identity/version mismatch")
    database = source["database"]
    expected = report["configuration"]["infrastructure"]["database"]
    require(database["runIdentity"] == source["runIdentity"], "envelope and database run identity disagree")
    require(database["identifier"].endswith("-run-" + database["runIdentity"]), "database identifier/run namespace mismatch")
    if database["verified"]:
        require(database["arn"] == "arn:{partition}:rds:{region}:{accountId}:db:{identifier}".format(**database), "database ARN mismatch")
    mismatch = source["runIdentity"] != report["provenance"]["runIdentity"] or database["identifier"] != expected.get("identifier") \
        or database["region"] != report["configuration"]["infrastructure"]["region"] \
        or database["instanceClass"] != expected.get("resolved", {}).get("DBInstanceClass", expected.get("instanceType")) \
        or ("accountId" in expected and database["accountId"] != expected["accountId"]) or "RDS_IDENTITY_MISMATCH" in source["collectionIssues"]
    if mismatch:
        reason("RDS_IDENTITY_MISMATCH", "FAILED")
    windows = {phase: (nanos(source["phases"][phase]["start"]), nanos(source["phases"][phase]["end"])) for phase in PHASES}
    require(all(start <= end for start, end in windows.values()) and windows["measurement"][0] < windows["measurement"][1], "phase ordering mismatch")
    require(windows["warmup"][1] == windows["measurement"][0] and windows["measurement"][1] == windows["drain"][0], "phase windows are not contiguous")
    full = windows["warmup"][0], windows["drain"][1]
    require(full[1] - full[0] <= 86340 * 1_000_000_000, "observation window exceeds point budget")
    metadata = report["measurementMetadata"]
    if metadata.get("measurementStartUtc") and metadata.get("observedDurationNanos") is not None:
        start = nanos(metadata["measurementStartUtc"])
        end = start + metadata["observedDurationNanos"]
        if not (0 <= start - windows["measurement"][0] <= 2000 and 0 <= windows["measurement"][1] - end <= 2000):
            reason("RDS_WINDOW_MISMATCH", "FAILED")
    names = [metric["name"] for metric in source["metrics"]]
    require(len(names) == len(set(names)) and (set(names) == set(UNITS) if database["verified"] else not names), "metric set mismatch")
    incomplete = not database["verified"] or bool(source["collectionIssues"])
    family = database["instanceClass"].split(".")[1]
    for metric in source["metrics"]:
        name = metric["name"]
        sums = name in SUMS
        statistics = ["sum"] if sums else ["average", "minimum", "maximum", "sampleCount"]
        require(metric["unit"] == UNITS[name] and metric["periodSeconds"] == (300 if "Credit" in name else 60), "metric unit/period mismatch")
        require(metric["statistics"] == (["Sum"] if sums else ["Average", "Minimum", "Maximum", "SampleCount"]), "metric statistics mismatch")
        applicable = True if "Credit" not in name else None if family.startswith("t") and family not in ("t2", "t3", "t4g") \
            else family in (("t3", "t4g") if name.startswith("CPUSurplus") else ("t2", "t3", "t4g"))
        require(metric["applicable"] is applicable, "metric applicability mismatch")
        period = metric["periodSeconds"] * 1_000_000_000
        expected_full = buckets(full, period)
        require(nanos(metric["queryStart"]) == expected_full[0] and nanos(metric["queryEnd"]) == expected_full[-1] + period, "query boundaries mismatch")
        points = metric["datapoints"]
        starts = [nanos(point["timestamp"]) for point in points]
        require(starts == sorted(set(starts)) and all(start in expected_full for start in starts), "metric timestamps are duplicated, unordered or outside query")
        require(not points or applicable is True and metric["attempts"] > 0, "unexpected observations for an unqueried metric")
        complete = {}
        for point, start in zip(points, starts):
            for field in ("average", "minimum", "maximum", "sampleCount", "sum"):
                value = point[field]
                require(value is None or type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1e100, "invalid datapoint value")
            require(point["sampleCount"] is None or point["sampleCount"] > 0, "invalid sample count")
            require(all(point[field] is None for field in (("average", "minimum", "maximum", "sampleCount") if sums else ("sum",))), "unexpected metric statistic")
            if metric["unit"] == "Percent":
                require(all(point[field] is None or point[field] <= 100 for field in ("average", "minimum", "maximum")), "invalid CPU percentage")
            if not sums:
                values = [point[field] for field in ("minimum", "average", "maximum") if point[field] is not None]
                require(values == sorted(values), "invalid gauge statistics ordering")
            require(nanos(point["bucketEnd"]) == start + period, "bucket end mismatch")
            overlapping = [phase for phase, (left, right) in windows.items() if left < right and start < right and start + period > left]
            boundary = not any(left <= start and start + period <= right for left, right in windows.values())
            require(point["phases"] == overlapping and point["crossesPhaseBoundary"] is boundary, "bucket phase attribution mismatch")
            if all(point[field] is not None for field in statistics):
                complete[start] = point
        def coverage(actual, window):
            expected_buckets = buckets(window, period) if applicable else []
            missing = [start for start in expected_buckets if start not in complete]
            require(actual["expectedBuckets"] == len(expected_buckets) and actual["completeBuckets"] == len(expected_buckets) - len(missing), "coverage counts mismatch")
            require([nanos(value) for value in actual["missingBucketStarts"]] == missing, "missing bucket timestamps mismatch")
            return expected_buckets, missing
        _, missing = coverage(metric["coverage"], full)
        incomplete |= bool(missing) or bool(metric["collectionIssues"]) or applicable is None
        for phase, window in windows.items():
            expected_buckets, _ = coverage(metric["coverage"]["phases"][phase], window)
            summary = metric["summary"][phase]
            coverage(summary, window)
            contained = [point for start, point in complete.items() if applicable and window[0] <= start and start + period <= window[1]]
            boundary = sum(not (window[0] <= start and start + period <= window[1]) for start in expected_buckets)
            require(summary["containedBuckets"] == len(contained) and summary["boundaryBuckets"] == boundary, "summary bucket counts mismatch")
            values = {"average": None, "minimum": None, "maximum": None, "sum": None}
            if contained:
                if sums:
                    values["sum"] = math.fsum(point["sum"] for point in contained)
                else:
                    values.update(average=math.fsum(point["average"] * point["sampleCount"] for point in contained) / math.fsum(point["sampleCount"] for point in contained),
                                  minimum=min(point["minimum"] for point in contained), maximum=max(point["maximum"] for point in contained))
            for field, value in values.items():
                require(summary[field] is None if value is None else summary[field] is not None and math.isclose(summary[field], value, rel_tol=1e-12, abs_tol=1e-12),
                        "phase-only summary includes wrong buckets/statistics: " + field)
    if incomplete:
        reason("RDS_RESOURCE_INCOMPLETE", "INCONCLUSIVE")
