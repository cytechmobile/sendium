# Manual performance workflow preparation

`.github/workflows/performance-tests.yml` is manual-only. Open **Actions →
Performance Tests → Run workflow**, select the application branch with **Use
workflow from**, and set the inputs below. GitHub requires the workflow file on
the default branch before dispatch is available.

The preparation stage validates requests, builds both Linux architectures and
publishes verified identities. The [protected AWS job](AWS_EXECUTION.md) resolves
compatibility and invokes CloudFormation/SSM. Report publication/cleanup follows
in Task 5.3 and trust/failure
verification in 5.4. A completed preparation run is not a benchmark observation.
Task 5.5's complete local rehearsal remains required before AWS validation in 6.1.

## Inputs and defaults

GitHub's [current workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onworkflow_dispatchinputs)
allows 25 top-level dispatch inputs (checked 2026-10-16). All 11 required controls
therefore have individual fields; durations do not need a grouped string.

| Input | Default | Validation |
|---|---:|---|
| `ingress_mode` | `http` | `http` or `smpp` |
| `sendium_instance_type` | `t4g.small` | EC2 type syntax; actual regional/type compatibility is preflight work |
| `harness_instance_type` | `t4g.small` | Same as above |
| `offered_rate` | 100 | Finite, positive messages/sec, at most 1,000,000,000; fractional rates supported |
| `worker_tps` | 100 | Positive Java-compatible integer |
| `warmup_seconds` | 120 | Integer, 0–600 |
| `measurement_seconds` | 600 | Integer, 1–3600 |
| `drain_seconds` | 120 | Integer, 0–600 |
| `dlr_enabled` | `true` | Boolean; `false` remains false through normalization |
| `provider_response_delay_ms` | 5 | Integer, 0–10000 |
| `provider_dlr_delay_ms` | 1000 | Integer, 0–60000 |

Settings merge into `performance/presets/aws-private-jvm-v1.json` and pass the
existing workload schema/compatibility/lifecycle-budget checks before builds.
Instance overrides become the effective preset's two EC2 defaults. RDS, storage,
networking, JVM arguments, capacities and retention remain preset settings.
Regional offerings, memory, AMI/instance architectures, exact engine/JRE identities
and expiry readiness are resolved/checked in the protected execution path.

Numbers arrive through the JSON-serialized `inputs` context, not shell fragments.
Whole-number fields reject fractional values, booleans and values outside Java
integer bounds; offered rate retains valid fractions. Unknown fields, malformed
types, invalid durations/delays and non-finite values fail preparation.

## Sources and privilege boundaries

- **Application:** use the original dispatch `github.sha`, not a subsequent lookup
  of the moving branch. Record the branch/ref and exact source SHA.
- **Harness/controller:** snapshot the maintained default branch once in the
  validation job, record its Git SHA and pin every later control/harness checkout
  to that SHA. Record `harnessRevision` separately from `applicationCommit`.
- **Workflow:** record `github.workflow_sha` separately. GitHub executes the
  workflow definition selected by dispatch; protected-environment admission for
  AWS jobs remains maintainer-controlled in Tasks 5.2/5.4.
- **Builds:** use separate application and harness jobs on native GitHub-hosted
  `ubuntu-24.04` (x86_64) and `ubuntu-24.04-arm` runners, with Temurin Java 25.
  Build both architectures without guessing EC2 architecture from a family name.
  The AWS job selects matching builds after type/AMI resolution.
- **Credentials:** validation, both build jobs and independent manifest aggregation
  have only `contents: read`; no environment, OIDC grant, cloud credentials or
  repository secrets are supplied. Checkout credentials are not persisted.
  Build Maven caches are not shared between the application and trusted harness.
- **Verification:** a fresh unprivileged job verifies every artifact as data. It
  executes neither the selected application nor a downloaded harness. Privileged
  orchestration likewise must not execute those artifacts.

The application uses `-DskipTests -pl sendium-app -am package` to produce its
packaged JVM distribution. The maintained harness uses `-Pperformance -pl
performance/harness verify`, including its unit/socket tests. Ordinary repository
CI and the local/AWS scenario checks remain separate acceptance evidence.

## Published build inputs

Artifact names include run ID **and attempt**. The normalized request is:

```text
performance-request-<runId>-<attempt>/request.json
```

It contains effective settings, run identity, application ref/commit,
harness/controller SHA, workflow SHA and the workflow URL. Each build publishes:

```text
performance-application-<architecture>-<runId>-<attempt>/
  application.tar.gz
  build-manifest.json
performance-harness-<architecture>-<runId>-<attempt>/
  harness.jar
  build-manifest.json
```

The application archive contains the **complete `quarkus-app/` tree**, including
libraries. Packaging rejects symlinks/special entries, bounds expanded/compressed
size and checks the source checkout's HEAD and actual build-runner architecture.
Harness packaging selects the primary shaded JAR without hardcoding the release
version or including intermediate/original/classifier JARs. Each manifest records
the component, run identity, exact source SHA, architecture, build Java version,
file name, byte size and SHA-256.

The independent `build-ready` job downloads all four builds, verifies strict
manifest fields/identity, fixed payload paths, exact sizes/digests and source
revisions, and publishes:

```text
performance-build-inputs-<runId>-<attempt>/build-inputs.json
```

All artifacts currently use the preset's 30-day retention. Preparation summaries
show settings/revisions and state that these are build inputs, not performance
findings. No runtime-generated credentials or private deployment configuration
exist at this stage. The helper's per-object transport limit is 1 GiB.

Fresh attempts have fresh identities/artifact names and never reuse another
attempt's inputs. For a complete retry, rerun all jobs or dispatch a new run so
validation emits the current attempt's request. A failed-jobs-only retry does not
recreate artifacts from an already completed validation job for the new attempt.

## Release compatibility

`release-please-config.json` explicitly includes the opt-in harness parent POM in
generic version updates, using its `x-release-please-version` marker. This avoids
relying on normal Maven-module discovery for a profile-only module. Preparation
tests check parent/root alignment and update registration; packaging tests also
use a different harness version to verify that file selection is version-agnostic.

## Verification

```sh
python -m pip install -r performance/workflow/requirements-dev.txt
python -m unittest discover -s performance/workflow -p 'test_*.py'
actionlint .github/workflows/performance-tests.yml
./mvnw -B -ntp -Pperformance -pl performance/harness verify
```

Tests cover defaults/all controls, boolean/fractional values, schema/budget/runtime
bounds, malformed inputs, source/attempt/architecture mismatch, complete archive
contents, digest tampering, manifest fields/paths, symlinks, escaped summaries,
workflow permission/source/runner contracts and release-parent alignment. Linux
runs the symlink test; Windows skips it without developer-mode privileges.
Workflow syntax/actionlint and local helper/Maven checks do not prove hosted-runner
execution; actual dispatch verification remains Task 5.4.
