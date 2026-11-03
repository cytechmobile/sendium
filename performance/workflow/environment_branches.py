"""Check or add the maintained performance-environment deployment branch rules."""

import argparse
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote


POLICY_PATH = Path(__file__).with_name("environment-branches.json")


def load_policy(path=POLICY_PATH):
    policy = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(policy, dict) or set(policy) != {"environments", "branch_patterns"}:
        raise ValueError("Expected environments and branch_patterns in the environment policy")
    for name, values in policy.items():
        if not isinstance(values, list) or not values or any(
                not isinstance(value, str) or not value.strip() or value != value.strip()
                or any(character in value for character in "\r\n") for value in values):
            raise ValueError("Invalid environment policy list: " + name)
        if len(set(values)) != len(values):
            raise ValueError("Duplicate environment policy entries: " + name)
    return policy


def gh_api(method, path, body=None):
    command = ["gh", "api", "--method", method, path,
               "--header", "Accept: application/vnd.github+json",
               "--header", "X-GitHub-Api-Version: 2022-11-28"]
    if body is not None:
        command += ["--input", "-"]
    try:
        result = subprocess.run(command, input=json.dumps(body) if body is not None else None,
                                capture_output=True, text=True, check=True, timeout=30)
    except subprocess.CalledProcessError as error:
        raise RuntimeError("GitHub API failed for " + path + "; check repository/environment access") from error
    return json.loads(result.stdout)


def branch_rules(api, path):
    rules = []
    page = 1
    while True:
        response = api("GET", path + "?per_page=100&page=" + str(page))
        entries, total = response.get("branch_policies"), response.get("total_count")
        if not isinstance(entries, list) or type(total) is not int or total < 0:
            raise ValueError("Invalid deployment branch policy response")
        rules.extend(entries)
        if len(rules) >= total:
            return {rule["name"] for rule in rules if rule.get("type", "branch") == "branch"}
        if not entries:
            raise ValueError("Incomplete deployment branch policy response")
        page += 1


def synchronize(repository, policy, apply=False, api=gh_api):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", repository):
        raise ValueError("Repository must be an owner/name pair")
    plans = []
    # Validate both existing environments before adding any rule. Never replace environment settings.
    for environment in policy["environments"]:
        path = "repos/" + repository + "/environments/" + quote(environment, safe="")
        settings = api("GET", path)
        selection = settings.get("deployment_branch_policy") or {}
        if selection.get("custom_branch_policies") is not True or selection.get("protected_branches") is not False:
            raise ValueError(environment + " must already use Selected branches and tags")
        rules_path = path + "/deployment-branch-policies"
        existing = branch_rules(api, rules_path)
        plans.append({"name": environment, "path": rules_path, "existing": existing, "created": []})

    for plan in plans:
        if apply:
            for pattern in policy["branch_patterns"]:
                if pattern in plan["existing"]:
                    continue
                api("POST", plan["path"], {"name": pattern, "type": "branch"})
                plan["created"].append(pattern)
            plan["existing"] = branch_rules(api, plan["path"])
    return {
        "repository": repository,
        "apply": apply,
        "environments": [
            {"name": plan["name"], "created": plan["created"],
             "missing": [pattern for pattern in policy["branch_patterns"] if pattern not in plan["existing"]]}
            for plan in plans
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="GitHub repository owner/name")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Read-only check (default)")
    mode.add_argument("--apply", action="store_true", help="Add missing branch rules to both existing environments")
    args = parser.parse_args()
    try:
        result = synchronize(args.repository, load_policy(), apply=args.apply)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(2, str(error) + "\n")
    print(json.dumps(result, indent=2))
    return int(any(environment["missing"] for environment in result["environments"]))


if __name__ == "__main__":
    raise SystemExit(main())
