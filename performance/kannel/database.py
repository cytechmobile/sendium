"""Run-owned PostgreSQL/TLS and read-only DLR storage evidence for local Kannel."""

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

import smoke


class Database:
    def __init__(self, root, identity, prefix, session, volume, image, pool_size):
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
            raise ValueError("EXACT_DATABASE_IMAGE_REQUIRED")
        self.root, self.identity, self.session, self.volume = Path(root), identity, session, volume
        self.name, self.network, self.data_volume = prefix + "-database", prefix + "-database-network", prefix + "-database-data"
        self.pki = self.root / "private" / (identity + "-database")
        self.image, self.pool_size = image, pool_size
        self.container_attempted = self.network_attempted = self.volume_attempted = False
        self.observations = []
        self.public = {"provisioned": True, "engine": "postgresql", "sslMode": "verify-full", "host": "db", "port": 5432,
                       "databaseName": "kannel_benchmark", "poolMaxSize": pool_size, "imageId": image,
                       "resourceMetricsRequired": False, "verificationIntervalSeconds": 1,
                       "verificationSource": "PostgreSQL row counts and pg_stat_user_tables; not AWS/RDS utilization"}

    def sql(self, query):
        result = subprocess.run(["docker", "exec", "-i", self.name, "psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                                 "-U", "kannel_perf", "-d", "kannel_benchmark"], input=query, text=True, capture_output=True, timeout=15)
        if result.returncode:
            raise RuntimeError("POSTGRESQL_QUERY_FAILED")
        return result.stdout.strip()

    def start(self, directory, schema):
        subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "local/tls.py"), str(self.pki)],
                       check=True, capture_output=True, timeout=30)
        self.password = (self.pki / "password").read_text().strip()
        tls = directory / "tls"
        tls.mkdir()
        for name in ("ca.pem", "wrong-ca.pem"):
            shutil.copyfile(self.pki / name, tls / name)
        labels = ("--label", "sendium.local.session=" + self.session)
        self.network_attempted = True
        smoke.docker("network", "create", "--internal", *labels, self.network)
        self.volume_attempted = True
        smoke.docker("volume", "create", *labels, self.data_volume)
        self.container_attempted = True
        smoke.docker("run", "--detach", "--name", self.name, *labels, "--network", self.network, "--network-alias", "db",
                     "--log-driver", "json-file", "--log-opt", "max-size=1m", "--log-opt", "max-file=1",
                     "--mount", f"type=volume,source={self.volume},target=/tls,volume-subpath=private/{self.identity}-database,readonly",
                     "--mount", f"type=volume,source={self.data_volume},target=/var/lib/postgresql/data",
                     "--env", "POSTGRES_USER=kannel_perf", "--env", "POSTGRES_DB=kannel_benchmark",
                     "--env", "POSTGRES_PASSWORD_FILE=/tls/password", self.image, "postgres", "-c", "ssl=on",
                     "-c", "ssl_cert_file=/tls/server.pem", "-c", "ssl_key_file=/tls/server.key", "-c", "hba_file=/tls/pg_hba.conf")
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            probe = subprocess.run(["docker", "exec", self.name, "pg_isready", "-h", "127.0.0.1", "-U", "kannel_perf", "-d", "kannel_benchmark"],
                                   capture_output=True, timeout=5)
            if probe.returncode == 0:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError("POSTGRESQL_STARTUP_TIMEOUT")
        version = json.loads(self.sql("SELECT json_build_object('version',current_setting('server_version'),'number',current_setting('server_version_num')::int)"))
        if not 170000 <= version["number"] < 180000:
            raise RuntimeError("POSTGRESQL_SERVER_VERSION_MISMATCH")
        self.public["version"] = version["version"]
        self.public["schemaSha256"] = hashlib.sha256(schema.read_bytes()).hexdigest()
        self.sql(schema.read_text(encoding="utf-8"))
        plaintext = subprocess.run(["docker", "exec", self.name, "psql", "-X", "-w", "-d",
                                   "host=127.0.0.1 user=kannel_perf dbname=kannel_benchmark sslmode=disable", "-c", "SELECT 1"],
                                  capture_output=True, text=True, timeout=10)
        if plaintext.returncode == 0 or "no encryption" not in plaintext.stderr:
            raise RuntimeError("POSTGRESQL_PLAINTEXT_NOT_REJECTED")
        self.public["plaintextRejected"] = True
        self.settings = {"host": "db", "port": 5432, "username": "kannel_perf", "password": self.password,
                         "database": "kannel_benchmark", "poolMaxSize": self.pool_size, "caFile": "/probe/tls/ca.pem"}
        return self.settings

    def observe(self):
        value = json.loads(self.sql("""
            SELECT json_build_object(
              'pendingRows',(SELECT count(*) FROM dlr),
              'insertedRows',(SELECT n_tup_ins FROM pg_stat_user_tables WHERE relname='dlr' AND schemaname='public'),
              'deletedRows',(SELECT n_tup_del FROM pg_stat_user_tables WHERE relname='dlr' AND schemaname='public'),
              'kannelTlsConnections',(SELECT count(*) FROM pg_stat_activity a JOIN pg_stat_ssl s USING(pid)
                                      WHERE a.application_name='kannel-benchmark' AND s.ssl),
              'kannelConnections',(SELECT count(*) FROM pg_stat_activity WHERE application_name='kannel-benchmark'))
            """))
        value["timestamp"] = datetime.now(timezone.utc).isoformat()
        if len(self.observations) >= 6000:
            raise RuntimeError("DATABASE_OBSERVATION_BUDGET_EXCEEDED")
        self.observations.append(value)
        return value

    def finish(self):
        deadline = time.monotonic() + 5
        while True:
            value = self.observe()
            if value["kannelConnections"] == 0:
                return value
            if time.monotonic() >= deadline:
                raise RuntimeError("KANNEL_DATABASE_CONNECTIONS_DID_NOT_CLOSE")
            time.sleep(0.2)

    def evidence(self):
        return {"schemaVersion": 1, "database": self.public, "observations": self.observations,
                "peakObservedPendingRows": max((value["pendingRows"] for value in self.observations), default=0),
                "kannelTlsConnectionObserved": any(value["kannelTlsConnections"] > 0 for value in self.observations),
                "counterBoundary": "pg_stat_user_tables cumulative row operations, flushed after gateway connections close",
                "final": self.observations[-1] if self.observations else None}

    def close(self, output, issues):
        if self.container_attempted:
            try:
                result = subprocess.run(["docker", "logs", "--tail", "100", self.name], capture_output=True, text=True, timeout=10)
                logs = (result.stdout + result.stderr).replace(getattr(self, "password", "not-a-secret"), "[REDACTED]")
                (output / "database.log").write_text(logs, encoding="utf-8")
            except (OSError, subprocess.SubprocessError):
                issues.append("DATABASE_LOG_COLLECTION_FAILED")
            try:
                smoke.docker("rm", "--force", self.name)
                self.container_attempted = False
            except Exception:
                issues.append("DATABASE_CONTAINER_CLEANUP_FAILED")
        for attempted, command, name in ((self.volume_attempted, "volume", self.data_volume), (self.network_attempted, "network", self.network)):
            if attempted:
                try:
                    smoke.docker(command, "rm", name)
                except Exception:
                    issues.append("DATABASE_RESOURCE_CLEANUP_FAILED")
        if not self.container_attempted and self.pki.exists():
            shutil.rmtree(self.pki)
