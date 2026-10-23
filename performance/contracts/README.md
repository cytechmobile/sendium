# Performance harness contracts, version 1

These contracts define the versioned configuration and result formats for the performance harness.
They describe JSON exchanged by orchestration, the harness, and reporting;
they do not provision infrastructure or execute traffic.

## Application runtime identity

The effective preset records `runtime.applicationRuntime` as `jvm` (default) or
`native`. The request normalizer accepts `application_runtime`; the Action choice
will be exposed only after native build and launcher integration is complete.
Until then, deployment rejects native requests before staging or provisioning.
The unprivileged application build helper already selects the requested build
mode and produces native compiler metadata; Linux runtime execution validation is
the next integration step.

JVM application and harness manifests retain their existing fields. A native
application manifest additionally requires `applicationRuntime: native` and a
`nativeBuild` identity containing the schema version, target architecture, pinned
UBI9/Mandrel builder image, actual Java 25 native-image compiler version and
compatibility build arguments. These must match the normalized request. A JVM
artifact cannot satisfy a native request, or vice versa; the harness remains JVM.

The native archive has one executable at `native/application`, with an explicit
executable mode and ELF64 architecture checked during unprivileged packaging.
Artifact aggregation/orchestration validate manifests, sizes and SHA-256 values
as data and never execute the native application. An ELF header check identifies
the target format/architecture; it does not establish runtime-library compatibility
or successful execution, which require the later node validation.

Native runtime arguments use a separately validated heap configuration rather
than copying JVM-specific garbage-collector options. JVM remains the default;
the existing workload configuration and JSON measurement fields are unchanged.

## Validation

Validate both schemas and boundary examples with `python validate.py` from
this directory after installing `requirements-dev.txt` in a development environment.
After the Java report tests, also validate actual generated reports from the
repository root:

```sh
python performance/contracts/validate.py --results-directory performance/harness/target/report-fixtures
```
Structural schema validation does not replace runtime validation of accounting
equations, interval ordering, empty distributions, or configuration consistency
(for example, PostgreSQL storage when delivery reports are enabled).
The generated-report command also runs the independent `semantics.py` checks:
admission/completion equations, additive cohort and latency counts, contiguous
observation windows, event-rate denominators, drain backlog, null/ordered latency
statistics, and validity/exit consistency. A report can describe a failed run and
still be a valid artifact; inconsistent report data fails validation itself.

```sh
python -m unittest discover -s performance/contracts -p 'test_*.py'
```

## Configuration

`run-config.schema.json` describes a fully resolved workload configuration.
Orchestration must resolve defaults before passing it to the harness. JSON
Schema `default` annotations are documentation, not automatic insertion.

Defaults are warm-up 120 seconds, measurement 600 seconds, drain 120 seconds,
and delivery reports enabled. Rates, provider delays, request bounds, and
accounting capacity must be explicitly recorded. No universal throughput or
instance-size default is imposed by this contract.

`maxTrackedMessages` bounds all active correlation entries, including warm-up
messages and unresolved admission outcomes. Never evict an unfinished message
to make room for new traffic: miss the generation target and flag saturation.
`maxOutstandingRequests` bounds admission requests separately. Completed
entries may be retired into bounded duplicate-detection bookkeeping; results
must state its detection horizon. Duplicate detection beyond that horizon is
a recorded limitation, not a claim of exhaustive detection.

Actual endpoints and credentials belong to deployment configuration, not
these public result artifacts. Never serialize passwords, authorization
headers, database secrets, or credential-bearing URLs.

## Message accounting

Each generated message carries a unique run-local correlation token in its
single-part payload. Map ingress acknowledgement IDs and provider IDs to that
token. The simulator rejects malformed or unknown correlation tokens as an
accounting violation. Receipt correlation uses the provider ID and the
forwarded ingress ID, rather than assuming those IDs are equal.
Tokens must use their issued canonical spelling. Numeric aliases such as
`run:01` or `run:+1` are malformed, not evidence of expired duplicate history.

Admission outcomes are mutually exclusive: accepted, rejected (explicit
negative acknowledgement), failed (transport/protocol failure), timed out,
or pending. A failed/timed-out request may still reach the provider: retain
its identity and count that observation as `providerObservedWithoutAcceptance`.
Do not automatically turn ambiguous transport outcomes into rejections.

For a reporting cohort:

```text
scheduled = attempted + missed
attempted = accepted + rejected + failed + timedOut + pendingAdmission
accepted = acceptedProviderCompleted + acceptedAwaitingProvider
```

When delivery reports are enabled:

```text
accepted = acceptedReceiptForwarded + acceptedAwaitingReceipt
```

`acceptedAwaitingReceipt` includes messages not yet processed by the provider;
it overlaps `acceptedAwaitingProvider` and must not be added to it. Unfinished
accepted work is accepted messages without their final required outcome:
provider response completion when reports are off, receipt forwarding when on.
Use unique-message counts for accounting, and separate duplicate counters.

Expected overload rejection is an outcome, not an accounting failure. Pending
work after bounded drain is reported; it is not an arbitrary performance gate.
Contradictory outcomes, unexpected duplicates, malformed identities, or failed
accounting equations are scenario violations and fail the run.

## Phases and interval attribution

Warm-up and measurement have distinct correlation cohorts. Stop generation at
the measurement boundary and continue outstanding provider/receipt processing
during drain. Do not count warm-up outcomes as measurement outcomes.

