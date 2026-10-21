"""Independent accounting/window validation for version-1 result artifacts."""

import math
from decimal import Decimal, ROUND_CEILING


ADDITIVE = (
    "scheduled", "attempted", "missed", "accepted", "rejected", "failed", "timedOut", "pendingAdmission",
    "providerArrivals", "providerResponses", "receiptsGenerated", "receiptsForwarded",
    "acceptedProviderCompleted", "acceptedAwaitingProvider", "acceptedReceiptForwarded", "acceptedAwaitingReceipt",
    "providerObservedWithoutAcceptance", "duplicateProviderRequests", "duplicateReceipts",
)
OUTPUT_RATES = {"providerArrivalsPerSecond": "providerArrivals", "providerResponsesPerSecond": "providerResponses",
                "receiptsForwardedPerSecond": "receiptsForwarded"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def equal(actual, expected, message):
    if expected is None:
        require(actual is None, message)
    else:
        require(actual is not None and math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-12), message)


def accounting(counts, dlr):
    require(counts["scheduled"] == counts["attempted"] + counts["missed"], "scheduled != attempted + missed")
    require(counts["attempted"] == sum(counts[key] for key in ("accepted", "rejected", "failed", "timedOut", "pendingAdmission")),
            "Admission outcomes do not partition attempts")
    require(counts["accepted"] == counts["acceptedProviderCompleted"] + counts["acceptedAwaitingProvider"],
            "Provider outcomes do not partition accepted messages")
    if dlr:
        require(counts["accepted"] == counts["acceptedReceiptForwarded"] + counts["acceptedAwaitingReceipt"],
                "Receipt outcomes do not partition accepted messages")
    else:
        require(counts["acceptedAwaitingReceipt"] == 0, "Disabled delivery reports have outstanding receipts")
    if "missingReceipts" in counts:
        require(counts["missingReceipts"] == counts["acceptedAwaitingReceipt"], "Missing receipt count disagrees with accounting")


def distributions(measurement):
    for name, sample in measurement["latencies"].items():
        values = [sample[key] for key in ("minMillis", "p50Millis", "p95Millis", "p99Millis", "maxMillis")]
        if sample["count"] == 0:
            require(all(value is None for value in values), f"Empty {name} distribution must use null statistics")
        else:
            require(all(value is not None for value in values), f"Nonempty {name} distribution has missing statistics")
            require(values == sorted(values), f"Invalid {name} percentile ordering")


