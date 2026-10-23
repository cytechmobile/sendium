"""Application runtime and native artifact identity; data validation only, never execution."""

import re
import struct


RUNTIMES = ("jvm", "native")
ELF_MACHINES = {"x86_64": 62, "arm64": 183}
CREDENTIAL_ENVIRONMENT = ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE",
                          "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
                          "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
                          "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", "ACTIONS_ID_TOKEN_REQUEST_TOKEN")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def runtime_mode(preset):
    mode = preset["runtime"].get("applicationRuntime", "jvm")
    return validate_runtime_mode(mode)


def validate_runtime_mode(mode):
    require(mode in RUNTIMES, "Application runtime must be jvm or native")
    return mode


def validate_native_settings(preset):
    settings = preset["runtime"].get("native")
    require(isinstance(settings, dict), "Native application settings are required")
    require(isinstance(settings.get("builderImage"), str) and re.fullmatch(
        r"quay\.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:[a-f0-9]{64}", settings["builderImage"]),
        "Native builder must be a digest-pinned UBI9 Mandrel image")
    require(settings.get("buildArguments") == ["-march=compatibility"], "Reviewed native compatibility build arguments required")
    arguments = settings.get("arguments")
    require(isinstance(arguments, list) and arguments and all(isinstance(argument, str)
            and re.fullmatch(r"-Xm[sx][1-9][0-9]*[mg]", argument) for argument in arguments),
            "Native runtime arguments must be explicit heap settings")
    maxima = [re.fullmatch(r"-Xmx([1-9][0-9]*)([mg])", argument) for argument in arguments if argument.startswith("-Xmx")]
    require(len(maxima) == 1, "Explicit native heap maximum required")
    maximum = int(maxima[0][1]) * (1024 if maxima[0][2] == "g" else 1)
    require(maximum < preset["aws"]["minimumMemoryMiB"]["sendium"], "Native heap exceeds the instance memory budget")
    minima = [re.fullmatch(r"-Xms([1-9][0-9]*)([mg])", argument) for argument in arguments if argument.startswith("-Xms")]
    require(len(minima) <= 1 and all(int(value[1]) * (1024 if value[2] == "g" else 1) <= maximum for value in minima),
            "Native initial heap exceeds its maximum")
    return settings


def validate_native_build(identity, preset, architecture):
    settings = validate_native_settings(preset)
    require(isinstance(identity, dict) and set(identity) == {
        "schemaVersion", "architecture", "builderImage", "compilerVersion", "buildArguments"}, "Native build identity fields mismatch")
    require(type(identity["schemaVersion"]) is int and identity["schemaVersion"] == 1 and identity["architecture"] == architecture
            and architecture in ELF_MACHINES, "Native build identity architecture/version mismatch")
    require(identity["builderImage"] == settings["builderImage"] and identity["buildArguments"] == settings["buildArguments"],
            "Native builder/arguments differ from the normalized request")
    compiler = identity["compilerVersion"]
    require(isinstance(compiler, str) and 0 < len(compiler.strip()) <= 8192
            and re.search(r"\bnative-image\s+25(?:[.\s+-]|$)", compiler)
            and not any(ord(character) < 32 and character not in "\n\r\t" for character in compiler),
            "Native compiler identity is unavailable or invalid")
    return identity


def verify_elf_header(header, architecture):
    require(architecture in ELF_MACHINES and len(header) >= 64 and header[:7] == b"\x7fELF\x02\x01\x01",
            "Native application must be a little-endian ELF64 executable")
    kind, machine = struct.unpack_from("<HH", header, 16)
    require(kind in (2, 3) and machine == ELF_MACHINES[architecture], "Native ELF architecture/type mismatch")
