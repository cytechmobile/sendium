"""Guard reporting integration without adding a YAML dependency to CI."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[4]
WORKFLOW = ROOT / ".github/workflows/run_tests.yml"


def step(name):
    match = re.search(r"(?ms)^      - name: " + re.escape(name) + r"\n(.*?)(?=^      - |\Z)",
                      WORKFLOW.read_text(encoding="utf-8"))
    if not match:
        raise AssertionError(f"Missing step: {name}")
    return match.group(1)


class JvmWorkflowTests(unittest.TestCase):
    def test_reporting_failure_preserves_verification_and_diagnostics(self):
        for name in ("Publish JVM timing summary", "Upload JVM timing artifacts"):
            body = step(name)
            self.assertIn("if: always()", body)
            self.assertIn("continue-on-error: true", body)
        diagnostics = step("Upload backend failure diagnostics")
        self.assertIn("if: failure()", diagnostics)
        self.assertIn("retention-days: 7", diagnostics)
        self.assertIn("sendium-core/target/failsafe-reports/", diagnostics)
        self.assertLess(WORKFLOW.read_text().index("Upload backend failure diagnostics"),
                        WORKFLOW.read_text().index("Publish JVM timing summary"))
        self.assertIn("./mvnw -B -ntp -V -e verify", step("Build Backend"))

    def test_identity_and_retention(self):
        artifact = step("Upload JVM timing artifacts")
        self.assertIn("retention-days: 30", artifact)
        self.assertIn("github.run_id", artifact)
        self.assertIn("github.run_attempt", artifact)
        self.assertIn("steps.backend_build.outputs.elapsed-seconds", step("Publish JVM timing summary"))
        workflow = WORKFLOW.read_text()
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("actions: write", workflow)
        self.assertIn("python3 -m unittest discover", workflow)

    @unittest.skipUnless(os.name == "posix", "Execute the actual Actions bash script on POSIX")
    def test_verify_exit_survives_timing_output_failure(self):
        body = step("Build Backend")
        script = textwrap.dedent(body.split("run: |\n", 1)[1])
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            (root / "mvnw").write_text("#!/bin/sh\nexit 37\n")
            result = subprocess.run(["bash", "-e", "-c", script], cwd=root,
                                    env={**os.environ, "GITHUB_OUTPUT": str(root)},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 37, result.stdout + result.stderr)
            self.assertIn("::warning::", result.stdout)

    def test_existing_modes_and_policy_are_preserved(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("os: [ubuntu-latest, macos-latest]", workflow)
        self.assertIn("needs: [quick-start, tests]", workflow)
        self.assertIn("branches: [main]", workflow)
        smoke = step("Test packaged DLR modes and default memory capacity on JVM")
        self.assertIn("for backend in memory disabled; do", smoke)
        self.assertIn("SENDIUM_E2E_DLR_BACKEND=file", smoke)
        self.assertIn("100,000-entry", smoke)


if __name__ == "__main__":
    unittest.main()
