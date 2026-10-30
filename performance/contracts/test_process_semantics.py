"""Tampering checks independent of the JVM process-group importer."""

import copy
import unittest

from process_semantics import validate_process_groups
from test_target_contracts import VALIDATOR, fixture


def group_sample():
    return {"timestamp": "2026-10-30T08:00:01Z", "intervalStartTimestamp": "2026-10-30T08:00:00Z",
            "intervalSeconds": 1, "cpuPercent": 50, "hostCpuPercent": 25,
            "rssBytes": 100, "hostMemoryUsedBytes": 200, "hostMemoryTotalBytes": 1000,
            "cpuCount": 2, "pid": None, "processStartTicks": None,
            "processes": [{"name": "bearerbox", "pid": 11, "processStartTicks": 123, "cpuPercent": 20, "rssBytes": 40},
                          {"name": "smsbox", "pid": 12, "processStartTicks": 124, "cpuPercent": 30, "rssBytes": 60}]}


def document():
    result = fixture("kannel")
    result["validity"].update(status="valid", reasons=[])
    result["resources"]["kannel"] = [group_sample()]
    return result


class ProcessSemanticsTests(unittest.TestCase):
    def test_group_structure_and_null_baseline_validate(self):
        report = document()
        baseline = copy.deepcopy(report["resources"]["kannel"][0])
        baseline.update(timestamp="2026-10-30T08:00:00Z", intervalStartTimestamp=None, intervalSeconds=None, cpuPercent=None)
        for member in baseline["processes"]:
            member["cpuPercent"] = None
        report["resources"]["kannel"].insert(0, baseline)
        VALIDATOR.validate(report)
        validate_process_groups(report)

    def test_group_aggregates_and_single_pid_claims_cannot_be_fabricated(self):
        for key, value, message in (("cpuPercent", 51, "CPU differs"), ("rssBytes", 101, "RSS differs"), ("pid", 11, "single PID")):
            report = document()
            report["resources"]["kannel"][0][key] = value
            with self.assertRaisesRegex(ValueError, message):
                validate_process_groups(report)
        report = document()
        report["resources"]["kannel"][0]["cpuPercent"] = None
        with self.assertRaisesRegex(ValueError, "Baseline"):
            validate_process_groups(report)

    def test_member_budget_duplicate_names_and_duplicate_pids_are_rejected(self):
        for key, value in (("name", "bearerbox"), ("pid", 11)):
            report = document()
            report["resources"]["kannel"][0]["processes"][1][key] = value
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                validate_process_groups(report)
        report = document()
        report["resources"]["kannel"][0]["processes"].pop()
        self.assertFalse(VALIDATOR.is_valid(report))
        with self.assertRaisesRegex(ValueError, "budget"):
            validate_process_groups(report)

    def test_secondary_identity_changes_and_wrong_roles_must_retain_failed_classification(self):
        report = document()
        changed = group_sample()
        changed["timestamp"] = "2026-10-30T08:00:02Z"
        changed["processes"][1]["processStartTicks"] = 999
        report["resources"]["kannel"].append(changed)
        with self.assertRaisesRegex(ValueError, "identity"):
            validate_process_groups(report)
        report["validity"]["status"] = "failed"
        validate_process_groups(report)
        report = document()
        report["resources"]["kannel"][0]["processes"][1]["name"] = "other"
        with self.assertRaisesRegex(ValueError, "bearerbox and smsbox"):
            validate_process_groups(report)

    def test_missing_members_and_sampling_mode_changes_cannot_be_published_as_valid(self):
        report = document()
        del report["resources"]["kannel"][0]["processes"]
        with self.assertRaisesRegex(ValueError, "both gateway"):
            validate_process_groups(report)
        report["validity"]["status"] = "inconclusive"
        validate_process_groups(report)
        report["resources"]["kannel"].insert(0, group_sample())
        with self.assertRaisesRegex(ValueError, "sampling mode"):
            validate_process_groups(report)

    def test_declared_process_identity_is_checked(self):
        report = document()
        expected = copy.deepcopy(report["resources"]["kannel"][0]["processes"])
        expected[1]["pid"] = 88
        report["configuration"]["infrastructure"]["kannel"]["processes"] = expected
        with self.assertRaisesRegex(ValueError, "unexpected group identity"):
            validate_process_groups(report)
        report["validity"]["status"] = "failed"
        validate_process_groups(report)


if __name__ == "__main__":
    unittest.main()
