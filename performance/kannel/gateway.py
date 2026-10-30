"""Bounded local supervisor for bearerbox and smsbox; no AWS operations."""

import signal
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path


def main():
    stopped = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stopped.set())
    processes = []
    deadline = time.monotonic() + 120
    Path("/tmp/kannel-store").mkdir()
    try:
        processes.append(subprocess.Popen([
            "/opt/kannel/sbin/bearerbox", "-v", "2", "/probe/kannel.conf",
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
            "/opt/kannel/sbin/smsbox", "-v", "2", "/probe/kannel.conf",
        ]))
        # A known unauthenticated request checks the actual sendsms handler without
        # creating a synthetic message or putting credentials in a request URL.
        while True:
            check_running(processes, stopped)
            if time.monotonic() >= deadline:
                raise RuntimeError("Smsbox HTTP startup timed out")
            try:
                urllib.request.urlopen("http://127.0.0.1:8080/sendsms", timeout=1).close()
            except urllib.error.HTTPError as response:
                with response:
                    if (response.code == 403 and
                            response.read(4096).strip() == b"Authorization failed for sendsms"):
                        break
            except OSError:
                pass
            stopped.wait(0.1)
        Path("/probe/gateway-ready").write_text("ready\n", encoding="utf-8")
        deadline = time.monotonic() + 180
        while not stopped.wait(0.1):
            check_running(processes, stopped)
            if time.monotonic() >= deadline:
                raise RuntimeError("Local gateway exceeded its lifetime")
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def check_running(processes, stopped):
    if stopped.is_set():
        raise InterruptedError("Local gateway interrupted")
    if any(process.poll() is not None for process in processes):
        raise RuntimeError("A Kannel process exited during startup/readiness")


if __name__ == "__main__":
    main()
