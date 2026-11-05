"""Fail-closed final export check for known ephemeral fixture credentials."""

from pathlib import Path


def redact_known(directory, values):
    changed = []
    secrets = sorted({value.encode() for value in values if value}, key=len, reverse=True)
    for path in Path(directory).rglob("*"):
        if path.is_file():
            original = path.read_bytes()
            redacted = original
            for value in secrets:
                redacted = redacted.replace(value, b"[REDACTED]")
            if redacted != original:
                path.write_bytes(redacted)
                changed.append(path.relative_to(directory).as_posix())
    return changed
