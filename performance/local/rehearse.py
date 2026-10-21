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
from scenarios import select


ROOT = Path(__file__).resolve().parents[2]


def call(arguments, **options):
    return subprocess.run(["docker", *arguments], check=True, **options)


def context():
    paths = [ROOT / "pom.xml", ROOT / "mvnw"]
    paths += list((ROOT / ".mvn").rglob("*"))
    for component in ("sendium-core", "sendium-app", "performance/harness"):
        paths.append(ROOT / component / "pom.xml")
        paths += list((ROOT / component / "src").rglob("*"))
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
        info = json.dumps({"baseCommit": head, "workingTreeDigest": fingerprint.hexdigest(), "sourceMode": "local-working-tree"}).encode()
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
    parser.add_argument("--scenarios", default="load", help="Default: HTTP/SMPP with DLR. Name no-DLR cases explicitly or select all.")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the locally built rehearsal images")
    parser.add_argument("--control-image", default="sendium-local:control", help="Controller/build-artifact image, including a selected-application comparison image")
    parser.add_argument("--resume-session", help="Wait for/copy/clean a previously detached owned rehearsal session")
    parser.add_argument("--detach", action="store_true", help="Start in Docker; use --resume-session to collect results and clean up")
    parser.add_argument("--offered-rate", type=float, help="Explicit per-run msg/s override; otherwise inherit the shared preset")
    args = parser.parse_args()
    select(args.scenarios)
    if args.offered_rate is not None and (not math.isfinite(args.offered_rate) or not 0 < args.offered_rate <= 1000000000):
        raise SystemExit("Offered rate must be finite, positive and at most 1,000,000,000")
    output = Path(args.output).resolve()
    if output.exists() or not output.parent.is_dir():
        raise SystemExit("Choose a new output directory beneath an existing parent")
    session = args.resume_session or str(int(time.time())) + "-" + secrets.token_hex(3)
    if not re.fullmatch(r"[0-9]+-[a-f0-9]{6}", session):
        raise SystemExit("Invalid local session identity")
    if not args.skip_build and not args.resume_session:
        data = context()
        for target in ("node", "control"):
            image = args.control_image if target == "control" else "sendium-local:node"
            call(["build", "--quiet", "--target", target, "-t", image, "-f", "performance/local/Dockerfile", "-"], input=data)
    name = "sendium-local-control-" + session
    volume = "sendium-local-state-" + session
    detached = False
    try:
        if args.resume_session:
            original = json.loads(call(["inspect", name], capture_output=True, text=True).stdout)[0]
            if original["Config"]["Labels"].get("sendium.local.session") != session:
                raise SystemExit("Container is not owned by this session")
            completed = int(call(["wait", name], capture_output=True, text=True).stdout.strip())
        else:
            call(["volume", "create", "--label", "sendium.local.session=" + session, volume], capture_output=True)
            command = ["create", "--name", name, "--label", "sendium.local.session=" + session,
                  "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                  "--mount", "type=volume,source=" + volume + ",target=/state",
                  "--env", "LOCAL_SESSION=" + session, "--env", "LOCAL_STATE_VOLUME=" + volume,
                  args.control_image, "--scenarios", args.scenarios]
            if args.offered_rate is not None:
                command += ["--offered-rate", str(args.offered_rate)]
            call(command, capture_output=True)
            if args.detach:
                call(["start", name], capture_output=True)
                detached = True
                print("Started local session: " + session)
                print("Collect and clean with --resume-session " + session + " --output " + str(output))
                return 0
            attached = subprocess.run(["docker", "start", "-a", name])
            state = json.loads(call(["inspect", name], capture_output=True, text=True).stdout)[0]["State"]
            completed = state["ExitCode"] if attached.returncode == 0 else attached.returncode
        output.mkdir()
        call(["cp", name + ":/state/public/.", str(output)])
        print("Local rehearsal artifacts: " + str(output))
        return completed
    finally:
        if not detached:
            cleanup = clean_session(session)
            if output.is_dir():
                (output / "session-cleanup.json").write_text(json.dumps(cleanup, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
