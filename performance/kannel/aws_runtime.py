"""Kannel-specific EC2 operations, executed only on the run-owned gateway node."""

import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import platform
import re
import secrets
import subprocess

from postgresql_probe import check
from smoke import configuration


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def install(node):
    tree = node.install / "application"
    metadata = json.loads((tree / "control/build-metadata.json").read_text())
    if metadata != node.document["kannelBuild"]:
        raise ValueError("Kannel build metadata differs from deployment")
    architecture = {"aarch64": "aarch64", "x86_64": "x86_64"}.get(platform.machine())
    if metadata["architecture"] != architecture:
        raise ValueError("Kannel architecture differs from gateway")
    release = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
    if release.get("ID", "").strip('"') != "amzn" or release.get("VERSION_ID", "").strip('"') != "2023":
        raise ValueError("Kannel runtime requires Amazon Linux 2023")
    manifest = json.loads((tree / "manifest.json").read_text())
    actual = {str(path.relative_to(tree)): digest(path) for path in tree.rglob("*")
              if path.is_file() and path != tree / "manifest.json"}
    if manifest != actual:
        raise ValueError("Kannel runtime manifest mismatch")
    for name in ("bearerbox", "smsbox"):
        if digest(tree / "sbin" / name) != metadata["binarySha256"][name]:
            raise ValueError("Kannel binary identity mismatch")
        subprocess.run([str(tree / "sbin" / name), "--version"], check=True, capture_output=True,
                       timeout=30, env=environment(node))


def environment(node):
    tree = node.install / "application"
    # Keep libpq/OpenSSL settings explicit; never inherit arbitrary PG* overrides.
    result = {key: value for key, value in os.environ.items()
              if not key.startswith(("AWS_", "PG", "JAVA_", "JDK_", "QUARKUS_", "SENDIUM_", "SMSG_", "LD_"))
              and key != "_JAVA_OPTIONS"}
    result.update(LD_LIBRARY_PATH=str(tree / "lib"), PGSSLMODE="verify-full",
                  PGSSLROOTCERT=node.document["deployment"]["database"]["caFile"],
                  PGCONNECT_TIMEOUT="5", PGAPPNAME="kannel-benchmark")
    return result


def configure(node, credentials):
    document = node.document
    deployment = document["deployment"]
    workload = {**document["workload"], "offeredMessagesPerSecond": float(document["workload"]["offeredMessagesPerSecond"])}
    database = {**deployment["database"], **credentials}
    if (not re.fullmatch(r"[A-Za-z0-9_]+", database["username"])
            or not re.fullmatch(r"[A-Za-z0-9]+", database["password"])
            or not re.fullmatch(r"[a-zA-Z0-9.-]+", database["host"])
            or not re.fullmatch(r"[a-zA-Z0-9_]+", database["database"])):
        raise ValueError("Database settings cannot be rendered as Kannel directives")
    ipaddress.ip_address(deployment["sendiumHost"])
    ipaddress.ip_address(deployment["harnessHost"])
    root = node.root / "generated/kannel"
    root.mkdir(parents=True)
    provider_password, http_password, admin_password = secrets.token_hex(4), secrets.token_hex(12), secrets.token_hex(12)
    config = configuration(provider_password, http_password, admin_password,
                           worker_tps=workload["workerTps"], outgoing_window=deployment["outgoingWindow"],
                           ingress_window=workload["maxOutstandingRequests"],
                           request_timeout_seconds=math.ceil(deployment["outgoingRequestTimeoutMillis"] / 1000),
                           database=database, provider_host=deployment["harnessHost"], provider_port=deployment["providerPort"],
                           http_port=deployment["httpPort"], store_location=str(root / "store"))
    (root / "kannel.conf").write_text(config)
    (root / "database.json").write_text(json.dumps(database))
    (root / "benchmark-settings.json").write_text(json.dumps({
        "schemaVersion": 1, "runIdentity": node.identity, "lifetimeSeconds": document["timeoutSeconds"],
        "intervalSeconds": document["sampleIntervalSeconds"], "maxSamples": document["sampleBudget"]}))
    # Schema initialization uses the same packaged libpq/TLS settings as the gateway.
    subprocess.run(["python3", str(Path(__file__).resolve()), "initialize", str(root / "database.json"),
                    str(node.install / "application/control/dlr.sql")], check=True, capture_output=True,
                   timeout=30, env=environment(node))
    address = "http://" + deployment["sendiumHost"] + ":" + str(deployment["httpPort"]) + "/sendsms"
    runtime = {"schemaVersion": 1, "benchmarkTarget": "kannel", "runIdentity": node.identity,
               "readinessUri": address, "gatewayReadinessFile": "gateway-ready.json",
               "provider": {"host": "0.0.0.0", "port": deployment["providerPort"], "systemId": "provider",
                            "password": provider_password, "maxConnections": 1, "windowSize": deployment["providerWindow"],
                            "maxPending": deployment["providerMaxPending"], "ioTimeoutMillis": deployment["providerIoTimeoutMillis"]},
               "ingress": {"admissionUri": address, "username": "smoke", "password": http_password,
                           "source": "Benchmark", "destination": "306900000001",
                           "callbackBase": "http://" + deployment["harnessHost"] + ":" + str(deployment["callbackPort"]) + "/dlr"
                           if workload["dlrEnabled"] else None},
               "callbackReceiver": {"host": "0.0.0.0", "port": deployment["callbackPort"], "path": "/dlr",
                                    "threads": deployment["callbackThreads"], "queueCapacity": deployment["callbackQueueCapacity"]}}
    public_config = config
    for value in (provider_password, http_password, admin_password, database["username"], database["password"]):
        public_config = public_config.replace(value, "[REDACTED]")
    public_deployment = {key: value for key, value in deployment.items() if key != "database"}
    public_deployment.update(gatewayHost=deployment["sendiumHost"], database={
        "provisioned": True, "engine": "postgresql", "sslMode": "verify-full", "poolMaxSize": database["poolMaxSize"]})
    effective = {"schemaVersion": 1, "benchmarkTarget": "kannel", "runIdentity": node.identity,
                 "preset": deployment["preset"], "workload": workload, "source": document["kannelBuild"]["source"],
                 "dlrStorage": "postgresql", "messageStore": "spool", "gatewayConfiguration": public_config,
                 "deployment": public_deployment}
    generated = node.root / "generated/harness"
    generated.mkdir()
    for name, value in (("runtime-settings.json", runtime), ("run-config.json", workload), ("effective-configuration.json", effective)):
        (generated / name).write_text(json.dumps(value, indent=2) + "\n")
    # Persist the redaction list before any processes start, including database/admin credentials.
    (root / "redactions.json").write_text(json.dumps([provider_password, http_password, admin_password,
                                                    database["username"], database["password"]]))


def command(node):
    root = node.root / "generated/kannel"
    tree = node.install / "application"
    return ["python3", str(tree / "control/gateway.py"), "--root", str(root), "--kannel", str(tree),
            "--agent", str(node.root / "sampler"), "--store", str(root / "store"),
            "--http-port", str(node.document["deployment"]["httpPort"])]


if __name__ == "__main__":
    import sys
    if len(sys.argv) != 4 or sys.argv[1] != "initialize":
        raise SystemExit("Expected initialize, private database settings and schema")
    check(json.loads(Path(sys.argv[2]).read_text()), Path(sys.argv[3]).read_text())
