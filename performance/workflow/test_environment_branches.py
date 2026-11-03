import copy
import json
from pathlib import Path
import re
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from environment_branches import branch_rules, gh_api, load_policy, synchronize


class FakeGitHub:
    def __init__(self, policy):
        self.calls = []
        self.settings = {
            name: {"deployment_branch_policy": {"custom_branch_policies": True, "protected_branches": False},
                   "protection_rules": [{"type": "required_reviewers", "reviewers": ["Maintainers"]}]
                   if name == "sendium-performance" else []}
            for name in policy["environments"]
        }
        self.rules = {name: [{"name": "main", "type": "branch"}, {"name": "feat/*", "type": "branch"}]
                      for name in policy["environments"]}

    def __call__(self, method, path, body=None):
        self.calls.append((method, path, body))
        environment = path.split("/environments/", 1)[1].split("/", 1)[0].split("?", 1)[0]
        if "/deployment-branch-policies" not in path:
            return copy.deepcopy(self.settings[environment])
        if method == "POST":
            self.rules[environment].append(dict(body))
            return dict(body)
        return {"total_count": len(self.rules[environment]), "branch_policies": copy.deepcopy(self.rules[environment])}


class EnvironmentBranchPolicyTest(unittest.TestCase):
    def setUp(self):
        self.policy = load_policy()
        self.api = FakeGitHub(self.policy)

    def test_policy_covers_both_workflow_environments_and_supported_branch_families(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/performance-tests.yml").read_text(encoding="utf-8")
        environments = set(re.findall(r"^\s+environment:\s+(\S+)\s*$", workflow, flags=re.MULTILINE))
        self.assertEqual(set(self.policy["environments"]), environments)
        self.assertEqual(self.policy["branch_patterns"], ["main", "feat/*", "fix/*", "perf/*"])

    def test_check_reports_missing_patterns_in_both_environments_without_writes(self):
        result = synchronize("cytechmobile/sendium", self.policy, api=self.api)

        self.assertEqual([entry["missing"] for entry in result["environments"]], [["fix/*", "perf/*"]] * 2)
        self.assertTrue(all(method == "GET" for method, _, _ in self.api.calls))

    def test_apply_adds_only_missing_rules_and_preserves_reviews_and_extra_rules(self):
        self.api.rules["sendium-performance"].append({"name": "release/*", "type": "branch"})
        settings_before = copy.deepcopy(self.api.settings)

        result = synchronize("cytechmobile/sendium", self.policy, apply=True, api=self.api)

        self.assertTrue(all(not entry["missing"] for entry in result["environments"]))
        self.assertEqual(self.api.settings, settings_before)
        self.assertIn({"name": "release/*", "type": "branch"}, self.api.rules["sendium-performance"])
        writes = [(method, body) for method, _, body in self.api.calls if method != "GET"]
        self.assertEqual(writes, [("POST", {"name": name, "type": "branch"})
                                  for _ in self.policy["environments"] for name in ("fix/*", "perf/*")])

    def test_repeated_apply_is_a_read_only_no_op(self):
        synchronize("cytechmobile/sendium", self.policy, apply=True, api=self.api)
        self.api.calls.clear()

        result = synchronize("cytechmobile/sendium", self.policy, apply=True, api=self.api)

        self.assertTrue(all(not entry["created"] and not entry["missing"] for entry in result["environments"]))
        self.assertTrue(all(method == "GET" for method, _, _ in self.api.calls))

    def test_same_named_tag_rule_does_not_allow_a_branch(self):
        self.api.rules["sendium-performance"].append({"name": "perf/*", "type": "tag"})

        result = synchronize("cytechmobile/sendium", self.policy, apply=True, api=self.api)

        self.assertEqual(result["environments"][0]["created"], ["fix/*", "perf/*"])
        self.assertIn({"name": "perf/*", "type": "branch"}, self.api.rules["sendium-performance"])

    def test_both_environments_are_validated_before_any_write(self):
        self.api.settings["sendium-performance-recovery"]["deployment_branch_policy"] = {
            "custom_branch_policies": False, "protected_branches": True}

        with self.assertRaisesRegex(ValueError, "Selected branches and tags"):
            synchronize("cytechmobile/sendium", self.policy, apply=True, api=self.api)

        self.assertTrue(all(method == "GET" for method, _, _ in self.api.calls))

    def test_apply_verifies_rules_were_persisted(self):
        def ignoring_write(method, path, body=None):
            return {} if method == "POST" else self.api(method, path, body)

        result = synchronize("cytechmobile/sendium", self.policy, apply=True, api=ignoring_write)

        self.assertEqual(result["environments"][0]["missing"], ["fix/*", "perf/*"])

    def test_branch_rules_reads_all_pages(self):
        pages = iter([
            {"total_count": 3, "branch_policies": [{"name": "main", "type": "branch"}]},
            {"total_count": 3, "branch_policies": [{"name": "feat/*", "type": "branch"}, {"name": "perf/*", "type": "branch"}]},
        ])
        self.assertEqual(branch_rules(lambda *args: next(pages), "rules"), {"main", "feat/*", "perf/*"})

    def test_incomplete_pagination_fails_instead_of_assuming_rules_are_absent(self):
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            branch_rules(lambda *args: {"total_count": 2, "branch_policies": []}, "rules")

    def test_invalid_repository_is_rejected_before_api_access(self):
        with self.assertRaises(ValueError):
            synchronize("owner/../unexpected", self.policy, api=self.api)
        self.assertEqual(self.api.calls, [])

    @patch("environment_branches.subprocess.run")
    def test_api_uses_explicit_method_and_json_input(self, run):
        run.return_value = SimpleNamespace(stdout='{"name":"perf/*"}')

        self.assertEqual(gh_api("POST", "repos/owner/repo/rules", {"name": "perf/*", "type": "branch"}),
                         {"name": "perf/*"})
        arguments, options = run.call_args.args[0], run.call_args.kwargs
        self.assertEqual(arguments[:5], ["gh", "api", "--method", "POST", "repos/owner/repo/rules"])
        self.assertEqual(json.loads(options["input"]), {"name": "perf/*", "type": "branch"})
        self.assertTrue(options["check"])

    @patch("environment_branches.subprocess.run")
    def test_api_failure_is_explicit(self, run):
        run.side_effect = subprocess.CalledProcessError(1, ["gh", "api"])
        with self.assertRaisesRegex(RuntimeError, "check repository/environment access"):
            gh_api("GET", "repos/owner/repo/rules")


if __name__ == "__main__":
    unittest.main()
