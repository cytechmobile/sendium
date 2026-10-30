"""Package the AL2023 Kannel runtime and its non-glibc shared dependencies."""

import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

from metadata import digest


def package(destination):
    destination = Path(destination)
    destination.mkdir()
    tree = destination / "runtime"
    shutil.copytree("/opt/kannel", tree)
    control = tree / "control"
    shutil.copytree("/opt/probe", control)
    libraries = tree / "lib"
    libraries.mkdir(exist_ok=True)
    glibc = {"libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1", "libresolv.so.2"}
    for executable in (tree / "sbin/bearerbox", tree / "sbin/smsbox"):
        linked = subprocess.run(["ldd", str(executable)], capture_output=True, text=True, check=True, timeout=30).stdout
        if "not found" in linked:
            raise ValueError("Unresolved runtime dependency")
        for filename in re.findall(r"=> (/\S+) \(", linked):
            path = Path(filename)
            if path.name in glibc:
                continue  # Use the AL2023 node's matched glibc/loader pair.
            target = libraries / path.name
            if target.exists() and digest(target) != digest(path):
                raise ValueError("Runtime library basename collision")
            shutil.copyfile(path, target)
    shutil.copytree("/usr/share/licenses", tree / "share/dependency-licenses", dirs_exist_ok=True)
    metadata = json.loads((control / "build-metadata.json").read_text())
    files = {str(path.relative_to(tree)): digest(path) for path in sorted(tree.rglob("*")) if path.is_file()}
    (tree / "manifest.json").write_text(json.dumps(files, indent=2) + "\n")
    with tarfile.open(destination / "kannel.tar.gz", "w:gz") as archive:
        for path in sorted(tree.iterdir()):
            archive.add(path, arcname=path.name)
    (destination / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    shutil.rmtree(tree)


if __name__ == "__main__":
    package("/export")
