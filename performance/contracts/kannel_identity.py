"""Reviewed Kannel archive identity shared by preparation and deployment."""

import re

SOURCE = {
    "version": "1.4.5",
    "archiveSha256": "f279606ea8317fd2c8fa6b0f591c084fdfa954612f1e7c9cb667a5f6b77387f3",
    "patchArchiveSha256": "3d8d54e272699a5226368738ee0246c8873d152a63220f978ac149285895f5be",
    "patches": ["Update-bison-source-for-newer-version.patch", "Fix-multiple-conflicting-defs-of-dispatch_to_wdp-wsp-push.patch"],
}


def validate_build(build, architecture=None):
    if (not isinstance(build, dict) or build.get("schemaVersion") != 1 or build.get("role") != "gateway"
            or build.get("postgresqlSupport") is not True):
        raise ValueError("PostgreSQL-enabled Kannel build identity required")
    source = build.get("source", {})
    if any(source.get(key) != value for key, value in SOURCE.items()):
        raise ValueError("Kannel source identity differs from the reviewed baseline")
    if build.get("architecture") not in ("aarch64", "x86_64"):
        raise ValueError("Unsupported Kannel build architecture")
    if architecture is not None and build["architecture"] != {"arm64": "aarch64", "x86_64": "x86_64"}.get(architecture):
        raise ValueError("Kannel build architecture differs from gateway")
    if any(not re.fullmatch(r"[a-f0-9]{64}", build.get("binarySha256", {}).get(name, "")) for name in ("bearerbox", "smsbox")):
        raise ValueError("Kannel binary digest required")
