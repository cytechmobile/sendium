"""One-command Docker rehearsal launcher. No AWS credentials or account are required."""

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import secrets
import re
import subprocess
import tarfile
import time
from scenarios import select, add_workload_arguments, workload_overrides


ROOT = Path(__file__).resolve().parents[2]


def call(arguments, **options):
    return subprocess.run(["docker", *arguments], check=True, **options)


def context(target="sendium"):
    paths = [ROOT / "pom.xml", ROOT / "mvnw"]
    paths += list((ROOT / ".mvn").rglob("*"))
    for component in (("performance/harness",) if target == "kannel" else ("sendium-core", "sendium-app", "performance/harness")):
        paths.append(ROOT / component / "pom.xml")
        paths += list((ROOT / component / "src").rglob("*"))
    if target == "kannel":
        for folder in ("kannel", "contracts", "presets", "resources"):
            paths += [path for path in (ROOT / "performance" / folder).glob("*")
                      if (folder == "kannel" and (path.suffix in (".py", ".java", ".md", ".sql") or path.name.startswith("Dockerfile")))
                      or (folder == "contracts" and (path.suffix in (".py", ".md") or path.name.endswith(".schema.json") or path.name == "requirements-dev.txt"))
                      or (folder == "presets" and path.name == "aws-private-jvm-v1.json")
                      or (folder == "resources" and path.name == "sample_process.py")]
        paths.append(ROOT / "performance/local/scenarios.py")
        paths.append(ROOT / "performance/local/tls.py")
    else:
        for folder in ("local", "contracts", "presets", "deployment", "infrastructure", "resources", "workflow"):
            paths += [path for path in (ROOT / "performance" / folder).rglob("*") if "__pycache__" not in path.parts]
    if any(path.is_symlink() for path in paths):
        raise SystemExit("Local build context must not contain symlinks")
    paths = sorted({path for path in paths if path.is_file() and "target" not in path.relative_to(ROOT).parts})
    fingerprint = hashlib.sha256()
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w") as bundle:
        for path in paths:
            name, content = path.relative_to(ROOT).as_posix(), path.read_bytes()
            fingerprint.update(name.encode() + b"\0" + content)
            entry = tarfile.TarInfo(name)
            entry.size, entry.mode = len(content), 0o644
            bundle.addfile(entry, io.BytesIO(content))
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
        source = {"baseCommit": head, "workingTreeDigest": fingerprint.hexdigest(), "sourceMode": "local-working-tree"}
        if target == "kannel":
            source["branch"] = subprocess.run(["git", "branch", "--show-current"], cwd=ROOT, check=True,
                                              capture_output=True, text=True).stdout.strip() or "detached"
        info = json.dumps(source).encode()
        entry = tarfile.TarInfo("source-context.json")
        entry.size, entry.mode = len(info), 0o644
        bundle.addfile(entry, io.BytesIO(info))
    return archive.getvalue()


