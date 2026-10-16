# Standalone JVM harness

Build and test from the repository root:

```sh
./mvnw -Pperformance -pl performance/harness verify
```

The opt-in `performance` profile leaves ordinary application builds unchanged.
The resulting shaded executable is
`target/sendium-performance-harness-0.5.2-SNAPSHOT.jar`.

```sh
java -jar performance/harness/target/sendium-performance-harness-0.5.2-SNAPSHOT.jar validate-config run-config.json
```

Supply a fully resolved workload matching `../contracts/run-config.schema.json`.
Validation prints the normalized workload and returns 0, or returns 2 for invalid
configuration/usage. It does not accept endpoints or credentials.

The lifecycle controller owns phase deadlines on an injected monotonic clock.
Protocol adapters implement `Scenario`: readiness must have bounded connection
and database timeouts; generation must respect the provided deadline and stop
synchronously; provider responses and receipts remain active during drain.
`hasOutstandingWork` includes pending admission and unresolved outcomes from
both warm-up and measurement. `finalizeAccounting` freezes outcome collection
before reporting. `close` releases resources, including on setup failure or
interruption. An incomplete bounded drain is reported rather than treated as
a performance failure.

The executable also provides `run` and offline `finalize-report` commands.
See [EXECUTION.md](EXECUTION.md) for manifests, startup order, sampling, outcomes,
and local end-to-end verification.

## Bounded accounting and latency collection

`MessageLedger` serializes protocol callbacks and generates compact run-local
payload tokens (`runToken:base36Sequence`). Adapters correlate protocol message
IDs back to these tokens. Active entries include warm-up work and ambiguous
transport outcomes. A full active/request budget returns a missed target;
unfinished entries are never evicted. Completed/rejected entries move into a
separately bounded retired history for duplicate detection.

Retired history is bounded by both age and `maxTrackedMessages`. Capacity can
shorten the configured retention horizon, so reporting must expose eviction
counts and `eventsBeyondRetention`. Events for previously issued tokens beyond
retention are not counted as proven duplicates or new completions; their presence
must make accounting coverage visibly limited/inconclusive. Unknown or malformed
tokens and contradictions within retained history count as violations. Protocol
adapters must reject unknown correlation identifiers rather than silently invent
tokens. Warm-up is excluded from measurement counts and latency samples; capacity
peaks include its impact on the harness.

Timeout/failed admission stays unresolved until finalization, even when observed
at the provider; no acknowledgement is inferred. Snapshots distinguish accepted
provider/receipt completions from total observed events. `freeze()` prevents later
callbacks from changing published accounting; stop/quiesce adapters before it.
The ledger supplies overall and minute-cohort counters/distributions, plus
separate event-time throughput and backlog windows.

`LatencyHistogram` uses HdrHistogram with three significant digits, microsecond
resolution, a fixed one-hour maximum, and millisecond output. Overflow is counted,
never silently clamped; the reporting validity classifier must mark it
inconclusive. Empty distributions have null percentiles. This bounds memory
independently of message volume.

## Simulated SMPP provider

`SmppProvider` listens at the explicit address/port in `ProviderSettings`,
authenticates isolated system ID/password credentials, and checks that submitted
single-part correlation payloads are known to the ledger. The provider accepts
default GSM or IA5 coding, rejects multipart indicators/message-payload TLVs,
and requires receipt requests to match the workload's delivery-report setting.
Delivery-report runs bind as transceivers; transmitter-only binds are allowed
when delivery reports are disabled.

Each successful submit receives a unique run-local `p-<base36Sequence>` provider
ID. Bounded virtual-thread tasks wait the configured response delay, complete
the response write, then wait the configured delivery delay before generating
a successful `DELIVRD` receipt. Receipt addresses reverse the submitted
source/destination. Receipt acknowledgement waits are bounded by the deployment
I/O timeout. Forwarded receipt reception is accounted by later ingress adapters,
not inferred from the provider's receipt acknowledgement.

