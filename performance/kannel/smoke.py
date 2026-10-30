"""Run a local, startup-only Kannel check against the actual harness simulator."""

import argparse
import json
import secrets
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def docker(*arguments, timeout=30):
    result = subprocess.run(["docker", *arguments], cwd=ROOT, text=True,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        # Docker command errors can contain generated configuration; keep public
        # diagnostics bounded and never forward arbitrary error text.
        raise RuntimeError(f"Docker {arguments[0]} failed (exit {result.returncode})")
    return result.stdout.strip()


def build(*arguments):
    subprocess.run(["docker", "build", *arguments], cwd=ROOT, check=True, timeout=1800)


def configuration(provider_password, http_password, admin_password, *, worker_tps=0, outgoing_window=1000,
                  ingress_window=1000, request_timeout_seconds=30, database=None,
                  provider_host="provider", provider_port=27779, http_port=8080, store_location="/tmp/kannel-store"):
    value = f"""group = core
admin-port = 13000
admin-password = {admin_password}
admin-deny-ip = "*.*.*.*"
admin-allow-ip = "127.0.0.1"
smsbox-port = 13001
smsbox-max-pending = {ingress_window}
box-deny-ip = "*.*.*.*"
box-allow-ip = "127.0.0.1"
dlr-storage = {'pgsql' if database else 'internal'}
store-type = spool
store-location = {store_location}
log-level = 2

group = smsc
smsc = smpp
smsc-id = simulated-provider
host = {provider_host}
port = {provider_port}
smsc-username = provider
smsc-password = {provider_password}
system-type = smpp
interface-version = 34
transceiver-mode = true
max-pending-submits = {outgoing_window}
throughput = {worker_tps}
wait-ack = {request_timeout_seconds}

group = smsbox
smsbox-id = benchmark
bearerbox-host = 127.0.0.1
sendsms-port = {http_port}
sendsms-url = /sendsms
immediate-sendsms-reply = false
log-level = 2

group = sendsms-user
username = smoke
password = {http_password}
max-messages = 1
concatenation = false
"""
    if database:
        value += f"""
group = pgsql-connection
id = benchmark-dlr
host = {database['host']}
port = {database['port']}
username = {database['username']}
password = {database['password']}
database = {database['database']}
max-connections = {database['poolMaxSize']}

group = dlr-db
id = benchmark-dlr
table = dlr
field-smsc = smsc
field-timestamp = ts
field-destination = destination
field-source = source
field-service = service
field-url = url
field-mask = mask
field-status = status
field-boxc-id = boxc
"""
    return value


def wait_ready(directory, containers, markers, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for container in containers:
            if docker("inspect", "--format", "{{.State.Running}}", container) != "true":
                raise RuntimeError("A local smoke container exited before readiness")
        if all((directory / marker).is_file() for marker in markers):
            return
        time.sleep(0.2)
    raise RuntimeError("Local Kannel readiness timed out")


def inspect_image(image):
    data = json.loads(docker("image", "inspect", image))[0]
    return {"id": data["Id"], "platform": data["Os"] + "/" + data["Architecture"]}


def run_smoke(output, *, skip_build=False, timeout=120, traffic=False):
    output = Path(output).resolve()
    if not output.parent.is_dir():
        raise ValueError("Output parent must already exist")
    output.mkdir()  # Never overwrite earlier evidence.
    identity = "kannel-smoke-" + uuid.uuid4().hex[:12]
    network = identity + "-network"
    provider = identity + "-provider"
    gateway = identity + "-gateway"
    candidates = []
    network_attempted = False
    # Kannel enforces SMPP's eight-character password limit on the wire.
    passwords = [secrets.token_hex(4), secrets.token_hex(12), secrets.token_hex(12)]
    outcome = {"schemaVersion": 1,
               "scope": "single-part-http-dlr-correctness" if traffic else "startup-and-bind-only", "status": "failed",
               "runIdentity": identity, "target": "kannel", "ingressMode": "http",
               "dlrStorage": "internal", "messageStore": "spool",
               "images": {}, "issues": [], "cleanupIssues": [], "buildSkipped": skip_build}
    try:
        architecture = docker("info", "--format", "{{.Architecture}}")
        platform = {"x86_64": "amd64", "amd64": "amd64",
                    "aarch64": "arm64", "arm64": "arm64"}.get(architecture)
        if platform is None:
            raise RuntimeError("Unsupported Docker engine architecture")
        base = "sendium-kannel-baseline:" + platform
        gateway_image = "sendium-local:kannel-gateway"
        provider_image = "sendium-local:kannel-provider"
        if not skip_build:
            build("--platform", "linux/" + platform, "--file",
                  "performance/kannel/Dockerfile.baseline", "--tag", base, "performance/kannel")
            for target, image in (("provider", provider_image), ("gateway", gateway_image)):
                build("--platform", "linux/" + platform, "--file", "performance/kannel/Dockerfile.local",
                      "--build-arg", "KANNEL_BASE_IMAGE=" + base, "--target", target, "--tag", image, ".")
        for role, image in (("gateway", gateway_image), ("provider", provider_image)):
            outcome["images"][role] = inspect_image(image)
            if outcome["images"][role]["platform"] != "linux/" + platform:
                raise RuntimeError("Smoke image does not match the Docker engine architecture")
        with tempfile.TemporaryDirectory(prefix=identity + "-") as temporary:
            directory = Path(temporary)
            (directory / "provider-password").write_text(passwords[0], encoding="utf-8")
            (directory / "kannel.conf").write_text(configuration(*passwords), encoding="utf-8")
            if traffic:
                (directory / "http-password").write_text(passwords[1], encoding="utf-8")
                (directory / "traffic-enabled").touch()
            network_attempted = True
            docker("network", "create", "--internal", "--label", "sendium.kannel.smoke=" + identity, network)
            try:
                for name, alias in ((provider, "provider"), (gateway, "gateway")):
                    candidates.append(name)
                    docker("run", "--detach", "--name", name, "--hostname", alias,
                           "--network", network, "--network-alias", alias,
                           "--label", "sendium.kannel.smoke=" + identity,
                           "--cpus", "2", "--memory", "2g",
                           "--log-driver", "json-file", "--log-opt", "max-size=1m",
                           "--log-opt", "max-file=1",
                           "--mount", f"type=bind,source={directory},target=/probe",
                           outcome["images"][alias]["id"])
                    markers = ["provider-listening"] if name == provider else ["gateway-ready", "provider-bound"]
                    wait_ready(directory, candidates, markers, timeout)
                # Require a live, stable connection rather than just a bind latch.
                time.sleep(1)
                wait_ready(directory, candidates, ["gateway-ready", "provider-bound"], timeout)
                outcome["readiness"] = {"smsboxHttp": True, "authenticatedSmppTransceiverBind": True}
                if traffic:
                    wait_ready(directory, candidates, ["traffic-result.json"], timeout)
                    result = json.loads((directory / "traffic-result.json").read_text(encoding="utf-8"))
                    if result.get("status") != "passed":
                        outcome["issues"].append("Message/DLR correctness check failed")
                outcome["status"] = "passed"
            finally:
                if traffic and (directory / "traffic-result.json").is_file():
                    try:
                        result = json.loads((directory / "traffic-result.json").read_text(encoding="utf-8"))
                        (output / "traffic-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
                    except (OSError, ValueError):
                        outcome["issues"].append("Could not preserve traffic correctness evidence")
                # Stop the provider while the gateway session is still connected;
                # graceful probe exit verifies its final problem checks.
                (directory / "stop-provider").touch()
                if provider in candidates:
                    try:
                        exit_code = docker("wait", provider, timeout=20)
                        if exit_code != "0":
                            outcome["issues"].append("Provider probe exited unsuccessfully")
                    except (RuntimeError, OSError, subprocess.SubprocessError):
                        outcome["issues"].append("Provider probe did not stop within its shutdown budget")
                # Containers must release the private mount before TemporaryDirectory closes.
                collect_and_remove(candidates, output, passwords, outcome)
                if gateway not in candidates and (directory / "kannel-resources.jsonl").is_file():
                    try:
                        samples = directory / "kannel-resources.jsonl"
                        if not 0 < samples.stat().st_size <= 256 * 8192:
                            raise ValueError("Invalid smoke sample budget")
                        (output / "kannel-resources.jsonl").write_bytes(samples.read_bytes())
                        outcome["gatewaySampling"] = {"source": "kannel-resources.jsonl", "members": ["bearerbox", "smsbox"],
                                                      "cpu": "sum of member CPU", "rss": "sum of member RSS; shared pages may repeat"}
                    except (OSError, ValueError):
                        outcome["issues"].append("Could not preserve gateway resource evidence")
                elif outcome["status"] == "passed":
                    outcome["issues"].append("Gateway resource evidence is missing")
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as error:
        outcome["issues"].append(type(error).__name__ if isinstance(error, subprocess.SubprocessError) else str(error))
    finally:
        # Also cover a timeout/failure during container creation or early cleanup.
        collect_and_remove(candidates, output, passwords, outcome)
        if network_attempted:
            try:
                docker("network", "rm", network)
            except (RuntimeError, OSError, subprocess.SubprocessError):
                outcome["cleanupIssues"].append("Could not remove smoke network")
        if outcome["issues"] or outcome["cleanupIssues"]:
            outcome["status"] = "failed"
        (output / "outcome.json").write_text(json.dumps(outcome, indent=2) + "\n", encoding="utf-8")
    return outcome


def collect_and_remove(candidates, output, passwords, outcome, roles=None):
    for name in list(reversed(candidates)):
        try:
            exists = bool(docker("ps", "--all", "--filter", "name=^/" + name + "$",
                                 "--format", "{{.Names}}"))
            if exists:
                docker("stop", "--time", "15", name, timeout=20)
                if name.endswith("-gateway") and docker("inspect", "--format", "{{.State.ExitCode}}", name) != "0":
                    outcome["issues"].append("Gateway supervision or resource sampling exited unsuccessfully")
                result = subprocess.run(["docker", "logs", "--tail", "200", name],
                                        text=True, capture_output=True, timeout=10)
                if result.returncode:
                    outcome["cleanupIssues"].append("Could not retrieve smoke container logs")
                logs = result.stdout + result.stderr
                for password in passwords:
                    logs = logs.replace(password, "[REDACTED]")
                role = (roles or {}).get(name, "provider" if name.endswith("-provider") else "gateway")
                (output / (role + ".log")).write_text(logs, encoding="utf-8")
                docker("rm", "--force", name, timeout=20)
            candidates.remove(name)
        except (RuntimeError, OSError, subprocess.SubprocessError):
            outcome["cleanupIssues"].append("Could not collect/remove smoke container")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New result directory beneath an existing parent")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the existing local smoke images")
    parser.add_argument("--traffic", action="store_true", help="Verify ten HTTP submissions and their delivery reports")
    arguments = parser.parse_args()
    try:
        outcome = run_smoke(arguments.output, skip_build=arguments.skip_build, traffic=arguments.traffic)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(f"Kannel {outcome['scope']}: {outcome['status']}; evidence: {arguments.output / 'outcome.json'}")
    return 0 if outcome["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
