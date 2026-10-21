"""Build an exact application Git revision while preserving a comparison image's harness/runtime configuration."""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile

from rehearse import ROOT, call


DOCKERFILE = b"""ARG BASE_IMAGE
FROM eclipse-temurin:25-jdk AS application
WORKDIR /src
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY sendium-core sendium-core
COPY sendium-app sendium-app
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests -pl sendium-app -am package
FROM ${BASE_IMAGE}
RUN rm -rf /opt/build/quarkus-app
COPY --from=application /src/sendium-app/target/quarkus-app /opt/build/quarkus-app
COPY local/lab.py /workspace/performance/local/lab.py
COPY source-context.json /opt/build/source-context.json
"""

INSPECT = """import hashlib,json,pathlib
root=pathlib.Path('/opt/build')
jars=list((root/'harness').glob('*.jar'))
assert len(jars)==1, 'Expected one comparison harness'
print(json.dumps({'source':json.loads((root/'source-context.json').read_text()),
                 'harnessSha256':hashlib.sha256(jars[0].read_bytes()).hexdigest()}))
"""


def application_context(archive_bytes, source, overlay):
    output = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as application, tarfile.open(fileobj=output, mode="w") as context:
        for entry in application:
            name = PurePosixPath(entry.name)
            if name.is_absolute() or ".." in name.parts or not name.parts:
                raise ValueError("Invalid application archive path")
            if entry.isdir():
                continue
            if not entry.isfile() or name.parts[0] not in ("pom.xml", "mvnw", ".mvn", "sendium-core", "sendium-app"):
                raise ValueError("Application archive contains an unexpected file or link")
            # Do not carry git archive's global PAX comment into the Docker stdin header.
            copied = tarfile.TarInfo(entry.name)
            copied.size, copied.mode = entry.size, entry.mode
            context.addfile(copied, application.extractfile(entry))
        for name, content in (("Dockerfile", DOCKERFILE), ("local/lab.py", overlay),
                              ("source-context.json", json.dumps(source).encode())):
            entry = tarfile.TarInfo(name)
            entry.size, entry.mode = len(content), 0o644
            context.addfile(entry, io.BytesIO(content))
    return output.getvalue()


def inspect(image):
    return json.loads(call(["run", "--rm", "--network", "none", "--entrypoint", "python", image, "-c", INSPECT],
                           capture_output=True, text=True).stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("application_ref")
    parser.add_argument("--base-image", default="sendium-local:control")
    parser.add_argument("--output-image", default="sendium-local:control")
    parser.add_argument("--expected-harness-sha256", help="Require the retained comparison harness checksum before building")
    args = parser.parse_args()
    def git(*arguments):
        return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True)
    revision = git("rev-parse", "--verify", "--end-of-options", args.application_ref + "^{commit}").stdout.decode().strip()
    if not re.fullmatch("[0-9a-f]{40}", revision):
        raise ValueError("Expected an exact application commit")
    base = call(["image", "inspect", args.base_image, "--format", "{{.Id}}"], capture_output=True, text=True).stdout.strip()
    original = inspect(base)
    if args.expected_harness_sha256 and original["harnessSha256"] != args.expected_harness_sha256:
        raise ValueError("Base image does not contain the retained baseline harness")
    archive = git("archive", "--format=tar", revision, "--", "pom.xml", "mvnw", ".mvn", "sendium-core", "sendium-app").stdout
    overlay = (Path(__file__).resolve().parent / "lab.py").read_bytes()
    source = dict(original["source"])
    source["baselineWorkingTreeDigest"] = source.pop("workingTreeDigest", None)
    symbolic = git("rev-parse", "--symbolic-full-name", "--verify", "--end-of-options", args.application_ref).stdout.decode().strip()
    source.update(applicationCommit=revision, applicationRef=symbolic if symbolic.startswith("refs/heads/") else "refs/heads/local-rehearsal",
                  requestedApplicationRef=args.application_ref,
                  sourceMode="local-application-git-revision", comparisonBaseImage=base,
                  applicationArchiveSha256=hashlib.sha256(archive).hexdigest(),
                  controllerOverlaySha256=hashlib.sha256(overlay).hexdigest(), harnessSha256=original["harnessSha256"])
    source["workingTreeDigest"] = hashlib.sha256(archive + overlay + DOCKERFILE + base.encode()).hexdigest()
    temporary_tag = "sendium-local:application-base-" + base.removeprefix("sha256:")[:12]
    call(["image", "tag", base, temporary_tag])
    try:
        call(["build", "--quiet", "-f", "Dockerfile", "--build-arg", "BASE_IMAGE=" + temporary_tag, "-t", args.output_image, "-"],
             input=application_context(archive, source, overlay))
        result = inspect(args.output_image)
        if result["harnessSha256"] != original["harnessSha256"] or result["source"]["applicationCommit"] != revision:
            raise ValueError("Comparison image did not preserve the expected application/harness identity")
        print(json.dumps(result, indent=2))
    finally:
        call(["image", "rm", temporary_tag], capture_output=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