No configured delay blocks a Netty event loop. `maxPending` bounds all delayed
responses and receipt acknowledgements together, independently of connection
and SMPP window limits. Saturation returns `STATUS_THROTTLED` and increments a
provider saturation counter; this is a harness limitation that later validity
reporting must classify as inconclusive, not ordinary Sendium overload.
Invalid submissions/correlation and protocol/I/O failures have separate bounded
diagnostic counters. A receipt timeout destroys the failed session to release
its outstanding request window; reconnection/failure-injection workloads remain
outside V1.

Use `start()`, `awaitBound(timeout)`, and `snapshot()` for lifecycle integration.
Drain must keep the provider running and include `snapshot().pending()` so
outstanding receipt acknowledgements are not lost. Quiesce/close it before
freezing the ledger. `close()` cancels delayed work and releases executor and
network resources idempotently. `BenchmarkRunner` wires the simulator and ingress
adapters into the executable lifecycle.

Cloudhopper uses the same managed version and JitPack repository as
`sendium-core`. Deployment credentials are redacted from the settings' string
representation; protocol payload/byte logging is disabled.

## HTTP submission and receipt reception

`HttpAdmissionClient` submits asynchronous Kannel-compatible GET requests to the
configured `/sendsms` URI. Each request URL-encodes credentials, synthetic
addresses, and the run token as single-part text. Delivery-report requests add
a URL-encoded callback template containing `token`, `messageId=%s`, and
`status=%d`; disabled delivery reports omit `dlr-url` entirely. Deployment
settings contain the advertised callback URI separately from the receiver's
listen address, supporting private EC2 networking.

`submit(measured)` reserves ledger capacity and returns an optional submission
with its token and completion future. Empty means a missed target, not a
rejection. The dispatch timestamp is set immediately before `sendAsync`, after
request construction. Request/connect timeouts use `admissionTimeoutMillis`.
Response bodies are limited to 4096 bytes, redirects are not followed, and
HTTP/1.1 is used consistently.

Classification:

| Response | Admission outcome | Diagnostic |
|----------|-------------------|------------|
| 202 with canonical UUID | Accepted | None |
| 503 or 429 | Rejected | Expected overload, separately counted |
| Other 4xx | Rejected | Authentication or unexpected-status failure |
| Invalid 202 body, redirects, other statuses including 500 | Failed/ambiguous | Protocol failure |
| Request/connect timeout | Timed out/ambiguous | Visible admission timeout |
| Transport/body-limit failure or cancellation | Failed/ambiguous | Transport failure |

The future resolves after ledger admission accounting. `problems()` provides
bounded diagnostics for later validity reporting. Authentication/protocol and
transport failures must remain distinguishable from ordinary overload. Do not
log request URIs or raw error messages: authentication uses query parameters.
`close()` cancels pending requests before ledger finalization; cancellation does
not prove the application never accepted them.

`HttpReceiptReceiver` accepts GET callbacks at an exact configured path, validates
the three query fields and a canonical gateway UUID, and requires delivered
status `1`. It records receipt forwarding through the ledger, checks gateway
identity against the acknowledgement, and tolerates callbacks arriving before
that acknowledgement. Later identity disagreement or gateway-ID reuse is an
accounting violation. The gateway-ID index is bounded by active plus retained
entries and expires with retired history.

Valid callbacks receive 204. Duplicate callbacks also receive 204 to avoid
manufacturing callback retry traffic, while the ledger records duplicates as
scenario violations. Previously issued tokens beyond retired-history retention
also receive 204, with a `LIMITED_HISTORY` diagnostic and no new completion count;
reporting must mark that coverage limitation inconclusive. Unknown/mismatched identities and malformed callbacks
receive 400; unexpected delivery status or disabled-report callbacks receive
422. Receipt forwarding is measured at callback reception, not HTTP response
completion. Failed acknowledgement writes are separately counted.

