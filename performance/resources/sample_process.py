"""Bounded Linux /proc sampling. CPU timing uses monotonic time, UTC identifies windows."""

import argparse
from datetime import datetime, timezone
import json
import math
import os
import re
from pathlib import Path
import sys
import time
import signal


def timestamp():
    return datetime.now(timezone.utc).isoformat()


MAX_PROCESSES = 16


def read_process(pid, proc):
    # comm may contain spaces and parentheses; fields after its last ')' start at field 3.
    fields = (proc / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()
    cpu_ticks = int(fields[11]) + int(fields[12])
    start_ticks = int(fields[19])
    rss_bytes = int(fields[21]) * os.sysconf("SC_PAGE_SIZE")
    if fields[0] in ("Z", "X", "x") or min(cpu_ticks, start_ticks, rss_bytes) < 0:
        raise ValueError("Process unavailable or invalid counters")
    return {"pid": pid, "processTicks": cpu_ticks, "startTicks": start_ticks, "rssBytes": rss_bytes}


def read_host(proc):
    host = [int(value) for value in (proc / "stat").read_text().splitlines()[0].split()[1:]]
    # guest/guest_nice are already included in user/nice; do not count them twice.
    total = sum(host[:8])
    idle = host[3] + host[4]
    memory = {}
    for line in (proc / "meminfo").read_text().splitlines():
        key, value = line.split(":", 1)
        memory[key] = int(value.split()[0]) * 1024
    return {
        "hostTicks": total,
        "hostBusyTicks": total - idle,
        "hostMemoryTotalBytes": memory["MemTotal"],
        "hostMemoryUsedBytes": memory["MemTotal"] - memory["MemAvailable"],
        "cpuCount": os.cpu_count(),
    }


def read_state(pid, proc=Path("/proc")):
    return {**read_process(pid, proc), **read_host(proc)}


def normalize_members(members):
    if not 2 <= len(members) <= MAX_PROCESSES:
        raise ValueError("Process groups require 2 to 16 explicit members")
    if any(not re.fullmatch(r"[a-z][a-z0-9_-]{0,31}", name) or type(pid) is not int or pid < 1
           for name, pid in members):
        raise ValueError("Invalid named process")
    if len({name for name, _ in members}) != len(members) or len({pid for _, pid in members}) != len(members):
        raise ValueError("Duplicate process name or PID")
    return sorted(members)


def read_group_state(members, proc=Path("/proc")):
    # Missing/dead members abort the whole observation; do not silently shrink the group.
    processes = [{"name": name, **read_process(pid, proc)} for name, pid in normalize_members(members)]
    return {**read_host(proc), "pid": None, "startTicks": None,
            "processTicks": sum(process["processTicks"] for process in processes),
            "rssBytes": sum(process["rssBytes"] for process in processes), "processes": processes}


def member_identities(processes):
    return {process["name"]: (process["pid"], process["startTicks"]) for process in processes}


def make_sample(current, previous, utc, previous_utc, elapsed, ticks_per_second):
    if previous is not None and (current["pid"] != previous["pid"] or current["startTicks"] != previous["startTicks"]
                                 or member_identities(current.get("processes", []))
                                 != member_identities(previous.get("processes", []))):
        raise ValueError("Process identity changed")
    process_cpu = host_cpu = None
    if previous is not None:
        process_ticks = current["processTicks"] - previous["processTicks"]
        host_ticks = current["hostTicks"] - previous["hostTicks"]
        host_busy = current["hostBusyTicks"] - previous["hostBusyTicks"]
        if elapsed <= 0 or process_ticks < 0 or host_ticks <= 0 or host_busy < 0 or host_busy > host_ticks:
            raise ValueError("Invalid sampling interval")
        process_cpu = 100 * process_ticks / ticks_per_second / elapsed
        host_cpu = 100 * host_busy / host_ticks
    sample = {
        "timestamp": utc,
        "intervalStartTimestamp": previous_utc,
        "intervalSeconds": elapsed if previous is not None else None,
        "cpuPercent": process_cpu,
        "hostCpuPercent": host_cpu,
        "rssBytes": current["rssBytes"],
        "hostMemoryUsedBytes": current["hostMemoryUsedBytes"],
        "hostMemoryTotalBytes": current["hostMemoryTotalBytes"],
        "cpuCount": current["cpuCount"],
        "pid": current["pid"],
        "processStartTicks": current["startTicks"],
    }
    if "processes" in current:
        old = {} if previous is None else {process["name"]: process for process in previous["processes"]}
        members = []
        for process in current["processes"]:
            cpu = None
            if previous is not None:
                delta = process["processTicks"] - old[process["name"]]["processTicks"]
                if delta < 0:
                    raise ValueError("A member CPU counter regressed")
                cpu = 100 * delta / ticks_per_second / elapsed
            members.append({"name": process["name"], "pid": process["pid"],
                            "processStartTicks": process["startTicks"], "cpuPercent": cpu,
                            "rssBytes": process["rssBytes"]})
        sample["processes"] = members
        sample["cpuPercent"] = None if previous is None else sum(process["cpuPercent"] for process in members)
    return sample


def collect(pid, duration, interval, max_samples, output, stop_requested=lambda: False):
    members = None if type(pid) is int else normalize_members(pid)
    if members is None and pid < 1 or not math.isfinite(duration) or duration <= 0 or not math.isfinite(interval) or interval <= 0:
        raise ValueError("Invalid sampling settings")
    # Baseline plus periodic samples, including one at the requested end.
    if math.ceil(duration / interval) + 1 > max_samples:
        raise ValueError("Sampling exceeds the configured sample budget")
    start = time.monotonic()
    previous = previous_utc = previous_time = None
    count = 0
    ticks_per_second = os.sysconf("SC_CLK_TCK")
    while True:
        now = time.monotonic()
        current = read_state(pid) if members is None else read_group_state(members)
        utc = timestamp()
        sample = make_sample(current, previous, utc, previous_utc,
                             0 if previous_time is None else now - previous_time, ticks_per_second)
        output.write(json.dumps(sample, allow_nan=False) + "\n")
        output.flush()
        count += 1
        previous, previous_utc, previous_time = current, utc, now
        elapsed = time.monotonic() - start
        if elapsed >= duration or stop_requested():
            return count
        if count >= max_samples:
            raise ValueError("Sampling exhausted the configured sample budget")
        target = min(duration, count * interval)
        time.sleep(max(0, target - elapsed))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--pid", type=int, help="Single process, preserving the existing sample format")
    target.add_argument("--process", action="append", metavar="NAME:PID", help="Repeat for each fixed group member (2-16)")
    parser.add_argument("--duration-seconds", required=True, type=float)
    parser.add_argument("--interval-seconds", type=float, default=1)
    parser.add_argument("--max-samples", type=int, default=4096)
    parser.add_argument("--output", required=True, help="New JSONL path, or '-' for stdout")
    args = parser.parse_args()
    try:
        if not sys.platform.startswith("linux"):
            raise ValueError("The sampling agent requires Linux /proc")
        stopping = False
        def stop(*_):
            nonlocal stopping
            stopping = True
        signal.signal(signal.SIGTERM, stop)
        selected = args.pid if args.process is None else normalize_members(
            [(name, int(pid)) for name, pid in (value.split(":", 1) for value in args.process)])
        if args.output == "-":
            collect(selected, args.duration_seconds, args.interval_seconds, args.max_samples, sys.stdout, lambda: stopping)
        else:
            with Path(args.output).open("x", encoding="utf-8") as output:
                collect(selected, args.duration_seconds, args.interval_seconds, args.max_samples, output, lambda: stopping)
        return 0
    except (OSError, ValueError, KeyError, IndexError):
        print("RESOURCE_SAMPLING_FAILED: process unavailable, invalid counters, settings, or output", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
