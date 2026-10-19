"""Failure/authentication polling and credential-redaction regressions from the workflow audit."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from aws_cli import AwsCli, AwsError
import deploy
from node import Node, write
from test_cleanup import Clock
import test_deployment


class ControlAuditTests(unittest.TestCase):
    def controller(self, aws, directory, clock):
        staged = test_deployment.staged() | {"nodeDigests": {"harness": "ab" * 32}, "timeoutSeconds": 900}
        return deploy.Controller(aws, staged, test_deployment.deployment_outputs(), directory,
                                 clock=clock, sleep=clock.sleep)

    def test_invocation_authorization_failure_is_not_retried_as_propagation(self):
        clock, calls = Clock(), []
        def call(service, operation, **parameters):
            calls.append(operation)
            if operation == "send-command":
                return {"Command": {"CommandId": "command"}}
            raise AwsError("Denied", code="AccessDeniedException")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(deploy.DeploymentError, "polling failed"):
                self.controller(Mock(call=call), directory, clock).command("harness", "supervise", background=True)
        self.assertEqual(clock.now, 0)
        self.assertEqual(calls, ["send-command", "get-command-invocation"])

    def test_invocation_propagation_is_retried(self):
        clock, reads = Clock(), []
        def call(service, operation, **parameters):
            if operation == "send-command":
                return {"Command": {"CommandId": "command"}}
            reads.append(operation)
            if len(reads) == 1:
                raise AwsError("Not propagated", code="InvocationDoesNotExist")
            return {"Status": "Success"}
        with tempfile.TemporaryDirectory() as directory:
            self.controller(Mock(call=call), directory, clock).command("harness", "install")
        self.assertEqual(len(reads), 2)
        self.assertEqual(clock.now, 2)

    def test_status_is_listed_before_getting_a_not_yet_published_key(self):
        clock, lists, gets = Clock(), [], []
        def call(service, operation, **parameters):
            self.assertEqual(operation, "list-objects-v2")
            lists.append(parameters)
            return {"Contents": [] if len(lists) == 1 else [{"Key": parameters["prefix"]}]}
        def get(bucket, key, path):
            gets.append(key)
            Path(path).write_text(json.dumps({"runIdentity": test_deployment.staged()["runIdentity"], "source": "harness", "phase": "listening"}))
        with tempfile.TemporaryDirectory() as directory:
            result = self.controller(Mock(call=call, get=get), directory, clock).wait("harness", {"listening"}, 20)
        self.assertEqual(result["phase"], "listening")
        self.assertEqual(len(lists), 2)
        self.assertEqual(len(gets), 1)
        self.assertEqual(clock.now, 2)

    def test_status_get_denial_fails_fast_after_existence_is_confirmed(self):
        clock = Clock()
        aws = Mock(call=lambda *_args, **parameters: {"Contents": [{"Key": parameters["prefix"]}]},
                   get=Mock(side_effect=AwsError("Denied", code="AccessDenied")))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(deploy.DeploymentError):
                self.controller(aws, directory, clock).wait("harness", {"finished"}, 900)
        self.assertEqual(clock.now, 0)
        aws.get.assert_called_once()

    def test_transport_preserves_safe_get_error_code_without_private_stderr(self):
        failure = subprocess.CalledProcessError(1, ["aws"], stderr=b"An error occurred (AccessDenied) private-value")
        with tempfile.TemporaryDirectory() as directory, patch("aws_cli.subprocess.run", side_effect=failure):
            with self.assertRaises(AwsError) as caught:
                AwsCli("eu-west-1").get("bucket", "key", Path(directory) / "status")
        self.assertEqual(caught.exception.code, "AccessDenied")
        self.assertNotIn("private-value", str(caught.exception))

    def test_log_redaction_survives_runtime_configuration_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            aws = test_deployment.MemoryAws()
            prefix = "runs/gh-1-1/inputs/harness/generated/"
            aws.objects["bucket", prefix + "runtime-settings.json"] = b'{"provider":{"password":"private-password"}}'
            for name in ("run-config.json", "effective-configuration.json"):
                aws.objects["bucket", prefix + name] = b"{}"
            node = Node({"source": "harness", "runIdentity": "gh-1-1", "region": "eu-west-1", "bucket": "bucket",
                         "execution": {}, "sampleIntervalSeconds": 1, "sampleBudget": 30,
                         "jvmArguments": [], "timeoutSeconds": 10}, root=directory, install=directory, aws=aws)
            node.root.mkdir(parents=True)
            write(node.root / "runtime.json", {"java": "java"})
            process = Mock()
            process.process.poll.return_value = 0
            process.process.pid = 123
            process.log.return_value = "Application log: private-password"
            def launch(*_args, **_kwargs):
                (node.root / "generated/harness/runtime-settings.json").unlink()
                return process
            with patch("node.CapturedProcess", side_effect=launch):
                self.assertEqual(node.supervise(), 0)
            published = aws.objects["bucket", "runs/gh-1-1/outputs/harness/process.log"].decode()
            self.assertEqual(published, "Application log: [REDACTED]")


if __name__ == "__main__":
    unittest.main()
