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