def clean_session(session):
    summary = {"schemaVersion": 1, "session": session, "removed": {}, "remaining": {}}
    for kind, listing, removal in (("container", ["ps", "-aq"], ["rm", "-f", "-v"]),
                                   ("network", ["network", "ls", "-q"], ["network", "rm"]),
                                   ("volume", ["volume", "ls", "-q"], ["volume", "rm"])):
        result = call([*listing, "--filter", "label=sendium.local.session=" + session], capture_output=True, text=True)
        summary["removed"][kind] = len(result.stdout.split())
        for identifier in result.stdout.split():
            call([*removal, identifier], capture_output=True)
        remaining = call([*listing, "--filter", "label=sendium.local.session=" + session], capture_output=True, text=True)
        summary["remaining"][kind] = len(remaining.stdout.split())
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="local-rehearsal-results")
    parser.add_argument("--benchmark-target", choices=("sendium", "kannel"), default="sendium")
    parser.add_argument("--scenarios", default="load", help="Default: HTTP/SMPP with DLR. Name no-DLR cases explicitly or select all.")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the locally built rehearsal images")
    parser.add_argument("--control-image", help="Controller/build-artifact image; defaults to the selected target's local image")
    parser.add_argument("--resume-session", help="Wait for/copy/clean a previously detached owned rehearsal session")
    parser.add_argument("--detach", action="store_true", help="Start in Docker; use --resume-session to collect results and clean up")
    parser.add_argument("--offered-rate", type=float, help="Explicit per-run msg/s override; otherwise inherit the shared preset")
    add_workload_arguments(parser)
    args = parser.parse_args()
    try:
        select(args.scenarios, args.benchmark_target)
        preset = json.loads((ROOT / "performance/presets/aws-private-jvm-v1.json").read_text(encoding="utf-8"))
        overrides = workload_overrides(args, preset)
    except ValueError as error:
        parser.error(str(error))
    args.control_image = args.control_image or ("sendium-local:kannel-control" if args.benchmark_target == "kannel" else "sendium-local:control")
    if args.offered_rate is not None and (not math.isfinite(args.offered_rate) or not 0 < args.offered_rate <= 1000000000):
        raise SystemExit("Offered rate must be finite, positive and at most 1,000,000,000")
    output = Path(args.output).resolve()
    if output.exists() or not output.parent.is_dir():
        raise SystemExit("Choose a new output directory beneath an existing parent")
    session = args.resume_session or str(int(time.time())) + "-" + secrets.token_hex(3)
    if not re.fullmatch(r"[0-9]+-[a-f0-9]{6}", session):
        raise SystemExit("Invalid local session identity")
    if not args.skip_build and not args.resume_session:
        data = context(args.benchmark_target)
        if args.benchmark_target == "kannel":
            architecture = call(["info", "--format", "{{.Architecture}}"], capture_output=True, text=True).stdout.strip()
            platform = {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(architecture)
            if platform is None:
                raise ValueError("Unsupported Docker architecture")
            base = "sendium-kannel-baseline:" + platform
            call(["build", "--quiet", "--platform", "linux/" + platform, "-t", base,
                  "-f", "performance/kannel/Dockerfile.baseline", "performance/kannel"], cwd=ROOT)
            for target, image in (("benchmark-harness", "sendium-local:kannel-harness"), ("gateway", "sendium-local:kannel-gateway"), ("control", args.control_image)):
                call(["build", "--quiet", "--platform", "linux/" + platform, "--target", target, "--build-arg", "KANNEL_BASE_IMAGE=" + base,
                      "-t", image, "-f", "performance/kannel/Dockerfile.local", "-"], input=data)
        else:
            for target in ("node", "control"):
                image = args.control_image if target == "control" else "sendium-local:node"
                call(["build", "--quiet", "--target", target, "-t", image, "-f", "performance/local/Dockerfile", "-"], input=data)
    name = "sendium-local-control-" + session
    volume = "sendium-local-state-" + session
    detached = False
    owned = False
    try:
        if args.resume_session:
            original = json.loads(call(["inspect", name], capture_output=True, text=True).stdout)[0]
            if original["Config"]["Labels"].get("sendium.local.session") != session:
                raise SystemExit("Container is not owned by this session")
            if original["Config"]["Labels"].get("sendium.benchmark.target", "sendium") != args.benchmark_target:
                raise SystemExit("Resume with the original benchmark target")
            owned = True
            completed = int(call(["wait", name], capture_output=True, text=True).stdout.strip())
        else:
            owned = True
            call(["volume", "create", "--label", "sendium.local.session=" + session, volume], capture_output=True)
            command = ["create", "--name", name, "--label", "sendium.local.session=" + session,
                  "--label", "sendium.benchmark.target=" + args.benchmark_target,
                  "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                  "--mount", "type=volume,source=" + volume + ",target=/state",
                  "--env", "LOCAL_SESSION=" + session, "--env", "LOCAL_STATE_VOLUME=" + volume,
                  ]
            if args.benchmark_target == "kannel":
                for role in ("gateway", "harness"):
                    image = call(["image", "inspect", "--format", "{{.Id}}", "sendium-local:kannel-" + role], capture_output=True, text=True).stdout.strip()
                    command += ["--env", "KANNEL_" + role.upper() + "_IMAGE=" + image]
                control_id = call(["image", "inspect", "--format", "{{.Id}}", args.control_image], capture_output=True, text=True).stdout.strip()
                command += ["--env", "KANNEL_CONTROL_IMAGE=" + control_id]
                database = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", "postgres:17-alpine"],
                                          capture_output=True, text=True)
                if database.returncode:
                    call(["pull", "postgres:17-alpine"], capture_output=True)
                    database = call(["image", "inspect", "--format", "{{.Id}}", "postgres:17-alpine"], capture_output=True, text=True)
                command += ["--env", "KANNEL_DATABASE_IMAGE=" + database.stdout.strip()]
            else:
                control_id = args.control_image
            command += [control_id, "--scenarios", args.scenarios]
            if args.benchmark_target == "kannel":
                command += ["--dlr-storage", "postgresql"]
            if args.offered_rate is not None:
                command += ["--offered-rate", str(args.offered_rate)]
            for key, value in overrides.items():
                command += ["--" + key.replace("_", "-"), str(value)]
            call(command, capture_output=True)
            if args.detach:
                call(["start", name], capture_output=True)
                detached = True
                print("Started local session: " + session)
                print("Collect and clean with --benchmark-target " + args.benchmark_target + " --resume-session " + session + " --output " + str(output))
                return 0
            attached = subprocess.run(["docker", "start", "-a", name])
            state = json.loads(call(["inspect", name], capture_output=True, text=True).stdout)[0]["State"]
            completed = state["ExitCode"] if attached.returncode == 0 else attached.returncode
        output.mkdir()
        call(["cp", name + ":/state/public/.", str(output)])
        print("Local rehearsal artifacts: " + str(output))
        return completed
    except KeyboardInterrupt:
        if owned:
            call(["stop", "--time", "90", name], capture_output=True, timeout=100)
            output.mkdir(exist_ok=True)
            call(["cp", name + ":/state/public/.", str(output)])
        return 130
    finally:
        if not detached and owned:
            cleanup = clean_session(session)
            if output.is_dir():
                (output / "session-cleanup.json").write_text(json.dumps(cleanup, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
