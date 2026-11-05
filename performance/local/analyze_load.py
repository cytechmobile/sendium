"""Summarize sustained traffic evidence without inventing latency/throughput thresholds."""

import argparse
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "contracts"))
from semantics import validate_results
from scenarios import DEFAULT, LOAD


def summarize(document):
    validate_results(document)
    workload = document["configuration"]["workload"]
    minutes = [event for event in document["eventIntervals"] if event["phase"] == "measurement"]
    counts = document["overall"]["counts"]
    resources = document["resources"].get("summary", {}).get("overall", {})
    timing = document["measurementMetadata"].get("inputTiming")
    timing_summary = None
    if timing is not None:
        full_seconds = [second for second in timing["seconds"]
                        if second["startOffsetSeconds"] + 1 <= document["overall"]["durationSeconds"]]
        timing_summary = {key: value for key, value in timing.items() if key != "seconds"}
        for field in ("submissions", "dispatchStarts"):
            values = [second[field] for second in full_seconds]
            timing_summary[field + "PerFullSecondMinMax"] = [min(values), max(values)] if values else None
    return {"status": document["validity"]["status"], "workload": workload,
        "expectedMeasuredTargets": math.ceil(workload["offeredMessagesPerSecond"] * workload["measurementSeconds"]),
        "observedSeconds": document["overall"]["durationSeconds"], "counts": counts,
        "measurementRates": document["overall"]["rates"],
        "generatorPhases": document["measurementMetadata"].get("generatorPhases", []),
        "inputTiming": timing_summary,
        "minuteProviderResponses": [event["providerResponses"] for event in minutes],
        "minuteProviderRates": [event["rates"]["providerResponsesPerSecond"] for event in minutes],
        "minuteReceiptRates": [event["rates"]["receiptsForwardedPerSecond"] for event in minutes],
        "minuteProviderBacklog": [event["outstanding"]["acceptedAwaitingProvider"] for event in minutes],
        "minuteReceiptBacklog": [event["outstanding"]["acceptedAwaitingReceipt"] for event in minutes],
        "drain": document["drain"], "latencyMilliseconds": document["overall"]["latencies"],
        "resources": {source: {"cpuAverageOneCorePercent": values.get("cpuPercentAverage"),
                               "cpuMaximumOneCorePercent": values.get("cpuPercentMaximum"),
                               "rssPeakMiB": values["rssBytesMaximum"] / 1048576 if values.get("rssBytesMaximum") is not None else None,
                               "coverageSeconds": values.get("cpuCoverageNanos", 0) / 1000000000}
                      for source, values in resources.items()}}


def analyze(directory, include_no_dlr=False, scenarios=None):
    cases, shared = {}, None
    selected = scenarios if scenarios is not None else LOAD if include_no_dlr else DEFAULT
    if not selected or any(scenario not in LOAD for scenario in selected):
        raise ValueError("Select normal load scenarios for analysis")
    for scenario in selected:
        path = Path(directory) / scenario / "results.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        result = summarize(document)
        limits = {key: value for key, value in result["workload"].items() if key not in ("ingressMode", "dlrEnabled")}
        if shared is not None and limits != shared:
            raise ValueError("Load scenarios used different workload limits")
        shared = limits
        cases[scenario] = result
    return {"schemaVersion": 1, "mode": "local-load-evidence", "sharedLimits": shared, "scenarios": cases,
            "interpretation": "Inspect per-minute sending and backlog alongside complete accounting; no arbitrary performance SLA is applied."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?")
    parser.add_argument("--report", help="Analyze one results.json, including an inconclusive exploration")
    parser.add_argument("--output")
    parser.add_argument("--include-no-dlr", action="store_true", help="Also analyze explicitly run no-DLR scenarios")
    parser.add_argument("--scenarios", help="Explicit comma-separated load scenarios to analyze")
    args = parser.parse_args()
    if args.include_no_dlr and args.scenarios:
        parser.error("Use --scenarios or --include-no-dlr")
    if bool(args.directory) == bool(args.report):
        parser.error("Provide a results directory or --report")
    result = {"schemaVersion": 1, "mode": "local-load-evidence", "observation": summarize(json.loads(Path(args.report).read_text(encoding="utf-8")))} \
        if args.report else analyze(args.directory, args.include_no_dlr, tuple(args.scenarios.split(",")) if args.scenarios else None)
    text = json.dumps(result, indent=2, allow_nan=False) + "\n"
    if args.output:
        with Path(args.output).open("x", encoding="utf-8") as output:
            output.write(text)
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
