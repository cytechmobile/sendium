"""Independent checks for bounded process groups in gateway resource reports."""

import math
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identities(members):
    require(isinstance(members, list) and 2 <= len(members) <= 16, "Invalid process-group member budget")
    result = {}
    pids = set()
    for member in members:
        require(isinstance(member, dict), "Invalid process-group member")
        name, pid, start = member.get("name"), member.get("pid"), member.get("processStartTicks")
        require(isinstance(name, str) and re.fullmatch(r"[a-z][a-z0-9_-]{0,31}", name), "Invalid process-group name")
        require(type(pid) is int and 0 < pid <= 2**63 - 1 and type(start) is int and 0 <= start <= 2**63 - 1,
                "Invalid process-group identity")
        require(name not in result and pid not in pids, "Duplicate process-group name or PID")
        result[name] = (pid, start)
        pids.add(pid)
    return result


def validate_process_groups(document):
    resources = document.get("resources", {})
    configuration = document.get("configuration", {})
    target = configuration.get("application", {}).get("benchmarkTarget", "sendium")
    infrastructure = configuration.get("infrastructure", {})
    status = document.get("validity", {}).get("status")
    if target == "kannel" and status == "valid":
        require(bool(resources.get("kannel")), "Valid Kannel resources require both gateway processes")
    for source in (target, "harness"):
        metadata = infrastructure.get(source, {})
        expected = metadata.get("processes")
        if expected is not None:
            try:
                expected = identities(expected)
            except (ValueError, AttributeError, TypeError):
                require(status == "failed", "Invalid process-group metadata must fail resource validity")
                expected = None
        first = None
        grouped = None
        for sample in resources.get(source, []):
            members = sample.get("processes")
            group = members is not None
            if grouped is not None and grouped != group:
                require(status == "failed", "Changing process sampling mode must fail resource validity")
            grouped = group
            if not group:
                if expected is not None:
                    require(status == "failed", "Omitting an expected process group must fail resource validity")
                if source == "kannel":
                    require(status != "valid", "Valid Kannel resources require both gateway processes")
                continue
            actual = identities(members)
            require(sample.get("pid") is None and sample.get("processStartTicks") is None,
                    "Process-group evidence must not claim a single PID")
            rss = [member.get("rssBytes") for member in members]
            require(all(type(value) is int and 0 <= value <= 2**63 - 1 for value in rss)
                    and sample.get("rssBytes") == sum(rss) <= 2**63 - 1,
                    "Process-group RSS differs from summed members")
            cpu = sample.get("cpuPercent")
            member_cpu = [member.get("cpuPercent") for member in members]
            if cpu is None:
                require(all(value is None for value in member_cpu), "Baseline process-group CPU must remain null")
            else:
                require(all(type(value) in (int, float) and math.isfinite(value) and value >= 0 for value in member_cpu)
                        and type(cpu) in (int, float) and math.isfinite(cpu)
                        and math.isclose(cpu, sum(member_cpu), rel_tol=1e-12, abs_tol=1e-12),
                        "Process-group CPU differs from summed members")
            if (first is not None and first != actual or expected is not None and expected != actual
                    or metadata.get("processId") is not None):
                require(status == "failed", "Changed or unexpected group identity must fail resource validity")
            if source == "kannel" and set(actual) != {"bearerbox", "smsbox"}:
                require(status == "failed", "Kannel process group must include bearerbox and smsbox exactly")
            first = actual
