"""Compare retained DLR-enabled observations after checking workload and runtime equivalence."""

import argparse
import json
from pathlib import Path

from analyze_load import summarize


def artifacts(document):
    return {entry["name"]: entry["sha256"] for entry in document["provenance"]["artifacts"]}


def matched_configuration(baseline, candidate):
    if baseline["configuration"]["workload"] != candidate["configuration"]["workload"]:
        raise ValueError("Comparison workloads differ")
    if not candidate["configuration"]["workload"]["dlrEnabled"]:
        raise ValueError("This comparison requires delivery reports enabled")
    first, second = artifacts(baseline), artifacts(candidate)
    unchanged = ("harness", "sendiumJre", "harnessJre", "node", "transport", "sampler", "preset", "network", "runTemplate")
    for name in unchanged:
        if name not in first or first[name] != second.get(name):
            raise ValueError("Comparison artifact differs: " + name)
    for field in ("jvmArguments", "connections", "dlrStorage"):
        if baseline["configuration"]["application"][field] != candidate["configuration"]["application"][field]:
            raise ValueError("Application runtime setting differs: " + field)
    before = baseline["configuration"]["infrastructure"]["resolution"]
    after = candidate["configuration"]["infrastructure"]["resolution"]
    for field in ("nodes", "database", "executionKind"):
        if before[field] != after[field]:
            raise ValueError("Resolved infrastructure differs: " + field)
    for field in ("poolMinSize", "poolMaxSize", "acquisitionTimeoutMillis", "claimDurationSeconds", "sslMode"):
        if baseline["configuration"]["infrastructure"]["database"][field] != candidate["configuration"]["infrastructure"]["database"][field]:
            raise ValueError("Database setting differs: " + field)
    return {name: first[name] for name in unchanged}


def metrics(summary):
    counts = summary["counts"]
    result = {key: counts[key] for key in ("attempted", "accepted", "rejected", "failed", "timedOut", "missed",
              "acceptedProviderCompleted", "acceptedAwaitingProvider", "acceptedReceiptForwarded", "missingReceipts")}
    result.update(providerResponsesPerSecond=summary["measurementRates"]["providerResponsesPerSecond"],
                  drainSeconds=summary["drain"]["durationSeconds"],
                  providerCompletionP99Millis=summary["latencyMilliseconds"]["providerResponseCompletion"]["p99Millis"],
                  acknowledgementP99Millis=summary["latencyMilliseconds"]["acknowledgement"]["p99Millis"],
                  sendiumCpuAverageOneCorePercent=summary["resources"]["sendium"]["cpuAverageOneCorePercent"],
                  sendiumPeakRssMiB=summary["resources"]["sendium"]["rssPeakMiB"],
                  peakMinuteProviderBacklog=max(summary["minuteProviderBacklog"], default=0))
    return result


def compare(baseline_directory, candidate_directory):
    result = {"schemaVersion": 1, "mode": "local-single-observation-comparison", "scenarios": {},
              "limitations": ["One retained baseline and one candidate run per protocol; latency/CPU differences are observations, not statistical proof.",
                              "Report validity describes measurement/accounting evidence, not successful delivery or a performance SLA.",
                              "Completion latency covers completed messages only; unfinished messages must not be hidden by percentile comparisons.",
                              "These local container observations do not establish AWS capacity."]}
    result["execution"] = {}
    for label, directory in (("baseline", baseline_directory), ("candidate", candidate_directory)):
        outcome = json.loads((Path(directory) / "rehearsal.json").read_text())
        result["execution"][label] = {key: outcome[key] for key in ("exitCode", "failure") if key in outcome}
    for scenario in ("http-dlr", "smpp-dlr"):
        baseline = json.loads((Path(baseline_directory) / scenario / "results.json").read_text())
        candidate = json.loads((Path(candidate_directory) / scenario / "results.json").read_text())
        matched = matched_configuration(baseline, candidate)
        first, second = summarize(baseline), summarize(candidate)
        a, b = metrics(first), metrics(second)
        differences = {key: {"baseline": a[key], "candidate": b[key], "delta": b[key] - a[key],
                             "percentChange": (b[key] / a[key] - 1) * 100 if a[key] else None}
                       for key in a if a[key] is not None and b[key] is not None}
        result["scenarios"][scenario] = {"baselineApplicationCommit": baseline["provenance"]["applicationCommit"],
            "candidateApplicationCommit": candidate["provenance"]["applicationCommit"],
            "matchedArtifacts": matched, "metrics": differences,
            "baselineDrainTimedOut": first["drain"]["timeoutReached"], "candidateDrainTimedOut": second["drain"]["timeoutReached"],
            "baselineInputTiming": first["inputTiming"], "candidateInputTiming": second["inputTiming"]}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--output")
    args = parser.parse_args()
    text = json.dumps(compare(args.baseline, args.candidate), indent=2, allow_nan=False) + "\n"
    if args.output:
        with Path(args.output).open("x", encoding="utf-8") as output:
            output.write(text)
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
