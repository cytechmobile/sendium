"""Bounded local supervisor for bearerbox and smsbox; no AWS operations."""

import signal
import json
import math
import re
import os
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path


def main(root=Path("/probe"), kannel=Path("/opt/kannel"), agent=Path("/opt/probe/sample_process.py"),
         store=Path("/tmp/kannel-store"), http_port=8080):
    settings = {"lifetimeSeconds": 180, "intervalSeconds": 1, "maxSamples": 256}
    plan = root / "benchmark-settings.json"
    if plan.is_file():
        if plan.stat().st_size > 8192:
            raise ValueError("Gateway settings exceed their budget")
        settings = json.loads(plan.read_text(encoding="utf-8"))
        if (not isinstance(settings, dict) or settings.get("schemaVersion") != 1
                or not isinstance(settings.get("runIdentity"), str) or not re.fullmatch(r"[A-Za-z0-9-]{1,32}", settings["runIdentity"])
                or type(settings.get("lifetimeSeconds")) is not int or not 1 <= settings["lifetimeSeconds"] <= 6000
                or type(settings.get("maxSamples")) is not int or not 2 <= settings["maxSamples"] <= 16384
                or type(settings.get("intervalSeconds")) not in (int, float)
                or not math.isfinite(settings["intervalSeconds"]) or not 0 < settings["intervalSeconds"] <= 60
                or math.ceil(settings["lifetimeSeconds"] / settings["intervalSeconds"]) + 1 > settings["maxSamples"]):
            raise ValueError("Invalid bounded gateway settings")
    stopped = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stopped.set())
    processes = []
    sampler = None
    database_ready = None
    database_file = root / "database.json"
    if database_file.is_file():
        from postgresql_probe import check
        if database_file.stat().st_size > 8192:
            raise ValueError("Database settings exceed their budget")
        database = json.loads(database_file.read_text(encoding="utf-8"))
        os.environ.update(PGSSLMODE="verify-full", PGSSLROOTCERT=database["caFile"], PGCONNECT_TIMEOUT="5", PGAPPNAME="kannel-benchmark")
        database_ready = check(database)
    deadline = time.monotonic() + 120
    store.mkdir()
    try:
        processes.append(subprocess.Popen([
            str(kannel / "sbin/bearerbox"), "-v", "2", str(root / "kannel.conf"),
        ]))
        while True:
            check_running(processes, stopped)
            if time.monotonic() >= deadline:
                raise RuntimeError("Bearerbox startup timed out")
            try:
                with socket.create_connection(("127.0.0.1", 13001), timeout=1):
                    break
            except OSError:
                stopped.wait(0.1)
        processes.append(subprocess.Popen([
            str(kannel / "sbin/smsbox"), "-v", "2", str(root / "kannel.conf"),
        ]))
        # A known unauthenticated request checks the actual sendsms handler without
        # creating a synthetic message or putting credentials in a request URL.
        while True:
            check_running(processes, stopped)
            if time.monotonic() >= deadline:
                raise RuntimeError("Smsbox HTTP startup timed out")
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{http_port}/sendsms", timeout=1).close()
            except urllib.error.HTTPError as response:
                with response:
                    if (response.code == 403 and
                            response.read(4096).strip() == b"Authorization failed for sendsms"):
                        break
            except OSError:
                # The HTTP listener may still be starting; retry until the bounded deadline.
                pass
            stopped.wait(0.1)
        samples = root / "kannel-resources.jsonl"
        sampler = subprocess.Popen([
            "python3", str(agent),
            "--process", f"bearerbox:{processes[0].pid}", "--process", f"smsbox:{processes[1].pid}",
            "--duration-seconds", str(settings["lifetimeSeconds"]), "--interval-seconds", str(settings["intervalSeconds"]),
            "--max-samples", str(settings["maxSamples"]),
            "--output", str(samples),
        ])
        while True:
            check_running(processes, stopped)
            if sampler.poll() is not None:
                raise RuntimeError("Gateway sampler exited before its baseline")
            if samples.exists():
                with samples.open(encoding="utf-8") as source:
                    baseline = source.readline(8193)
                    if baseline.endswith("\n"):
                        break
            if time.monotonic() >= deadline:
                raise RuntimeError("Gateway sampler startup timed out")
            stopped.wait(0.1)
        if "runIdentity" in settings:
            members = json.loads(baseline)["processes"]
            identity = [{key: member[key] for key in ("name", "pid", "processStartTicks")} for member in members]
            temporary = root / "gateway-ready.tmp"
            temporary.write_text(json.dumps({"schemaVersion": 1, "runIdentity": settings["runIdentity"], "processes": identity,
                                              "database": database_ready}) + "\n",
                                 encoding="utf-8")
            temporary.replace(root / "gateway-ready.json")
        (root / "gateway-ready").write_text("ready\n", encoding="utf-8")
        deadline = time.monotonic() + settings["lifetimeSeconds"]
        while not stopped.wait(0.1):
            check_running(processes, stopped)
            if sampler.poll() is not None:
                raise RuntimeError("Gateway sampler stopped during the local run")
            if time.monotonic() >= deadline:
                raise RuntimeError("Local gateway exceeded its lifetime")
    finally:
        try:
            # Retain the final complete group interval while both members are alive.
            if sampler is not None and stop_process(sampler, math.ceil(settings["intervalSeconds"]) + 5) != 0:
                raise RuntimeError("Gateway sampler did not finish successfully")
        finally:
            for process in reversed(processes):
                stop_process(process, 5)


def stop_process(process, timeout):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    return process.returncode


def check_running(processes, stopped):
    if stopped.is_set():
        raise InterruptedError("Local gateway interrupted")
    if any(process.poll() is not None for process in processes):
        raise RuntimeError("A Kannel process exited during startup/readiness")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/probe"))
    parser.add_argument("--kannel", type=Path, default=Path("/opt/kannel"))
    parser.add_argument("--agent", type=Path, default=Path("/opt/probe/sample_process.py"))
    parser.add_argument("--store", type=Path, default=Path("/tmp/kannel-store"))
    parser.add_argument("--http-port", type=int, default=8080)
    args = parser.parse_args()
    main(args.root, args.kannel, args.agent, args.store, args.http_port)
