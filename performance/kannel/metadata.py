"""Capture immutable build identities inside images; show only public metadata."""

import hashlib
import json
import platform
import re
import subprocess
import sys
from pathlib import Path

OUTPUT = Path("/opt/probe/build-metadata.json")


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def version(*command, cwd=None):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=True, timeout=15)
    text = (result.stdout + result.stderr).strip()
    if len(text) > 16384:
        raise ValueError("Version evidence exceeds its budget")
    return text


def build(role):
    result = {"schemaVersion": 1, "role": role, "architecture": platform.machine(),
              "samplerSha256": digest("/opt/probe/sample_process.py")}
    if role == "harness":
        result.update(sourceContext=json.loads(Path("/opt/probe/source-context.json").read_text()),
                      harnessSha256=digest("/opt/probe/harness.jar"), javaVersion=version("java", "--version"))
    else:
        source = Path("/opt/kannel-source")
        reported = version("/opt/kannel/sbin/bearerbox", "--version")
        match = re.search(r"Kannel bearerbox version `([^']+)'", reported)
        if match is None:
            raise ValueError("Missing Kannel version identity")
        patches = (source / "applied-patches.txt").read_text().splitlines()
        result.update(source={"version": match[1],
                              "archiveSha256": (source / "source-archive.sha256").read_text().split()[0],
                              "patchArchiveSha256": (source / "patch-archive.sha256").read_text().split()[0],
                              "patches": patches,
                              "patchDigests": {name: digest(source / "debian/patches" / name) for name in patches}},
                      binarySha256={name: digest("/opt/kannel/sbin/" + name) for name in ("bearerbox", "smsbox")},
                      supervisorSha256=digest("/opt/probe/gateway.py"),
                      postgresqlProbeSha256=digest("/opt/probe/postgresql_probe.py"),
                      compilerVersion=version("gcc", "--version").splitlines()[0],
                      configureArguments=version("./config.status", "--config", cwd=source),
                      postgresqlSupport=bool(re.search(r"^#define HAVE_PGSQL 1$", (source / "gw-config.h").read_text(), re.MULTILINE)),
                      postgresqlClientVersion=version("pg_config", "--version"),
                      packageInventorySha256=digest("/opt/kannel/build-packages.txt"),
                      osRelease=Path("/etc/os-release").read_text())
    OUTPUT.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if sys.argv[1:] == ["show"]:
        print(OUTPUT.read_text(encoding="utf-8"))
    elif len(sys.argv) == 2 and sys.argv[1] in ("harness", "gateway"):
        build(sys.argv[1])
    else:
        raise SystemExit("Expected harness, gateway, or show")
