# Execute a benchmark

Build with `./mvnw -Pperformance -pl performance/harness verify`. Commands below
use `performance/harness/target/sendium-performance-harness-0.5.2-SNAPSHOT.jar`.

## Run traffic

```sh
java -jar performance/harness/target/sendium-performance-harness-0.5.2-SNAPSHOT.jar \
  run generated/harness execution.json run-output
```

The generated harness directory contains `run-config.json`, private
`runtime-settings.json`, and sanitized `effective-configuration.json`.
The output parent must exist; `run-output` must not exist. Credentials are not
copied into reports. The runner owns the simulator, ingress adapter, optional
HTTP receipt listener, generator, and optional local harness sampler. Sendium,
PostgreSQL, and remote application sampling remain deployment-owned.

Start the harness, wait for `state.json` to show `listenersReady: true`, then
start packaged Sendium using its generated working directory/environment. This
avoids an initial failed provider bind and reconnection delay. An already-running
application can also reconnect to the new provider. Readiness requires HTTP `UP`,
an `UP` `sendium-dlr-storage` check with `backend=postgresql`, and a provider bind.
PostgreSQL remains required even when message-level DLR is disabled. SMPP ingress
connects when selected; optional local sampling must produce a baseline.

Warm-up, measurement, and bounded drain then run automatically. Accounting is
sealed, owned services stop, and JSON/Markdown reports are written. Atomically
replaced state includes phase, harness PID, UTC measurement origin/duration, and
final validity/exit status. `lifecycle.jsonl` records transitions without credentials.

## Execution manifest

Create `execution.json` from build/provisioning metadata. Replace placeholders
with actual identities/digests; placeholder hashes are rejected.

```json
{
  "schemaVersion": 1,
  "readinessTimeoutSeconds": 60,
  "retentionSeconds": 300,
  "intervalBudget": 256,
  "context": {
    "provenance": {
      "branch": "<selected branch>",
      "applicationCommit": "<exact 40-character application SHA>",
      "harnessRevision": "<exact 40-character harness SHA>",
      "workflowRunUrl": null,
      "runIdentity": "<same identity used by generate-config>",
      "artifacts": [
        {"name": "application.zip", "sha256": "<64-character SHA-256>"},
        {"name": "harness.zip", "sha256": "<64-character SHA-256>"}
      ]
    },
    "application": {"jvmArguments": [], "connections": {}, "dlrStorage": "postgresql"},
    "infrastructure": {
      "preset": "<preset identity>", "region": "local",
      "sendium": {"execution": "host-jvm"},
      "harness": {"execution": "host-jvm"},
      "database": {"engine": "postgresql", "version": "17"}
    },
    "diagnostics": []
  },
  "resources": {
    "schemaVersion": 1, "sendiumSamples": null, "harnessSamples": null,
    "cpuCredits": null, "sampleBudget": 4096
  },
  "sampling": null
}
```

Supply actual application JVM arguments, instance IDs/types/architecture/AZ,
database placement/sizing, region, and workflow URL for AWS execution. `local`
is reserved for non-AWS functional verification. Optional `processId` and
`creditMode` are checked against evidence. The runner records its PID and merges
generated workload/worker/database settings into public configuration, refusing
mismatched workload/run identities. Only sanitized metadata belongs in `context`.

Evidence paths are relative to this manifest or absolute. Null means unavailable,
not zero utilization. Missing evidence makes reports inconclusive. On Linux,
optional local harness sampling can replace `sampling: null` with:

```json
{"pythonExecutable":"python3", "agentPath":"tools/sample_process.py",
 "intervalSeconds":1, "maxSamples":4096}
```

The agent path is relative to `execution.json`. Its budget must cover all phases
and fit the import budget. Do not also specify an external `harnessSamples` path.
The runner stops the agent and waits for its final complete sampling interval.
Remote Sendium sampling is deployment-controlled; see `../resources/README.md`.

## Outcomes and cleanup

- Exit **0**: valid or explicitly inconclusive observation.
- Exit **1**: classified setup, execution, accounting, or cleanup failure, with
  reports when valid configuration and an owned output directory were available.
- Exit **2**: invalid invocation/configuration, unsupported inputs, existing
  output, or inability to publish reports.

Readiness and admission timeouts cover response bodies as well as headers.
Drain tracks pending admission, provider work, receipt mapping, and downstream
acknowledgement writes. The final receipt does not close its connection before
its acknowledgement finishes. Pending acknowledgements are reported at cutoff.
Accounting freezes before cancellation: teardown cannot rewrite pending
admissions into transport failures or contaminate protocol diagnostics.

The runner never stops external Sendium/database processes. A JVM shutdown hook
requests interruption and permits bounded best-effort cleanup/reporting during
graceful termination. Abrupt process/host loss cannot guarantee artifacts; AWS
cleanup and independent expiry remain Phase 4 orchestration responsibilities.

## Finalize resources offline

After retrieving remote samples and AWS credit diagnostics, create
`resource-inputs.json` containing the `resources` object above with populated
paths relative to that file. Run without AWS credentials/OIDC permissions:

```sh
java -jar performance/harness/target/sendium-performance-harness-0.5.2-SNAPSHOT.jar \
  finalize-report run-output/results.json resource-inputs.json final-output
```

The output directory must be new. This replaces resource summaries and
resource-scoped validity reasons using recorded UTC origin/exact duration. It
does not repeat traffic or change counts, intervals, latencies, drain outcomes,
provenance, or application configuration. Workload/setup failures remain failed.

Use an unprivileged publishing job: protected AWS jobs retrieve diagnostics and
publish input artifacts, then a separate job runs report code without provisioning
credentials. Validate final artifacts with `performance/contracts/validate.py`.

## Local end-to-end verification

```sh
./mvnw -DskipTests -pl sendium-app -am package
./mvnw -Pperformance,performance-integration -pl performance/harness verify
```

This checks adapters and the executable CLI for HTTP/SMPP with DLR enabled and
disabled. CLI fixtures use test-only provenance and omit production resource
evidence: they are functional checks, not publishable performance findings.
Logs/results remain under `performance/harness/target/integration-runs/`.
