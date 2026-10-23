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

## Read-only standard RDS collector

`collect_rds_metrics.py` collects standard `AWS/RDS` instance metrics. It is a
standalone collector at this stage; automatic workflow collection and database
report sections are the following integration steps.

```sh
python3 performance/resources/collect_rds_metrics.py \
  --context rds-context.json --output rds-metrics.json
```

The public context identifies the database and the observation's contiguous,
half-open UTC phase windows. The example below includes partial minute buckets:

```json
{
  "database": {
    "identifier": "sendium-perf-run-gh-12345-1",
    "instanceClass": "db.t4g.small",
    "partition": "aws",
    "region": "eu-west-1",
    "accountId": "123456789012",
    "repository": "cytechmobile/sendium",
    "runIdentity": "gh-12345-1"
  },
  "phases": {
    "warmup": {"start": "2026-10-23T12:00:10Z", "end": "2026-10-23T12:01:10Z"},
    "measurement": {"start": "2026-10-23T12:01:10Z", "end": "2026-10-23T12:03:20Z"},
    "drain": {"start": "2026-10-23T12:03:20Z", "end": "2026-10-23T12:03:50Z"}
  }
}
```

Before any metric request, the collector verifies the database identifier, exact
ARN/account/region, instance class, PostgreSQL engine and run/repository ownership
tags. Identity mismatch or unavailable identity produces explicit evidence with
`database.verified: false` and no metric queries. It never reads database secrets
or connects to PostgreSQL. It requires `rds:DescribeDBInstances` and
`rds:ListTagsForResource`, scoped to the bootstrap's run-database ARN prefix and
region, plus the existing `cloudwatch:GetMetricStatistics` permission. Existing
bootstrap installations need the updated diagnostics policy before live use.

| Metrics | Unit | Period | Statistics |
|---|---|---:|---|
| CPU utilization | Percent | 60 s | Average, minimum, maximum, sample count |
| Freeable memory, swap, free storage | Bytes | 60 s | Same |
| Database connections, disk queue depth | Count | 60 s | Same |
| Read/write IOPS | Count/Second | 60 s | Same |
| Read/write throughput | Bytes/Second | 60 s | Same |
| Read/write storage I/O latency | Seconds | 60 s | Same |
| CPU credit balance, surplus credit balance | Count | 300 s | Same |
| CPU credit usage, surplus credits charged | Count | 300 s | Sum |

Credits apply to supported burstable RDS classes. Non-burstable classes explicitly
mark these series not applicable; T2 does not require surplus metrics. An unknown
T-family leaves credit applicability unknown instead of assuming it is absent.

The schema-v1 evidence records identity, collection start, exact phase boundaries,
per-series query boundaries, units/statistics, sorted timestamped datapoints,
attempt history and coverage. Each datapoint timestamp starts a whole CloudWatch
bucket of `periodSeconds`; query boundaries round outward. Phase coverage counts
overlapping buckets, so a bucket crossing phases can appear in more than one
phase's coverage. These are not interpolated one-second samples or exact
phase-only observations. Storage I/O latency is not SQL-query latency, and
freeable memory is not database heap usage.

Collection allows at most three attempts per applicable metric, retries only
incomplete/problematic series, and waits at most 30 seconds between attempts.
A 180-second deadline stops new metric requests; the CLI bounds each in-flight
AWS request to ten seconds. Each metric has a 1,440-bucket budget. Publication
lag can still leave missing data. Good samples survive later failures, while
missing/invalid samples and interrupted/deadline-limited collection remain explicit.
Recovered attempt errors are retained separately from current collection issues.
No missing value becomes zero. No raw AWS error text is published. Partial metric
evidence returns zero from the CLI for later resource-validity classification;
identity or context/output failure returns nonzero.

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
