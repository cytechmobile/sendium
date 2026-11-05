"""Build the selected application without cloud credentials; native compilation uses a pinned Linux builder."""

import argparse
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

import prepare
from application_runtime import CREDENTIAL_ENVIRONMENT, runtime_mode, validate_native_build, validate_native_settings, verify_elf_header


PLATFORMS = {"x86_64": "linux/amd64", "arm64": "linux/arm64"}


def build(request, checkout, architecture, java_version, machine=None, system=None):
    prepare.require(not any(os.environ.get(key) for key in CREDENTIAL_ENVIRONMENT), "Application build requires an unprivileged environment")
    prepare.require(architecture in PLATFORMS and prepare.machine_architecture(machine) == architecture,
                    "Application build runner architecture mismatch")
    prepare.require((system or platform.system()) == "Linux", "Application artifacts require a Linux build runner")
    prepare.require(isinstance(java_version, str) and re.fullmatch(r"25(?:[.+-].*)?", java_version), "Application build requires Java 25")
    checkout = Path(checkout).resolve()
    observed = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], check=True,
                              capture_output=True, text=True, timeout=30).stdout.strip()
    prepare.require(observed == prepare.revision(request, "application"), "Application build checkout revision mismatch")
    if prepare.benchmark_target(request) == "kannel":
        image = "sendium-kannel-baseline:" + architecture
        subprocess.run(["docker", "build", "--platform", PLATFORMS[architecture], "--file", "performance/kannel/Dockerfile.baseline",
                        "--tag", image, "performance/kannel"], cwd=checkout, check=True, timeout=2400)
        # Verify dependency closure on minimal AL2023 before exporting transport bytes.
        subprocess.run(["docker", "build", "--platform", PLATFORMS[architecture], "--file", "performance/kannel/Dockerfile.aws",
                        "--build-arg", "KANNEL_BASE_IMAGE=" + image, "--target", "verification", "."],
                       cwd=checkout, check=True, timeout=600)
        subprocess.run(["docker", "build", "--platform", PLATFORMS[architecture], "--file", "performance/kannel/Dockerfile.aws",
                        "--build-arg", "KANNEL_BASE_IMAGE=" + image, "--target", "export",
                        "--output", "type=local,dest=performance/kannel/target/aws-runtime", "."], cwd=checkout, check=True, timeout=600)
        identity = prepare.read(checkout / "performance/kannel/target/aws-runtime/build-metadata.json")
        prepare.validate_kannel_build(identity, architecture)
        return identity
    mode = runtime_mode(request["preset"])
    command = ["bash", "./mvnw", "-B", "-ntp", "-DskipTests", "-pl", "sendium-app", "-am"]
    identity = None
    if mode == "native":
        settings = validate_native_settings(request["preset"])
        image = settings["builderImage"]
        target_platform = PLATFORMS[architecture]
        subprocess.run(["docker", "pull", "--quiet", "--platform", target_platform, image], check=True, timeout=300)
        version = subprocess.run(["docker", "run", "--rm", "--pull=never", "--network", "none", "--platform", target_platform,
                                  "--entrypoint", "native-image", image, "--version"], check=True,
                                 capture_output=True, text=True, timeout=60)
        identity = {"schemaVersion": 1, "architecture": architecture, "builderImage": image,
                    "compilerVersion": (version.stdout + version.stderr).strip(), "buildArguments": settings["buildArguments"]}
        validate_native_build(identity, request["preset"], architecture)
        command += ["-Pnative", "-DskipITs", "-Dquarkus.native.container-build=true",
                    "-Dquarkus.native.remote-container-build=true",
                    "-Dquarkus.native.builder-image=" + image,
                    "-Dquarkus.native.additional-build-args=" + ",".join(settings["buildArguments"]), "clean", "package"]
    else:
        command += ["package"]
    subprocess.run(command, cwd=checkout, check=True, timeout=3000)
    if identity is not None:
        target = checkout / "sendium-app/target"
        prepare.require(target.is_dir() and not target.is_symlink(), "Native build target directory is unavailable")
        binaries = list(target.glob("*-runner"))
        prepare.require(len(binaries) == 1 and binaries[0].is_file() and not binaries[0].is_symlink(), "Expected one compiled native application")
        with binaries[0].open("rb") as stream:
            verify_elf_header(stream.read(64), architecture)
        prepare.write(target / "performance-native-build.json", identity)
    return identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True)
    parser.add_argument("--checkout", required=True)
    parser.add_argument("--architecture", choices=sorted(PLATFORMS), required=True)
    parser.add_argument("--java-version", required=True)
    args = parser.parse_args()
    try:
        build(prepare.read(args.request), args.checkout, args.architecture, args.java_version)
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("PERFORMANCE_APPLICATION_BUILD_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
