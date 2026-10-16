"""Build an immutable, dependency-light Lambda ZIP for bootstrap installation."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import zipfile


def package(directory):
    directory = Path(directory)
    directory.mkdir(exist_ok=False)
    target = directory / "expiry.zip"
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in ("aws_cli.py", "cleanup.py", "expiry.py"):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100644 << 16  # Readable by Lambda's unprivileged runtime user.
            archive.writestr(entry, Path(__file__).with_name(name).read_bytes())
    checksum = hashlib.sha256(target.read_bytes()).digest()
    hex_digest = checksum.hex()
    target = target.rename(directory / (hex_digest + ".zip"))
    manifest = {"schemaVersion": 1, "file": target.name, "sha256": hex_digest,
                "codeSha256": base64.b64encode(checksum).decode("ascii"),
                "s3Key": "bootstrap/expiry/" + hex_digest + ".zip"}
    (directory / "expiry-package.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New directory beneath an existing parent")
    args = parser.parse_args()
    package(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
