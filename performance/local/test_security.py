"""Regression checks for local certificate substitution and credential transport."""

import hashlib
import json
import subprocess
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import lab
import backend
from aws_cli import AwsCli, AwsError


class LocalSecurityTests(unittest.TestCase):
    def test_only_exact_rds_bundle_url_selects_local_certificate(self):
        bundle = "https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"
        with tempfile.TemporaryDirectory() as directory:
            assets = lab.Assets.__new__(lab.Assets)
            assets.cache = Path(directory)
            assets.identity, assets.fault = "gh-12345-1", None
            assets.real = Mock()
            payload = b"cached runtime"
            checksum = hashlib.sha256(payload).hexdigest()
            (assets.cache / (checksum + ".tar.gz")).write_bytes(payload)
            target = assets.cache / "target"
            target.write_bytes(payload)
            urls = (bundle, bundle + "?other=1", bundle + ".evil",
                    "https://truststore.pki.rds.amazonaws.com.evil/global/global-bundle.pem",
                    "https://evil.example/?host=truststore.pki.rds.amazonaws.com",
                    "https://truststore.pki.rds.amazonaws.com@evil.example/global/global-bundle.pem",
                    bundle.replace("https:", "http:"))
            for url in urls:
                with self.subTest(url=url), patch.object(lab.shutil, "copyfile") as copy:
                    assets.download(url, target, 1024, checksum, len(payload))
                    source = copy.call_args.args[0]
                    if url == bundle:
                        self.assertEqual(source, Path("/state/private/gh-12345-1/tls/ca.pem"))
                    else:
                        self.assertEqual(source, assets.cache / (checksum + ".tar.gz"))

    def test_secret_creation_uses_stdin_without_extra_credential_file_even_on_failure(self):
        identity = "gh-12345-1"
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory)
            tls = private / identity / "tls"
            tls.mkdir(parents=True)
            (tls / "password").write_text("local-test-password")
            fixture = SimpleNamespace(roots={}, labels=[], store="objects", passwords=[], real=Mock())
            fixture.real.call.side_effect = AwsError("fixture failure")
            with patch.object(backend, "Path", return_value=private), patch.object(backend, "docker"), \
                    patch.dict(backend.os.environ, {"LOCAL_STATE_VOLUME": "fixture-state"}):
                with self.assertRaises(AwsError):
                    backend.Lab.new_root(fixture, {"RunIdentity": identity}, [], None)
            parameters = fixture.real.call.call_args.kwargs
            self.assertEqual(parameters["secret_string"], "file:///dev/stdin")
            self.assertEqual(json.loads(parameters["stdin"])["password"], "local-test-password")
            self.assertFalse((private / identity / "secret.json").exists())

    def test_aws_cli_passes_stdin_without_putting_it_in_arguments(self):
        payload = json.dumps({"password": "local-test-password"})
        with patch("aws_cli.subprocess.run", return_value=SimpleNamespace(stdout='{"ARN":"secret-arn"}')) as run:
            response = AwsCli("local").call("secretsmanager", "create-secret", name="fixture",
                                           secret_string="file:///dev/stdin", stdin=payload)
        self.assertEqual(response["ARN"], "secret-arn")
        self.assertEqual(run.call_args.kwargs["input"], payload)
        self.assertNotIn(payload, run.call_args.args[0])
        self.assertNotIn("--stdin", run.call_args.args[0])

    def test_stdin_credentials_are_not_exposed_by_transport_errors(self):
        payload = json.dumps({"password": "local-test-password"})
        failure = subprocess.CalledProcessError(1, ["aws"], stderr=payload)
        with patch("aws_cli.subprocess.run", side_effect=failure):
            with self.assertRaises(AwsError) as caught:
                AwsCli("local").call("secretsmanager", "create-secret",
                                    secret_string="file:///dev/stdin", stdin=payload)
        self.assertNotIn("local-test-password", str(caught.exception))
        self.assertEqual(caught.exception.code, "AWS_REQUEST_FAILED")


if __name__ == "__main__":
    unittest.main()