Receiver worker count and queue capacity are explicit and bounded. Queue
saturation increments a diagnostic and applies caller-runs backpressure;
validity reporting must mark that harness saturation inconclusive. Queries are
bounded to 4096 characters and reject duplicate fields. `close()` stops reception
and workers before freezing accounting.

These adapters are tested over real local HTTP sockets against a controlled API
endpoint and against packaged Sendium/PostgreSQL. Workload scheduling is integrated;
AWS deployment/workflow execution remains Phase 4/5 work.

## SMPP submission and forwarded receipts

`SmppAdmissionClient` binds one authenticated transceiver session using
`SmppIngressSettings`. Connection/bind/write/request waits are bounded. The
configured window must cover `maxOutstandingRequests` so local window waiting
does not silently become application latency. Credentials are redacted from
settings and protocol byte/PDU logging is disabled.

`submit(measured)` returns an optional token/completion pair, reserving ledger
capacity before starting a bounded virtual-thread request task. Empty means a
missed generation target. Single-part default-GSM payloads contain the run token;
registered delivery is requested only when enabled. Successful acknowledgements
require a nonblank provider/gateway ID. `STATUS_THROTTLED` and `STATUS_MSGQFUL`
are expected overload rejections; other negative acknowledgements are rejections
with protocol diagnostics. Timeouts and transport failures remain ambiguous,
not inferred rejections. Read `problems()` alongside ledger counters.

Forwarded `deliver_sm` requests must be successful single-part delivery receipts
with the expected receipt flag, default/IA5 coding, delivered state, zero error,
and one submitted/delivered message. Unlike HTTP callbacks, these receipts carry
only the gateway ID. A receipt arriving before acknowledgement mapping is stored
in a buffer bounded by `maxTrackedMessages`, including duplicates. Mapping replays
the original arrival timestamps into the ledger; acknowledgement wait time is
therefore excluded from receipt-forwarding latency. The ledger validates event
timestamp order before recording a latency sample.

Known valid and duplicate receipts are acknowledged successfully; duplicates
still count as scenario violations. Invalid receipt bodies/states are rejected.
Buffer saturation returns `STATUS_THROTTLED` and exposes a harness saturation
diagnostic. During drain, keep this client connected and include pending requests
and buffered receipts in outstanding-work observations.

After admission tasks stop, `finalizeReceipts()` classifies unmatched IDs:
`UNCORRELATED_RECEIPT` when no mapping limitation is known, `LIMITED_HISTORY`
after retained mapping eviction, or `AMBIGUOUS_RECEIPT` after an admission
timeout/failure (including warm-up). The latter two are inconclusive coverage
limitations; they are not proof of malformed traffic. No unverified receipt is
added to completion/latency totals. Final validity classification is implemented
in Task 3.3. `close()` destroys the session, interrupts/awaits request tasks,
releases network resources, and finalizes buffered receipts once requests stop.
Close adapters before freezing the ledger.

Tests use real SMPP sockets against the provider simulator and controlled gateway
behaviors, and the packaged CLI is verified against actual Sendium/PostgreSQL.

## Generate isolated runtime configuration

From the repository root, after building the harness:

```sh
java -jar performance/harness/target/sendium-performance-harness-0.5.2-SNAPSHOT.jar \
  generate-config \
  performance/harness/src/test/resources/run-config.json \
  performance/harness/src/test/resources/deployment.json \
  performance/harness/target/local-config
```

The fixture inputs illustrate local settings and contain test-only database
credentials. Deployment orchestration supplies actual resolved preset values,
private host addresses, and an isolated database. The output directory's parent
must exist; the output directory itself must not exist. Existing runs are never
overwritten. Generation does not connect to services or start a benchmark.

Generated files:

