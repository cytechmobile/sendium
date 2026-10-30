"""Package published benchmark evidence without AWS access or changing observations."""

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import re
import shutil
import tarfile

import prepare
import publish


MAX_FILES = 512
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
TOP_LEVEL = ("results.json", "summary.md", "workflow-outcome.json")


def descriptor(path, name):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    content_type = ("application/json" if name.endswith(".json") else
                    "application/x-ndjson" if name.endswith(".jsonl") else
                    "application/gzip" if name.endswith(".gz") else "text/plain; charset=utf-8")
    return {"path": name, "sizeBytes": path.stat().st_size, "sha256": digest.hexdigest(), "contentType": content_type}


def published_files(source):
    """Only public publication outputs; never include finalizer work or build inputs."""
    paths = [source / name for name in TOP_LEVEL if (source / name).exists() or (source / name).is_symlink()]
    for name in ("evidence", "diagnostics"):
        root = source / name
        prepare.require(not root.is_symlink(), "Archive evidence directory cannot be a link")
        if root.exists():
            prepare.require(root.is_dir(), "Invalid archive evidence directory")
            for path in root.rglob("*"):
                prepare.require(not path.is_symlink(), "Archive evidence cannot contain links")
                if not path.is_dir():
                    paths.append(path)
                prepare.require(len(paths) <= MAX_FILES, "Archive file count exceeds budget")
    prepare.require(len(paths) <= MAX_FILES, "Archive file count exceeds budget")
    total = 0
    for path in paths:
        prepare.require(path.is_file() and not path.is_symlink(), "Invalid archive evidence file")
        prepare.require(all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", part)
                            for part in path.relative_to(source).parts), "Invalid archive evidence path")
        size = path.stat().st_size
        total += size
        prepare.require(size <= MAX_FILE_BYTES and total <= MAX_TOTAL_BYTES, "Archive evidence exceeds byte budget")
    return sorted(paths, key=lambda path: path.relative_to(source).as_posix())


def package(request, source, output):
    source, output = Path(source), Path(output)
    prepare.require(source.is_dir() and not source.is_symlink(), "Invalid published evidence directory")
    prepare.require(not output.resolve().is_relative_to(source.resolve()), "Archive output must be outside published evidence")
    repository = request["repository"]
    prepare.require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
                    and all(part not in (".", "..") for part in repository.split("/")), "Invalid archive repository")
    match = re.fullmatch(r"gh-([1-9][0-9]*)-([1-9][0-9]*)", request["runIdentity"])
    prepare.require(match is not None, "Invalid archive run identity")
    paths = published_files(source)
    report = publish.read(source / "results.json")
    publish.validate(report, request)
    workflow_path = source / "workflow-outcome.json"
    if workflow_path.exists():
        workflow = publish.read(workflow_path)
        prepare.require(workflow.get("schemaVersion") == 1 and workflow.get("runIdentity") == request["runIdentity"],
                        "Archive workflow identity mismatch")
    output.mkdir(exist_ok=False)
    files = []
    for path in paths:
        name = path.relative_to(source).as_posix()
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        files.append(descriptor(target, name))
    # Stable archive bytes allow retries to verify an existing object by checksum.
    with (output / "evidence.tar.gz").open("xb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as bundle:
                for item in files:
                    info = tarfile.TarInfo(item["path"])
                    info.size, info.mode, info.mtime = item["sizeBytes"], 0o644, 0
                    with (output / item["path"]).open("rb") as stream:
                        bundle.addfile(info, stream)
    application = report["configuration"]["application"]
    manifest = {"schemaVersion": 1, "repository": repository, "runIdentity": request["runIdentity"],
                "runId": match[1], "runAttempt": match[2],
                "prefix": "runs/" + repository + "/" + match[1] + "/" + match[2] + "/",
                "packagedAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "provenance": report["provenance"], "resultSchemaVersion": report["schemaVersion"],
                "observationStatus": report["validity"]["status"],
                "observationAvailable": report["measurementMetadata"].get("observationAvailable", True),
                "applicationRuntime": application.get("runtime", "jvm"),
                "ingressMode": report["configuration"]["workload"]["ingressMode"],
                "missingFiles": [name for name in TOP_LEVEL if not (source / name).is_file()],
                "files": files, "bundle": descriptor(output / "evidence.tar.gz", "evidence.tar.gz")}
    if prepare.benchmark_target(request) == "kannel":
        manifest.update(benchmarkTarget="kannel", applicationSource=application["source"],
                        dlrStorage=application["dlrStorage"], messageStore=application["messageStore"])
    prepare.write(output / "manifest.json", manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        package(prepare.read(args.request), args.source, args.output)
        return 0
    except Exception:
        print("PERFORMANCE_ARCHIVE_PACKAGING_FAILED")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
