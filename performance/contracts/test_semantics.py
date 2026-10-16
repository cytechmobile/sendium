import copy
import unittest
from semantics import ADDITIVE, OUTPUT_RATES, validate_results


def fixture():
    counts = dict.fromkeys(ADDITIVE, 0)
    counts.update(scheduled=2, attempted=2, accepted=2, providerArrivals=2, providerResponses=2,
                  receiptsGenerated=2, receiptsForwarded=2, acceptedProviderCompleted=2, acceptedReceiptForwarded=2)
    latency = {"count": 2, "minMillis": 1, "p50Millis": 1, "p95Millis": 1, "p99Millis": 1, "maxMillis": 1}
    measurement = {"durationSeconds": 10, "counts": counts, "latencies": {"acknowledgement": latency},
                   "rates": {"requestedInputPerSecond": 0.2, "attemptedInputPerSecond": 0.2,
                             "providerArrivalsPerSecond": 0.2, "providerResponsesPerSecond": 0.2, "receiptsForwardedPerSecond": 0.1}}
    cohort = copy.deepcopy(measurement)
    cohort.update(startOffsetSeconds=0, endOffsetSeconds=10)
    cohort["rates"].update(dict.fromkeys(OUTPUT_RATES, None))
    return {"configuration": {"workload": {"dlrEnabled": True, "measurementSeconds": 10, "offeredMessagesPerSecond": 0.2}},
            "overall": measurement, "cohortIntervals": [cohort], "eventIntervals": [
                {"phase": "measurement", "startOffsetSeconds": 0, "durationSeconds": 10,
                 "providerArrivals": 2, "providerResponses": 2, "receiptsGenerated": 2, "receiptsForwarded": 1,
                 "rates": {"providerArrivalsPerSecond": 0.2, "providerResponsesPerSecond": 0.2, "receiptsForwardedPerSecond": 0.1},
                 "outstanding": {"pendingAdmission": 0, "acceptedAwaitingProvider": 0, "acceptedAwaitingReceipt": 1}},
                {"phase": "drain", "startOffsetSeconds": 10, "durationSeconds": 5,
                 "providerArrivals": 0, "providerResponses": 0, "receiptsGenerated": 0, "receiptsForwarded": 1,
                 "rates": {"providerArrivalsPerSecond": 0, "providerResponsesPerSecond": 0, "receiptsForwardedPerSecond": 0.2},
                 "outstanding": {"pendingAdmission": 0, "acceptedAwaitingProvider": 0, "acceptedAwaitingReceipt": 0}}],
            "drain": {"durationSeconds": 5, "unfinishedAccepted": 0, "pendingAdmission": 0},
            "measurementMetadata": {"outsideWindowEvents": 0}, "validity": {"status": "valid", "exitCode": 0}}


class SemanticValidationTests(unittest.TestCase):
    def test_valid_drain_completion_is_not_measurement_throughput(self):
        validate_results(fixture())

    def test_drain_inflated_rates_and_missing_cohort_counts_are_rejected(self):
        document = fixture()
        document["overall"]["rates"]["receiptsForwardedPerSecond"] = 0.2
        with self.assertRaisesRegex(ValueError, "drain"):
            validate_results(document)
        document = fixture()
        document["cohortIntervals"][0]["counts"]["receiptsForwarded"] = 1
        with self.assertRaisesRegex(ValueError, "Cohort sum"):
            validate_results(document)

    def test_missing_intervals_and_invalid_latencies_are_rejected(self):
        document = fixture()
        document["eventIntervals"][1]["startOffsetSeconds"] = 11
        with self.assertRaisesRegex(ValueError, "contiguous"):
            validate_results(document)
        document = fixture()
        document["overall"]["latencies"]["acknowledgement"]["p95Millis"] = 0
        with self.assertRaisesRegex(ValueError, "percentile"):
            validate_results(document)

    def test_accounting_failure_cannot_be_hidden_by_inconclusive_validity(self):
        document = fixture()
        document["overall"]["counts"]["violations"] = 1
        document["validity"] = {"status": "inconclusive", "exitCode": 0}
        with self.assertRaisesRegex(ValueError, "must fail"):
            validate_results(document)

    def test_outside_window_outcomes_are_disclosed_but_never_added_to_event_rates(self):
        document = fixture()
        document["measurementMetadata"]["outsideWindowEvents"] = 1
        document["eventIntervals"][1]["receiptsForwarded"] = 0
        document["eventIntervals"][1]["rates"]["receiptsForwardedPerSecond"] = 0
        document["eventIntervals"][1]["outstanding"]["acceptedAwaitingReceipt"] = 1
        document["drain"]["unfinishedAccepted"] = 1
        document["validity"] = {"status": "inconclusive", "exitCode": 0}
        validate_results(document)

    def test_correct_global_volume_cannot_be_claimed_valid_with_a_burst_in_one_minute(self):
        document = fixture()
        document["configuration"]["workload"].update(offeredMessagesPerSecond=1 / 60, measurementSeconds=120)
        document["overall"]["durationSeconds"] = 120
        document["overall"]["rates"].update(requestedInputPerSecond=1 / 60, attemptedInputPerSecond=2 / 120,
                                             providerArrivalsPerSecond=2 / 120, providerResponsesPerSecond=2 / 120,
                                             receiptsForwardedPerSecond=1 / 120)
        first = document["cohortIntervals"][0]
        first.update(durationSeconds=60, endOffsetSeconds=60)
        first["rates"].update(requestedInputPerSecond=1 / 60, attemptedInputPerSecond=2 / 60)
        second = copy.deepcopy(first)
        second.update(startOffsetSeconds=60, endOffsetSeconds=120)
        second["counts"].update(dict.fromkeys(ADDITIVE, 0))
        second["rates"]["attemptedInputPerSecond"] = 0
        second["latencies"]["acknowledgement"] = {"count": 0, "minMillis": None, "p50Millis": None,
                                                 "p95Millis": None, "p99Millis": None, "maxMillis": None}
        document["cohortIntervals"].append(second)
        first_event = document["eventIntervals"][0]
        first_event["durationSeconds"] = 60
        first_event["rates"].update(providerArrivalsPerSecond=2 / 60, providerResponsesPerSecond=2 / 60,
                                    receiptsForwardedPerSecond=1 / 60)
        second_event = copy.deepcopy(first_event)
        second_event["startOffsetSeconds"] = 60
        second_event.update(providerArrivals=0, providerResponses=0, receiptsGenerated=0, receiptsForwarded=0)
        second_event["rates"].update(dict.fromkeys(OUTPUT_RATES, 0))
        document["eventIntervals"][1]["startOffsetSeconds"] = 120
        document["eventIntervals"].insert(1, second_event)
        with self.assertRaisesRegex(ValueError, "declared fixed-rate minute schedule"):
            validate_results(document)
        document["validity"] = {"status": "failed", "exitCode": 1}
        validate_results(document)  # The failed run can still produce an internally consistent artifact.


if __name__ == "__main__":
    unittest.main()
