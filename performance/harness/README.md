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

Task 1.2 supplies configuration validation, packaging, and lifecycle control.
It does not yet execute a real protocol scenario or produce benchmark results;
protocol adapters and reporting are subsequent tracked tasks.

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
Minute aggregation and event-time throughput publication are delivered in Task
3.2; this ledger currently supplies overall counters and distributions.

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
network resources idempotently. The simulator is currently a reusable component;
full scenario/CLI wiring follows with the ingress adapters.

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
endpoint. Packaged Sendium/PostgreSQL integration remains Task 2.5; workload
scheduling and workflow execution remain later tracked tasks.

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
behaviors. Actual Sendium/PostgreSQL scenario verification remains Task 2.5.

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
