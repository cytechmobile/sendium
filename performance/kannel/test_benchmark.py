"""Local benchmark preparation, ownership, provenance, and failure evidence checks."""

import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

try:
    import benchmark
    from jsonschema import ValidationError
except ModuleNotFoundError as error:
    if error.name not in ("jsonschema", "referencing"):
        raise
    benchmark = None


@unittest.skipIf(benchmark is None, "Controller tests require the contracts dependencies (available in the Docker control image)")
class BenchmarkControllerTests(unittest.TestCase):
    def fixtures(self):
        source = {"baseCommit": "a" * 40, "workingTreeDigest": "b" * 64, "sourceMode": "local-working-tree", "branch": "fixture"}
        sampler = benchmark.digest(benchmark.PERFORMANCE / "resources/sample_process.py")
        gateway = {"schemaVersion": 1, "role": "gateway", "architecture": "x86_64", "samplerSha256": sampler,
                   "supervisorSha256": benchmark.digest(benchmark.PERFORMANCE / "kannel/gateway.py"),
                   "postgresqlSupport": True, "postgresqlProbeSha256": benchmark.digest(benchmark.PERFORMANCE / "kannel/postgresql_probe.py"),
                   "source": {"version": "1.4.5", "archiveSha256": "c" * 64, "patchArchiveSha256": "d" * 64, "patches": ["fix.patch"]},
                   "binarySha256": {"bearerbox": "e" * 64, "smsbox": "f" * 64}}
        harness = {"schemaVersion": 1, "role": "harness", "architecture": "x86_64", "samplerSha256": sampler,
                   "sourceContext": source, "harnessSha256": "9" * 64, "javaVersion": "25 fixture"}
        preset = benchmark.read(benchmark.PERFORMANCE / "presets/aws-private-jvm-v1.json")
        return preset, source, gateway, harness

    def test_shared_defaults_and_overrides_preserve_the_workload_boundary(self):
        preset, *_ = self.fixtures()
        default = benchmark.workload(preset, SimpleNamespace(offered_rate=None), "http-dlr")
        self.assertEqual(default["workload"], preset["workload"])
        self.assertIsInstance(default["workload"]["offeredMessagesPerSecond"], float)
        changed = benchmark.workload(preset, SimpleNamespace(offered_rate=12.5, worker_tps=7,
                                     warmup_seconds=0, measurement_seconds=65, drain_seconds=5,
                                     provider_response_delay_ms=10, provider_dlr_delay_ms=500), "http-no-dlr")
        self.assertEqual((12.5, 7, 0, 65, 5, False), tuple(changed["workload"][key] for key in
                         ("offeredMessagesPerSecond", "workerTps", "warmupSeconds", "measurementSeconds", "drainTimeoutSeconds", "dlrEnabled")))
        self.assertEqual(preset["deployment"], changed["deployment"])
        for args in (SimpleNamespace(offered_rate=float("nan")), SimpleNamespace(offered_rate=0),
                     SimpleNamespace(offered_rate=None, measurement_seconds=0), SimpleNamespace(offered_rate=None, warmup_seconds=601)):
            with self.assertRaises((ValueError, ValidationError)):
                benchmark.workload(preset, args, "http-dlr")

    def test_prepared_public_configuration_excludes_credentials_and_identifies_actual_components(self):
        preset, source, gateway, harness = self.fixtures()
        preset = benchmark.workload(preset, SimpleNamespace(offered_rate=None, worker_tps=9), "http-dlr")
        preset["deployment"]["callbackThreads"] = 7
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            settings, effective = benchmark.prepare(root, preset, "http-dlr", "test-run", source, gateway, harness,
                                                   {"gateway": "sha256:" + "e" * 64, "harness": "sha256:" + "f" * 64},
                                                   ["psecret1", "http-private", "admin-private"])
            runtime = benchmark.read(root / "inputs/runtime-settings.json")
            execution = benchmark.read(root / "execution.json")
            self.assertEqual("psecret1", runtime["provider"]["password"])
            self.assertEqual("http-private", runtime["ingress"]["password"])
            self.assertEqual("gateway-ready.json", runtime["localReadinessFile"])
            self.assertEqual(7, effective["deployment"]["callbackThreads"])
            self.assertEqual(effective["deployment"]["callbackThreads"], runtime["callbackReceiver"]["threads"])
            self.assertIn("throughput = 9", (root / "kannel.conf").read_text())
            self.assertIn("[REDACTED]", effective["gatewayConfiguration"])
            self.assertFalse(any(secret in json.dumps(effective) for secret in ("psecret1", "http-private", "admin-private")))
            self.assertIsNone(execution["context"]["provenance"]["applicationCommit"])
            self.assertEqual(source["baseCommit"], execution["context"]["provenance"]["harnessRevision"])
            self.assertEqual(harness["harnessSha256"], next(item["sha256"] for item in execution["context"]["provenance"]["artifacts"] if item["name"] == "harness"))
            self.assertFalse(execution["context"]["infrastructure"]["database"]["provisioned"])
            self.assertLess(settings["lifetimeSeconds"], settings["maxSamples"])

    def test_reused_images_cannot_mix_source_sampler_supervisor_or_architecture(self):
        _, source, gateway, harness = self.fixtures()
        benchmark.validate_images(source, gateway, harness, "x86_64")
        for field, value in (("sourceContext", {**source, "workingTreeDigest": "0" * 64}),
                             ("samplerSha256", "0" * 64), ("architecture", "aarch64")):
            changed = copy.deepcopy(harness)
            changed[field] = value
            with self.assertRaisesRegex(ValueError, "BUILD_IDENTITIES_DIFFER"):
                benchmark.validate_images(source, gateway, changed, "x86_64")
        changed = copy.deepcopy(gateway)
        changed["supervisorSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "BUILD_IDENTITIES_DIFFER"):
            benchmark.validate_images(source, changed, harness, "x86_64")

    def failure_case(self, cleanup_fails):
        preset, source, gateway, harness = self.fixtures()
        preset["runtime"]["java"]["harnessArguments"] = ["-Xms256m", "-Xmx768m", "-XX:+UseG1GC"]
        commands = []
        def docker(*arguments, **_):
            commands.append(arguments)
            return json.dumps({"Running": False, "ExitCode": 1}) if arguments[0] == "inspect" else "owned-resource"
        def failed_readiness(directory, *_):
            (directory / "initial").mkdir()
            benchmark.write(directory / "initial/state.json", {"phase": "READINESS"})
            raise RuntimeError("READINESS_FAILED")
        def collect(candidates, output, passwords, outcome, roles=None):
            if cleanup_fails:
                outcome["cleanupIssues"].append("INJECTED_CLEANUP_FAILURE")
            else:
                candidates.clear()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "public").mkdir()
            database = SimpleNamespace(settings={"host": "db", "port": 5432, "username": "kannel_perf", "password": "db-private-value",
                                                 "database": "kannel_benchmark", "poolMaxSize": 10, "caFile": "/probe/tls/ca.pem"},
                                       public={"engine": "postgresql", "provisioned": True, "sslMode": "verify-full", "version": "17.6"},
                                       password="db-private-value", network="db-network", start=Mock(), close=Mock())
            with patch.object(benchmark, "STATE", root), patch.object(benchmark.smoke, "docker", side_effect=docker), \
                    patch.object(benchmark, "await_listeners", side_effect=failed_readiness), \
                    patch.object(benchmark, "Database", return_value=database), \
                    patch.object(benchmark.smoke, "collect_and_remove", side_effect=collect):
                result = benchmark.run_case(preset, "http-dlr", "12345-abcdef", "sendium-local-state-12345-abcdef",
                                            source, gateway, harness, {"gateway": "gateway", "harness": "harness", "database": "database"}, 1)
            self.assertFalse(result["passed"])
            self.assertEqual("READINESS_FAILED", result["failure"])
            self.assertTrue((root / "public/http-dlr/initial/state.json").is_file())
            self.assertFalse((root / "public/http-dlr/inputs/runtime-settings.json").exists())
            self.assertEqual(cleanup_fails, bool(list((root / "private").iterdir())))
            self.assertTrue(any(command[:2] == ("network", "rm") for command in commands))
            self.assertTrue(any(command[0] == "run" and "-Xmx768m" in command for command in commands))
            database.close.assert_called_once()

    def test_postgresql_configuration_records_the_backend_and_keeps_database_credentials_private(self):
        preset, source, gateway, harness = self.fixtures()
        database = SimpleNamespace(settings={"host": "db", "port": 5432, "username": "kannel_perf", "password": "database-private",
                                             "database": "kannel_benchmark", "poolMaxSize": 10, "caFile": "/probe/tls/ca.pem"},
                                   public={"engine": "postgresql", "provisioned": True, "sslMode": "verify-full", "version": "17.6"})
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            _, effective = benchmark.prepare(directory, preset, "http-dlr", "pgsql-test", source, gateway, harness,
                                             {"gateway": "gateway", "harness": "harness"},
                                             ["provider", "http-private", "admin-private", "database-private"], database)
            self.assertEqual("postgresql", effective["dlrStorage"])
            self.assertTrue(effective["deployment"]["database"]["provisioned"])
            self.assertIn("dlr-storage = pgsql", (directory / "kannel.conf").read_text())
            self.assertIn("max-connections = 10", (directory / "kannel.conf").read_text())
            self.assertNotIn("database-private", json.dumps(effective))
            self.assertEqual("database-private", benchmark.read(directory / "database.json")["password"])

    def test_setup_failure_keeps_public_diagnostics_and_cleans_private_inputs(self):
        self.failure_case(cleanup_fails=False)

    def test_failed_container_cleanup_preserves_private_files_until_session_cleanup(self):
        self.failure_case(cleanup_fails=True)


if __name__ == "__main__":
    unittest.main()
