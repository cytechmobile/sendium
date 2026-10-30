"""Failure-path checks for the local-only Docker lifecycle."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import smoke


class SmokeLifecycleTest(unittest.TestCase):
    def failure_run(self, provider_shutdown_times_out):
        commands = []

        def execute(command, **_):
            commands.append(command)
            args = command[1:]
            text = ""
            if args[0] == "info":
                text = "x86_64"
            elif args[:2] == ["image", "inspect"]:
                text = json.dumps([{"Id": "sha256:" + "a" * 64, "Os": "linux", "Architecture": "amd64"}])
            elif args[0] == "ps":
                text = args[args.index("--filter") + 1].removeprefix("name=^/").removesuffix("$")
            elif args[0] == "logs":
                text = "provider-secret http-secret admin-secret"
            elif args[0] == "wait":
                if provider_shutdown_times_out:
                    raise subprocess.TimeoutExpired(command, 20)
                text = "0"
            return subprocess.CompletedProcess(command, 0, text, "")

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "evidence"
            with patch.object(smoke.subprocess, "run", side_effect=execute), \
                    patch.object(smoke.secrets, "token_hex", side_effect=["provider-secret", "http-secret", "admin-secret"]), \
                    patch.object(smoke, "wait_ready", side_effect=[None, RuntimeError("gateway readiness failed")]):
                result = smoke.run_smoke(output, skip_build=True)
            self.assertEqual("failed", result["status"])
            self.assertIn("gateway readiness failed", result["issues"])
            self.assertEqual([], result["cleanupIssues"])
            if provider_shutdown_times_out:
                self.assertIn("Provider probe did not stop within its shutdown budget", result["issues"])
            self.assertEqual(2, sum(command[1:3] == ["rm", "--force"] for command in commands))
            self.assertEqual(1, sum(command[1:3] == ["network", "rm"] for command in commands))
            for log in output.glob("*.log"):
                self.assertEqual("[REDACTED] [REDACTED] [REDACTED]", log.read_text(encoding="utf-8"))
            self.assertEqual("failed", json.loads((output / "outcome.json").read_text())["status"])

    def test_failed_gateway_preserves_diagnostics_and_cleans_both_nodes_and_network(self):
        self.failure_run(provider_shutdown_times_out=False)

    def test_provider_shutdown_timeout_cannot_skip_container_cleanup(self):
        self.failure_run(provider_shutdown_times_out=True)


if __name__ == "__main__":
    unittest.main()
