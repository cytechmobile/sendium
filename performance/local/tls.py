"""Generate ephemeral local database PKI and password files, never public artifacts."""

import os
from pathlib import Path
import secrets
import subprocess
import sys


def openssl(*arguments):
    subprocess.run(["openssl", *arguments], check=True, capture_output=True)


root = Path(sys.argv[1] if len(sys.argv) > 1 else "/tls")
root.mkdir(parents=True, exist_ok=True)
for name in ("ca", "wrong-ca"):
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=SendiumLocal-" + name,
            "-keyout", str(root / (name + ".key")), "-out", str(root / (name + ".pem")))
openssl("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=db", "-keyout", str(root / "server.key"), "-out", str(root / "server.csr"))
(root / "server.ext").write_text("subjectAltName=DNS:db\n")
openssl("x509", "-req", "-in", str(root / "server.csr"), "-CA", str(root / "ca.pem"), "-CAkey", str(root / "ca.key"),
        "-CAcreateserial", "-days", "2", "-extfile", str(root / "server.ext"), "-out", str(root / "server.pem"))
(root / "password").write_text(secrets.token_hex(16))
(root / "pg_hba.conf").write_text("local all all trust\nhostnossl all all 0.0.0.0/0 reject\nhostssl all all 0.0.0.0/0 scram-sha-256\nhostnossl all all ::/0 reject\nhostssl all all ::/0 scram-sha-256\n")
for path in root.iterdir():
    os.chown(path, 70, 70)
    path.chmod(0o600 if path.suffix == ".key" or path.name == "password" else 0o644)