Each measurement message belongs to the interval of its **scheduled generation
time**, with half-open intervals `[start, end)`. The last interval may be shorter
than 60 seconds. Its final admission/completion counts and latency samples can
therefore change during drain. Sum additive cohort counters to obtain overall
counts. Peaks are not additive: overall peaks include all harness work, including
warm-up, whereas cohort peaks count concurrent entries belonging to that cohort.
Scenario-wide violations can include unknown identities and warm-up violations
and are reported globally rather than assigned to an invented cohort.

Also publish event-time provider-response and receipt-forwarding counters per
minute, with an explicit `phase` of `measurement` or `drain`. These count only
measurement messages, but place completions at the time they actually occur.
Event-time rates expose delayed output and backlog; cohort completion divided
by measurement duration alone must not be presented as sustainable throughput.
Overall event throughput uses the measurement observation window; drain counts
are separate. Requested/attempted input rates use measurement duration.

In `cohortIntervals`, provider/receipt throughput rate fields are null on purpose;
cohort completions divided by cohort duration are not sustainable sending rates.
`eventIntervals` supplies minute-window counts/rates and outstanding measurement
work at each window's exclusive end. Changes exactly at a minute boundary belong
to the next interval. Pending admission reflects outcomes known by that boundary;
an early SMPP receipt replayed after acknowledgement retains its original event
time without creating a negative accepted-message backlog before acknowledgement.

The final measurement/drain intervals may be shorter than a minute. Early-ended
measurements retain planned cohort boundaries and their observed durations,
including zero observed duration for future cohorts with missed targets. Resource
evidence and classification are described in `../resources/README.md`; missing
evidence is explicitly inconclusive rather than interpreted as zero utilization.
When early finalization shares a monotonic clock tick with an already-recorded
update, the exclusive observation end advances by the minimum one nanosecond
needed to include that update. Configured measurement/drain deadlines remain
exclusive and are never extended. This avoids silently dropping a same-tick
callback into a nonexistent interval. Receipt timestamps must be between their
predecessor event and recording time; invalid timestamps cannot mutate receipt
identity, completion counters, or latency samples.

Interval storage has an explicit default budget of 256 minute windows across
measurement and maximum drain. Histograms are allocated only for sampled
boundaries, and counters survive correlation-history eviction. A larger budget
may be supplied before generation and is recorded in metadata; an insufficient
budget fails setup before allocating an unbounded interval history. Events or
backlog updates beyond the bounded observation window are disclosed and excluded
from event-time rates. Drain unfinished-accepted counts use the window-end
backlog; all-phase physical counts are labeled as finalization-time observations.

## Latency boundaries and clocks

All workload timing uses the harness monotonic clock. The generator, provider
simulator, and forwarded receipt receiver run on the same harness instance;
no cross-host wall-clock subtraction is needed. UTC timestamps identify runs
and resource sample windows only.

| Metric | Start | End |
|--------|-------|-----|
| Acknowledgement | Immediately before admission request dispatch | Complete HTTP response or SMPP submit response |
| Submission to provider | Admission request dispatch | Simulator receives complete submit request |
| Provider response completion | Admission request dispatch | Simulator completes sending the successful submit response |
| Receipt forwarding | Simulator starts sending the generated receipt | Harness receives complete forwarded callback or SMPP receipt |

The response-completion boundary is simulator-side send completion, not proof
that Sendium consumed the response. Record this limitation in results.
Configured delivery delay is measured from provider response send completion
to the start of receipt generation; report actual simulator scheduling delay
separately. It is excluded from receipt-forwarding latency.

Publish count, p50/p95/p99, min/max in milliseconds for each distribution.
No samples means null percentiles/min/max, not zero. Record the histogram
algorithm, precision, and overflow count. Overflow makes affected latency
statistics incomplete and the run inconclusive.

## Result envelope

`results.schema.json` defines the artifact envelope. Schema version changes
when interpretation or required fields change. Version 1 includes fully
resolved workload/application/infrastructure settings, provenance, overall
measurements, cohort intervals, event intervals, resources, drain accounting,
diagnostics, and validity reasons.

Application/infrastructure objects preserve effective settings rather than
silently omitting preset values. Record instance IDs/types/architecture/AZ,
database size/placement, JVM arguments, connection/window settings, preset
identity, worker TPS, and artifact digests. Sanitized configuration must not
include credentials. Artifact identifiers may be updated by publication after
execution without changing benchmark measurements.

Runnable reports record exact observed nanoseconds and measurement-start UTC in
metadata, with `resourceFinalizationVersion=1`. Offline `finalize-report` may
replace resource evidence/summaries and resource-scoped validity reasons without
repeating traffic or changing workload counters, windows, latency distributions,
configuration, provenance, or drain outcomes. Reasons carry `scope=workload` or
`scope=resources`; workload/setup failures cannot be removed by resource
enrichment. The command requires an owned new output directory and no AWS access.
Run output also exposes pending receipt acknowledgements at cutoff. Accounting
freezes before intentional shutdown, so cancellations cannot turn unfinished
admissions into observed transport failures outside the measurement window.

## Validity and execution outcome

- `valid`: completed measurement with no detected harness bottleneck or
  accounting/setup failure; does not imply sustainable throughput.
- `inconclusive`: missed generation due to harness limits/scheduling, simulator
  saturation, missing required resource samples, or measurement overflow.
- `failed`: setup/protocol failure or a scenario accounting violation.

Failure takes precedence over inconclusive. Always publish machine-readable
reason codes plus explanations. Setup/accounting failures produce a nonzero
execution status. Inconclusive runs remain downloadable and visibly labeled;
overload rejections and unfinished work alone do not impose performance gates.
If measurement never starts, observation duration is zero, intervals are empty,
and undefined rates/distributions are null. Preserve available diagnostics.