```text
<output>/
  sendium/
    config/application.properties   # Quarkus listener, paths, pool, and logging
    conf/smsg.properties            # Provider worker, TPS/windows, SMPP ingress
    conf/credentials.yml            # Only the selected ingress credential type
    conf/routingTable.conf          # One outbound route; SMPP receipt return route
    environment.json               # Private PostgreSQL environment variables
  harness/
    run-config.json                # Fully resolved workload
    runtime-settings.json          # Private provider/ingress credentials and endpoints
    effective-configuration.json    # Sanitized settings consumed by the runner
  public/
    effective-configuration.json   # Sanitized settings for later result publication
```

Every generation creates independent ingress/provider credentials. The two
runtime bundles contain secrets and are deployment inputs, not downloadable
result artifacts. Only `public/` is intended for publication. PostgreSQL URLs
must identify a database and contain no embedded authentication parameters;
credentials travel separately. On POSIX filesystems the output root is created
with mode 700 and files with mode 600; other filesystems use their native access
controls. Generated configuration should stay in ignored build output or external
run directories.

Launch the packaged Sendium application with `sendium/` as its working directory
and the key/value pairs from `sendium/environment.json` in its process environment.
Quarkus reads `config/application.properties`; Sendium reads its generated
`conf/` paths. Deployment scripts must set the JSON values as environment values,
not evaluate the JSON as shell commands. Copy the packaged application separately.
Task 2.5 verifies this launch path against actual Sendium and PostgreSQL.

The standalone application has PostgreSQL persistence enabled at build time.
**DLR disabled means no receipt request or callback; it does not remove the
datasource or disable the compiled persistence subsystem.** PostgreSQL remains
configured in both modes, and the public configuration records `dlrStorage` as
`postgresql` alongside the separate `dlrRequested` flag.

The provider worker gets the exact workload TPS, one transceiver, explicit
window/request timeout/thread settings, and a run-specific provider namespace.
Its provider-response retry lists are empty with a default `FAIL` policy, matching
V1's exclusion of retries. SMPP ingress is enabled only for SMPP runs, with no
extra ingress rate cap. HTTP callbacks advertise the harness host, while its
listeners bind to all local interfaces inside the private environment.
Per-message trace, raw SMPP logging, and HTTP access logging are disabled and
recorded in the effective configuration. AWS sizing, JVM arguments, and final
infrastructure presets remain later tasks.

## Local packaged-application verification

Requires Java 25, Maven, and a running Docker daemon. Build the actual application
first, then explicitly enable the harness integration profile:

```sh
./mvnw -DskipTests -pl sendium-app -am package
./mvnw -Pperformance,performance-integration -pl performance/harness verify
```

On Windows, use `mvnw.cmd` and quote `"-Pperformance,performance-integration"`
when invoking Maven from PowerShell. Ordinary harness verification without
`performance-integration` runs only the unit/socket tests and needs no Docker.

`PackagedSendiumIT` starts a disposable PostgreSQL 17 container and a separate
packaged Sendium JVM for each HTTP/SMPP and DLR enabled/disabled combination.
Each case has an isolated database, run directory, credentials, and listener
ports. The tests consume generated configuration directly, start the provider
and appropriate ingress adapters, require readiness to identify PostgreSQL, and
verify the upstream bind before submitting traffic.

Adapter cases send two warm-up messages and three measurement messages. Packaged
CLI cases exercise fixed-rate phases with two warm-up and four measurement
messages. Assertions verify admission, provider completion, receipt forwarding when requested, zero
accounting violations/harness diagnostics, and exclusion of warm-up messages
from measurement counts. Storage metrics must show provider acceptance,
resolution, and successful downstream completion for all five DLR-enabled
adapter messages (six for CLI cases), with no storage errors and no remaining
message/correlation rows.
DLR-disabled cases keep PostgreSQL available but require no tracking writes or
generated/forwarded receipts.

These are bounded functional checks with short fixed-rate phases, not capacity
findings or throughput gates. AWS execution and remote resource collection
remain Phase 4/6 work. The integration runner stops application processes in
`finally` blocks and stops the disposable database after the suite.

