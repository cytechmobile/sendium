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
