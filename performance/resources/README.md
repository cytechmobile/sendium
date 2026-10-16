# Performance resource evidence

These standard-library Python helpers collect evidence without giving the load
JVM AWS credentials. `sample_process.py` runs on each private Linux EC2 instance;
`collect_cpu_credits.py` runs in protected workflow orchestration after measurement.
The harness CLI wires local orchestration; Task 4.5 deploys/controls remote agents
through Systems Manager. No public monitoring endpoint is required.

## Linux process and host sampler

Run once for Sendium's JVM PID on its host and once for the harness JVM PID on its
host. Start before warm-up and retain a final sample after measurement so CPU
intervals cover the entire observation window. For example:

```sh
python3 performance/resources/sample_process.py \
  --pid "$SENDIUM_PID" \
  --duration-seconds 960 \
  --interval-seconds 1 \
  --max-samples 4096 \
  --output sendium-resources.jsonl
```

The output parent must exist and the file must be new. `--output -` streams JSONL
to stdout. The explicit sample budget includes baseline and final samples;
settings exceeding it fail rather than collecting an unbounded history. Defaults
are one-second sampling and 4096 samples per source. Run directories own generated
files; do not commit them.
SIGTERM requests a graceful stop after the next complete interval, allowing the
runner to retain a final CPU observation rather than truncate its window.

| Field | Meaning |
|-------|---------|
| `cpuPercent` | Process user+system CPU delta divided by monotonic elapsed time. 100% is one logical core; a multithreaded JVM can exceed 100%. |
| `hostCpuPercent` | Busy `/proc/stat` ticks divided by aggregate host ticks. Guest ticks are not counted twice; idle includes I/O wait. 100% is total host capacity. |
| `rssBytes` | Resident process pages times system page size; process RSS, not Java heap size. |
| `hostMemoryUsedBytes` | `MemTotal - MemAvailable`, including other processes. |
| `hostMemoryTotalBytes`, `cpuCount` | Host capacity context. |
| `pid`, `processStartTicks` | Target identity; PID reuse aborts sampling. |
| `timestamp`, `intervalStartTimestamp` | UTC boundaries for report attribution. Adjacent intervals share the exact serialized boundary. |
| `intervalSeconds` | Actual monotonic interval used for CPU calculation. |

Baseline CPU is null, not fabricated zero utilization. Both instances must have
synchronized UTC clocks, with clock configuration recorded in the infrastructure
preset. Workload latency still uses the harness's single monotonic clock. Sampler
overhead is included in host utilization and is not subtracted; record sampling
settings when deploying agents.

CPU summaries weight each sample by overlap with the measurement/minute window.
Missing spans are not filled with zero. Memory peaks use snapshots inside the
half-open window. Raw samples remain available in `results.json`.

## Read-only AWS CPU-credit collector

Run under the protected GitHub environment's OIDC-authenticated read-only
diagnostics role, not the application/harness instance role:

```sh
python3 performance/resources/collect_cpu_credits.py \
  --region "$AWS_REGION" \
  --sendium-instance-id "$SENDIUM_INSTANCE_ID" \
  --harness-instance-id "$HARNESS_INSTANCE_ID" \
  --start-time "$MEASUREMENT_START_UTC" \
  --end-time "$MEASUREMENT_END_UTC" \
  --output cpu-credits.json
```

Requires AWS CLI v2 and these read-only permissions, to be included in bootstrap
CloudFormation:

- `ec2:DescribeInstances`
- `ec2:DescribeInstanceTypes`
- `ec2:DescribeInstanceCreditSpecifications`
- `cloudwatch:GetMetricStatistics`

AWS instance-type metadata establishes burstability rather than a guessed type
prefix. Record Standard/Unlimited mode as returned when diagnostics run;
non-burstable instances are `not-applicable`. IDs/types must match effective run
configuration. The report also checks a preset's explicit `creditMode` if present.

CloudWatch credit metrics use 300-second periods; query boundaries round outward.
Record balance/surplus balance averages and usage/surplus charged sums without
replacing absent data with zero. Data may lag; at least one balance observation
overlapping measurement is required for a burstable instance. This is observed
credit state, not proof of continuous coverage or long-term sustainability. The
explicit API budget is 1440 points per metric. Partial metrics survive later API
failures and collection issues remain visible.

The versioned envelope contains query windows and per-instance source, identity,
burstability, mode, samples, and collection issues. Errors are sanitized; AWS
stderr/credentials are not copied into it. Global collection/output failures
return nonzero; per-instance issues are preserved for classification.

## Java import, summary, and validity

`ResourceEvidence.load` strictly validates shape, values, and byte/sample bounds.
`loadAvailable` retains good sources when another artifact is missing/invalid and
records sanitized issues. Construct the UTC resource window from measurement
start UTC plus observed monotonic duration. Supply expected PIDs through
`Context.infrastructure.<source>.processId`. `ResultReport.build` accepts evidence,
that window, and typed lifecycle/adapter observations. CPU/memory summaries appear
overall and per minute in JSON/Markdown. Its overload without evidence remains
inconclusive.

| Status | Examples | Execution exit code |
|--------|----------|--------------------:|
| `valid` | Requested targets supplied and required evidence/correctness checks complete. High utilization, expected overload rejection, or unfinished accepted work alone is not failure. | 0 |
| `inconclusive` | Harness scheduling/capacity limits, resource gaps, missing credit state, partial measurement, ambiguous transport/correlation coverage, or histogram overflow. | 0 |
| `failed` | Setup/protocol/authentication failure, accounting violation, excess targets, negative backlog, or mismatched process/instance/window metadata. Failure takes precedence. | 1 |

Pass diagnostics from `RunValidity.httpAdmission`, `smppAdmission`, `provider`, and
`httpReceipts`, plus explicit setup/lifecycle reasons. Expected 503/429 and SMPP
throttled/queue-full responses remain ordinary rejections. There are no CPU,
memory, throughput, latency, rejection-percentage, or unfinished-work performance
thresholds. A valid observation is not a finding of sustainable sending capacity.

## Verification

```sh
python -m unittest discover -s performance/resources -p 'test_*.py'
./mvnw -Pperformance -pl performance/harness verify
```

Python tests cover `/proc` parsing, timing/denominators, budgets/PID reuse, AWS
metadata/modes, coarse metric windows, missing values, and partial failures.
Java tests cover bounded/partial import, weighted intervals, coverage, identities,
status precedence, diagnostics, and report serialization. The agent was also
exercised against a live busy process in Linux Docker. AWS API behavior is mocked
here; real-account collection remains part of Task 6.1's AWS lifecycle validation.