Generated runtime inputs and local diagnostic logs/accounting snapshots remain
under `performance/harness/target/integration-runs/<run-identity>/`. This private
tree includes credentials: publish only its sanitized `public/` subtree when
needed. Failsafe's test reports are in `target/failsafe-reports/`. A failed
readiness check points to that case's `diagnostics/sendium.log` rather than
printing raw runtime configuration. Nothing generated is committed.

To test another compatible packaged application, set `-Dsendium.app.jar` to its
absolute `quarkus-app/quarkus-run.jar` path; retain that directory's companion
libraries. `-Dintegration.output.directory` can select an external diagnostic
directory. The default application path targets the current checkout's build.

## Fixed-rate traffic scheduling

`FixedRateGenerator` supplies the same open-loop schedule to either ingress
adapter. For example, construct it with
`(measured, scheduled) -> client.submit(measured, scheduled).isPresent()`.
The callback must return promptly and must use the ledger-backed adapter: a
false result means that adapter already counted the capacity-missed target.
The scheduler never waits for the returned admission completion future.

Call `start(phase, startNanos, deadlineNanos)` from the scenario's
`startGeneration` hook and `stop()` from `stopGeneration`. Only warm-up and
measurement phases generate traffic. Stop wakes the generator and joins it
before switching cohorts or draining. Existing provider responses, admission
requests, and receipts continue through their adapters during drain. Close the
generator and adapters before freezing the ledger. Target exceptions propagate
through the phase future and `stop()`; they are execution failures, not valid
performance measurements.

Targets occupy a half-open interval `[start, deadline)`, beginning at offset
zero. The count is `ceil(rate * durationSeconds)`, with decimal arithmetic at
phase setup to avoid fractional-rate boundary errors. Individual deadlines are
rounded up to nanosecond resolution. Rates above one target per nanosecond are
not representable and are rejected. Normal phase statistics include the quiet
tail after the last target, so achieved input rate uses the full observation
window rather than an artificially shortened denominator.

The schedule remains anchored to its original start. When late, the generator
counts expired slots as missed in constant space and submits only the latest
due slot; it never replays the skipped backlog. Slow responses cannot reduce
the requested rate silently. Full admission/tracking budgets count capacity
misses without evicting unfinished work. Warm-up uses the same budgets but does
not contribute to measurement counters; leftover warm-up work can therefore
produce visible capacity misses during measurement.

Phase statistics expose requested/achieved input rate, scheduled/attempted
targets, scheduling misses, capacity misses, maximum dispatch-call lateness,
elapsed observation, early stop, and saturation. Early stop accounts for unsent
targets and marks the observation partial. A cancelled caller-facing result
future does not cancel the internal phase or permit overlapping phases; use
`stop()` to terminate generation safely. Only one phase can be active or queued.

Adapters retain the original scheduled timestamp alongside actual dispatch
timing, providing the basis for submission-cohort intervals in Task 3.2. Network
latency still begins at actual request dispatch rather than scheduled time.
JSON/summary reporting and evidence-based validity classification are described below.

## Overall and one-minute reports

`ResultReport` builds a schema-version-1 `results.json` and a GitHub-compatible
`summary.md` from frozen accounting. Supply exact branch/commit/harness/artifact
provenance, sanitized application/infrastructure settings, ordered generator
phase statistics, and the drain timeout outcome. Never supply the private
runtime bundles as report context. `write()` refuses existing report files.

The fixed-rate generator defines the measurement origin automatically. Manual
drivers must call `ledger.beginMeasurement(startNanos, durationNanos)` before
measurement traffic. End measurement on early termination, call `finishDrain()`
when observation stops, quiesce the adapters, then freeze accounting before
reporting. Finalization does not extend an already recorded drain window.
The lifecycle controller's absolute drain deadline includes time spent stopping
generation, keeping its budget aligned with reporting boundaries.

