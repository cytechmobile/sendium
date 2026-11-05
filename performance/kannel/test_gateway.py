"""The final resource interval must finish before either gateway member stops."""

import io
from pathlib import Path
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

import gateway


class GatewaySamplingLifecycleTests(unittest.TestCase):
    def supervise(self, sampler_exit):
        stopped = []

        class Process:
            def __init__(self, name, pid, exit_code=0):
                self.name, self.pid, self.exit_code = name, pid, exit_code
                self.returncode = None

            def poll(self):
                return self.returncode

            def terminate(self):
                stopped.append(self.name)
                self.returncode = self.exit_code

            def wait(self, **_):
                return self.returncode

        processes = [Process("bearerbox", 7), Process("smsbox", 8), Process("sampler", 9, sampler_exit)]
        event = MagicMock()
        event.is_set.return_value = False
        event.wait.return_value = True  # Stop once readiness has been published.
        forbidden = urllib.error.HTTPError("http://127.0.0.1/sendsms", 403, "Forbidden", {},
                                          io.BytesIO(b"Authorization failed for sendsms"))
        with patch.object(gateway.signal, "signal"), patch.object(gateway.threading, "Event", return_value=event), \
                patch.object(gateway.subprocess, "Popen", side_effect=processes) as launch, \
                patch.object(gateway.socket, "create_connection"), \
                patch.object(gateway.urllib.request, "urlopen", side_effect=forbidden), \
                patch.object(Path, "mkdir"), patch.object(Path, "exists", return_value=True), \
                patch.object(Path, "open", return_value=io.StringIO("{}\n")), patch.object(Path, "write_text"):
            if sampler_exit:
                with self.assertRaisesRegex(RuntimeError, "sampler did not finish"):
                    gateway.main()
            else:
                gateway.main()
            self.assertEqual(["sampler", "smsbox", "bearerbox"], stopped)
            self.assertIn("bearerbox:7", launch.call_args_list[2].args[0])
            self.assertIn("smsbox:8", launch.call_args_list[2].args[0])

    def test_sampler_finishes_before_either_member_stops(self):
        self.supervise(sampler_exit=0)

    def test_failed_sampler_does_not_skip_member_cleanup(self):
        self.supervise(sampler_exit=1)


if __name__ == "__main__":
    unittest.main()
