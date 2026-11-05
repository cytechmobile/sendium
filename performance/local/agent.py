"""Local management boundary: real endpoint CLI, modeled runtime scope and service units."""

import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def value(arguments, flag):
    return arguments[arguments.index(flag) + 1] if flag in arguments else None


def aws(arguments):
    role, identity = os.environ["LOCAL_NODE_ROLE"], os.environ["LOCAL_RUN_IDENTITY"]
    prefix = "runs/" + identity + "/"
    service, operation = arguments[:2]
    key = value(arguments, "--key") or ""
    allowed = False
    if service == "s3api" and operation == "get-object":
        allowed = key.startswith(prefix + "inputs/common/") or key.startswith(prefix + "inputs/" + role + "/")
    elif service == "s3api" and operation == "put-object":
        allowed = key.startswith(prefix + "outputs/" + role + "/") or role == "sendium" and key.startswith(prefix + "inputs/harness/generated/")
    elif service == "secretsmanager" and operation == "get-secret-value":
        allowed = role == "sendium" and value(arguments, "--secret-id") == os.environ.get("LOCAL_DATABASE_SECRET_ARN")
    if not allowed:
        print("An error occurred (AccessDenied) when calling the local runtime operation", file=sys.stderr)
        return 1
    if service == "s3api" and value(arguments, "--bucket") != os.environ["LOCAL_BUCKET"]:
        return 1
    if "--region" in arguments:
        arguments[arguments.index("--region") + 1] = "us-east-1"
    return subprocess.call(["/usr/bin/aws-real", *arguments, "--endpoint-url", "http://objects:4566"])


def unit(arguments):
    name = value(arguments, "--unit")
    command = arguments.index("python3")
    maximum = next(int(item.split("=", 2)[2]) for item in arguments if item.startswith("--property=RuntimeMaxSec="))
    subprocess.Popen([sys.executable, __file__, "watch", str(maximum), *arguments[command:]], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True)
    return 0


def watch(arguments):
    maximum = int(arguments[0])
    process = subprocess.Popen(arguments[1:])
    def stop(*_):
        if process.poll() is None:
            process.terminate()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        return process.wait(timeout=maximum)
    except subprocess.TimeoutExpired:
        stop()
        try:
            return process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.kill()
            return process.wait(timeout=10)


if __name__ == "__main__":
    function = {"aws": aws, "unit": unit, "watch": watch}[sys.argv[1]]
    raise SystemExit(function(sys.argv[2:]))