Reports contain two complementary minute views:

- **Submission cohorts:** admission outcomes, provider/receipt completion counts,
  unfinished work, and p50/p95/p99 latency boundaries for messages scheduled in
  each minute. Outcomes received during drain update their original cohorts.
- **Event-time output:** provider arrivals/responses and receipts per second in
  actual measurement/drain minute windows, plus end-of-window pending admission
  and accepted work awaiting provider/receipt completion. Warm-up events are
  excluded, including those arriving during measurement or drain.

Overall sending/receipt rates exclude drain completions. Cohort provider/receipt
rate fields are null rather than misleading completion-derived throughput.
Partial minutes use their actual observation duration. Empty distributions/rates
are null where undefined. Overall peaks include warm-up resource pressure;
per-cohort peaks and global scenario violations are not additive counters.

Shared counter and histogram update rules serve both overall and cohort totals.
Minute counters/histograms survive message-history eviction without retaining
individual events. The default interval budget is 256 measurement-plus-drain
minute windows; call the three-argument `beginMeasurement` first to configure a
different explicit memory budget. Histogram allocation is lazy per metric and
cohort. Out-of-window updates are counted as a limitation, not silently added to
the measured throughput.

Supply `ResourceEvidence`, the aligned UTC measurement window, and typed
`RunValidity` observations to classify results as valid, inconclusive, or failed.
Reports without resource evidence remain inconclusive. Empty arrays mean
unavailable measurements, not zero utilization. Accounting/setup/protocol failures
take precedence over evidence limitations; expected overload and unfinished work
alone impose no performance gate. See `../resources/README.md` for agents, AWS
read-only collection, import APIs, CPU/memory definitions, and status policy.

Report tests write synthetic fixtures under `target/report-fixtures/` and cover
minute boundaries, drain attribution, replayed receipt timestamps, bounded
history/budgets, partial observations, and stable finalization. Validate the
actual JSON output against the contract from the repository root:

```sh
python -m pip install -r performance/contracts/requirements-dev.txt
python performance/contracts/validate.py --results-directory performance/harness/target/report-fixtures
```

The runnable CLI coordinates readiness, adapters, phases, collectors, reporting,
and exit status. [EXECUTION.md](EXECUTION.md) documents `run` and offline resource
finalization before AWS deployment integration.

## Accounting and boundary verification

`MeasurementAccountingVerificationTest` combines ledger, interval, report, and
validity checks with controlled monotonic time. It exercises every admission
position around the ordered provider/receipt events for both accepted and timed-out
submissions. Timeout remains an ambiguous admission outcome even when provider
processing and a forwarded receipt are observed; it is never silently accepted.
Warm-up completions during drain remain excluded from measurement statistics.

The ledger rejects invalid receipt/admission times before committing their state,
and retained-token checks are atomic within one operation. Canonical token spelling
distinguishes malformed aliases from genuine events beyond retained history.
Duplicate receipts remain idempotently acknowledged but fail scenario accounting.
An explicit rejection contradicting observed provider processing also fails,
including when another diagnostic marks harness saturation inconclusive.

Early observation close includes already-recorded same-tick updates by advancing
the exclusive end one nanosecond if needed, within the absolute configured bounds.
Events exactly at the maximum drain deadline are outside that window; the report
keeps its pre-boundary unfinished count and discloses later observations.

Before publishing, `RunValidity` reconciles additive cohort counters and latency
sample counts with overall totals, checks declared minute target capacity, and
reconciles event counts when no out-of-window updates were recorded. A correct
overall submission count cannot hide an incorrectly attributed burst in one minute.
The Python contract validator independently checks rendered JSON equations,
windows, rates, distributions, and failure precedence, rather than relying solely
on the Java aggregation logic.

```sh
python -m unittest discover -s performance/contracts -p 'test_*.py'
python performance/contracts/validate.py --results-directory performance/harness/target/report-fixtures
```
