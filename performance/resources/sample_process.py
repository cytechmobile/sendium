"""Bounded Linux /proc sampling. CPU timing uses monotonic time, UTC identifies windows."""

import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import sys
import time
import signal


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def read_state(pid, proc=Path("/proc")):
    # comm may contain spaces and parentheses; fields after its last ')' start at field 3.
    fields = (proc / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()
    cpu_ticks = int(fields[11]) + int(fields[12])
    start_ticks = int(fields[19])
    rss_bytes = int(fields[21]) * os.sysconf("SC_PAGE_SIZE")
    host = [int(value) for value in (proc / "stat").read_text().splitlines()[0].split()[1:]]
    # guest/guest_nice are already included in user/nice; do not count them twice.
    total = sum(host[:8])
    idle = host[3] + host[4]
    memory = {}
    for line in (proc / "meminfo").read_text().splitlines():
        key, value = line.split(":", 1)
        memory[key] = int(value.split()[0]) * 1024
    return {
        "pid": pid,
        "processTicks": cpu_ticks,
        "startTicks": start_ticks,
        "rssBytes": rss_bytes,
        "hostTicks": total,
        "hostBusyTicks": total - idle,
        "hostMemoryTotalBytes": memory["MemTotal"],
        "hostMemoryUsedBytes": memory["MemTotal"] - memory["MemAvailable"],
        "cpuCount": os.cpu_count(),
    }


def make_sample(current, previous, utc, previous_utc, elapsed, ticks_per_second):
    if previous is not None and current["startTicks"] != previous["startTicks"]:
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
    return {
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


def collect(pid, duration, interval, max_samples, output, stop_requested=lambda: False):
    if pid < 1 or not math.isfinite(duration) or duration <= 0 or not math.isfinite(interval) or interval <= 0:
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
        current = read_state(pid)
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
    parser.add_argument("--pid", required=True, type=int)
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
        if args.output == "-":
            collect(args.pid, args.duration_seconds, args.interval_seconds, args.max_samples, sys.stdout, lambda: stopping)
        else:
            with Path(args.output).open("x", encoding="utf-8") as output:
                collect(args.pid, args.duration_seconds, args.interval_seconds, args.max_samples, output, lambda: stopping)
        return 0
    except (OSError, ValueError, KeyError, IndexError):
        print("RESOURCE_SAMPLING_FAILED: process unavailable, invalid counters, settings, or output", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