def validate_results(document):
    if any(reason.get("severity") == "FAILED" for reason in document["validity"].get("reasons", [])):
        require(document["validity"]["status"] == "failed", "Failed reasons must retain failed classification")
    workload = document["configuration"]["workload"]
    overall = document["overall"]
    counts = overall["counts"]
    duration = overall["durationSeconds"]
    observed_nanos = document["measurementMetadata"].get("observedDurationNanos")
    if observed_nanos is not None:
        require(type(observed_nanos) is int and observed_nanos >= 0, "Invalid resource observation duration")
        equal(duration, observed_nanos / 1_000_000_000, "Resource and workload observation durations differ")
    cohorts = document["cohortIntervals"]
    events = document["eventIntervals"]
    accounting(counts, workload["dlrEnabled"])
    distributions(overall)
    timing = document["measurementMetadata"].get("inputTiming")
    if timing is not None:
        seconds = timing["seconds"]
        require([second["startOffsetSeconds"] for second in seconds] == list(range(len(seconds))),
                "Input seconds must be contiguous and aligned to measurement start")
        dispatches = timing["dispatchesDuringMeasurement"] + timing["dispatchesDuringDrain"]
        require(sum(second["dispatchStarts"] for second in seconds) == dispatches,
                "Input dispatch seconds disagree with measurement/drain totals")
        require(dispatches <= counts["attempted"], "More dispatch starts than submitted targets")
        sample = timing["submissionToDispatch"]
        require(sample["count"] + sample.get("overflowCount", 0) == dispatches,
                "Dispatch timing samples disagree with dispatch starts")
        if document["measurementMetadata"].get("outsideWindowEvents", 0) == 0:
            require(sum(second["submissions"] for second in seconds) == counts["attempted"],
                    "Input seconds disagree with submitted targets")
    expected_start = 0
    for cohort in cohorts:
        start, end = cohort["startOffsetSeconds"], cohort["endOffsetSeconds"]
        equal(start, expected_start, "Cohort windows must be contiguous")
        require(end > start and end - start <= 60, "Invalid cohort window length")
        equal(cohort["durationSeconds"], max(0, min(end, duration) - start), "Incorrect observed cohort duration")
        accounting(cohort["counts"], workload["dlrEnabled"])
        distributions(cohort)
        expected_start = end
        for rate in OUTPUT_RATES:
            require(cohort["rates"][rate] is None, "Cohort completion rate is not event-time throughput")
    if cohorts:
        equal(expected_start, workload["measurementSeconds"], "Planned cohort duration differs from configuration")
        if document["validity"]["status"] == "valid":
            rate = workload["offeredMessagesPerSecond"]
            require(rate <= 1_000_000_000, "Declared rate exceeds nanosecond schedule resolution")
            total = int((Decimal(str(rate)) * workload["measurementSeconds"]).to_integral_value(rounding=ROUND_CEILING))
            period = 1_000_000_000 / rate
            def before(boundary):
                low, high = 0, total
                nanos = int(Decimal(str(boundary)) * 1_000_000_000)
                while low < high:
                    middle = low + (high - low) // 2
                    offset = 0 if middle == 0 else math.ceil(middle * period)
                    if offset < nanos:
                        low = middle + 1
                    else:
                        high = middle
                return low
            for cohort in cohorts:
                require(cohort["counts"]["scheduled"] == before(cohort["endOffsetSeconds"]) - before(cohort["startOffsetSeconds"]),
                        "Valid report does not match the declared fixed-rate minute schedule")
    for key in ADDITIVE:
        require(sum(cohort["counts"][key] for cohort in cohorts) == counts[key], f"Cohort sum differs for {key}")
    for name, sample in overall["latencies"].items():
        require(sum(cohort["latencies"][name]["count"] for cohort in cohorts) == sample["count"], f"Latency sample sum differs for {name}")
    for measurement in [overall, *cohorts]:
        observed = measurement["durationSeconds"]
        equal(measurement["rates"]["attemptedInputPerSecond"], None if observed == 0 else measurement["counts"]["attempted"] / observed,
              "Achieved input rate has the wrong observation denominator")
        equal(measurement["rates"]["requestedInputPerSecond"], None if observed == 0 else workload["offeredMessagesPerSecond"],
              "Requested input rate differs from workload")
    for phase, base, observed in (("measurement", 0, duration), ("drain", duration, document["drain"]["durationSeconds"])):
        phase_events = [event for event in events if event["phase"] == phase]
        cursor = base
        for event in phase_events:
            equal(event["startOffsetSeconds"], cursor, "Event windows must be contiguous within each phase")
            require(0 < event["durationSeconds"] <= 60, "Invalid event window length")
            cursor += event["durationSeconds"]
            for rate, counter in OUTPUT_RATES.items():
                if "rates" in event and counter in event:
                    equal(event["rates"][rate], event[counter] / event["durationSeconds"], "Event rate has the wrong denominator")
            if "outstanding" in event:
                require(all(value >= 0 for value in event["outstanding"].values()), "Negative historical backlog")
        equal(cursor, base + observed, "Event windows do not span their observation phase")
    if document["measurementMetadata"].get("outsideWindowEvents", 0) == 0:
        for key in ("providerArrivals", "providerResponses", "receiptsGenerated", "receiptsForwarded"):
            if all(key in event for event in events):
                require(sum(event[key] for event in events) == counts[key], f"Event accounting differs for {key}")
    for rate, counter in OUTPUT_RATES.items():
        total = sum(event.get(counter, 0) for event in events if event["phase"] == "measurement")
        equal(overall["rates"][rate], None if duration == 0 else total / duration, "Overall rate incorrectly includes drain or uses the wrong denominator")
    if events and "outstanding" in events[-1]:
        final = events[-1]["outstanding"]
        equal(document["drain"]["pendingAdmission"], final["pendingAdmission"], "Drain admission backlog differs from its window end")
        equal(document["drain"]["unfinishedAccepted"], final["acceptedAwaitingReceipt" if workload["dlrEnabled"] else "acceptedAwaitingProvider"],
              "Drain unfinished count differs from its window end")
    if counts.get("violations", 0) > 0:
        require(document["validity"]["status"] == "failed", "Accounting violations must fail execution")
    if "exitCode" in document["validity"]:
        require(document["validity"]["exitCode"] == (1 if document["validity"]["status"] == "failed" else 0), "Validity exit code is inconsistent")
