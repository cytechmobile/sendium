# Sendium performance tests

These tests measure how Sendium handles synthetic messages and delivery reports
(DLRs). They exercise the real application and PostgreSQL database, using a
simulated SMS provider. **No real SMS messages are sent.**

## How it works

The test tool submits messages to Sendium through HTTP or SMPP. Sendium routes and
forwards them to the tool's simulated SMPP provider. The provider returns
submission responses and, when requested, successful delivery receipts after
configurable delays. Sendium uses PostgreSQL to persist and correlate DLR data,
then forwards the receipts back to the test tool through HTTP or SMPP.

The code calls the complete test tool a **test harness**. The simulated provider
is only one part of it; the other parts generate traffic, receive DLRs and record
measurements.

```mermaid
flowchart LR
    subgraph Tool["Test tool — one EC2 instance"]
        Client["Traffic generator and test client"]
        Provider["Simulated SMS provider"]
        Receiver["DLR receiver"]
    end
    Sendium["Sendium application"]
    Database[("PostgreSQL: DLR storage")]

    Client -->|HTTP or SMPP messages| Sendium
    Sendium -->|Outgoing SMPP| Provider
    Provider -->|Responses and delivery receipts| Sendium
    Sendium <-->|Store and correlate DLR data| Database
    Sendium -->|Forwarded delivery receipts| Receiver
```

Each run has three traffic phases:

- **Warm-up:** exercise the application before collecting measured message totals.
- **Measurement:** offer traffic at the requested average rate and record outcomes,
  timing and resource usage. Brief timing variation and recovery bursts are visible
  in the results; responses do not pace new submissions.
- **Drain:** stop generating new messages and wait, up to a limit, for submitted
  messages and receipts to finish. Unfinished work remains visible in the report.

## Components

| Component | Responsibility |
|---|---|
| **Test-tool EC2** | Generates traffic, simulates the provider, receives forwarded DLRs, and records message counts and timing. These roles share one process. |
| **Sendium EC2** | Runs the selected application version, separately from the test tool. |
| **RDS PostgreSQL** | Persists delivery-report data and provider-message correlations. |
| **Private networking** | Connects the nodes, database and AWS management services without public instance IPs. |
| **GitHub Actions and AWS orchestration** | Build the exact application/test-tool revisions, provision resources, execute through Systems Manager, collect evidence, clean up and publish reports. |
| **Expiry service** | Independently attempts cleanup of expired or abandoned runs if normal cleanup did not complete. |

Only provisioning/execution requires environment approval from one member of the
**Maintainers** team; build verification is automatic. Evidence collection
and cleanup proceed automatically afterward using separate read-only and
deletion-only roles; cleanup cannot create new benchmark resources.

Run-owned EC2, RDS and networking resources are temporary. The bootstrap roles,
artifact bucket and expiry service persist between runs. The
[local rehearsal](local/README.md) runs the traffic against real Sendium and
PostgreSQL in containers, while explicitly modeling AWS control-plane operations.

## Workflow parameters

The [Performance Tests Action](../.github/workflows/performance-tests.yml) exposes
the following inputs. Each run tests one gateway and one ingress protocol.

| Parameter | Default | What it controls |
|---|---|---|
| `benchmark_target` | `sendium` | Selects Sendium or Kannel. Kannel supports HTTP ingress and uses its C runtime with PostgreSQL-backed DLR storage. |
| `application_runtime` | `jvm` | Runs Sendium as JVM Java or a compiled `native` executable. Leave `jvm` for Kannel, which uses C; the test tool remains JVM-based. |
| `ingress_mode` | `http` | Sendium supports `http` or `smpp`; Kannel supports HTTP ingress only. Both gateways connect to the provider over SMPP. |
| `sendium_instance_type` | `t4g.small` | EC2 instance type for the gateway, whether Sendium or Kannel. |
| `harness_instance_type` | `t4g.small` | EC2 instance type for the test tool, including the generator, simulated provider and DLR receiver. |
| `offered_rate` | `30` | Average messages per second the generator attempts to submit to the selected gateway; positive fractional rates are supported. |
| `worker_tps` | `0` | Gateway outgoing rate cap. `0` means unlimited; a positive integer sets the cap. This is separate from the generator's offered rate. |
| `warmup_seconds` | `120` | Warm-up duration before measured traffic: 0–600 seconds. |
| `measurement_seconds` | `600` | Duration of measured traffic: 1–3,600 seconds. |
| `drain_seconds` | `120` | Maximum time to wait for outstanding messages and DLRs after generation stops: 0–600 seconds. |
| `dlr_enabled` | `true` | Whether messages request delivery receipts and the test checks their return. Keep enabled for DLR-inclusive performance comparisons. |
| `provider_response_delay_ms` | `5` | Intentional delay before the simulated provider sends its submission response: 0–10,000 milliseconds. |
| `provider_dlr_delay_ms` | `1000` | Intentional delay from provider response completion to simulated DLR generation: 0–60,000 milliseconds. It is separate from Sendium's receipt-forwarding time. |

The **Use workflow from** branch selector determines the application revision
being tested. The maintained test tool and controller are pinned separately from
the default branch. Instance compatibility and regional availability are checked
before provisioning. For Kannel, upstream archive identity replaces the application
commit and the packaging code comes from the maintained controller revision.
Merge the controller/workflow changes to `main` before the first Kannel dispatch;
see the [Kannel dispatch instructions](workflow/README.md#running-kannel-after-merge).

Native mode builds both Linux architectures using a digest-pinned Mandrel compiler
and runs the matching executable directly on the Sendium instance. Reports record
the runtime, native compiler/build identity and separate native heap settings.
A Java runtime is retained on that instance for configuration generation.

Database sizing, JVM/native heap, protocol windows and networking remain defined by the
[shared preset](presets/README.md); they are not Action inputs. AWS region and role
configuration come from the protected GitHub environment.

## Metrics collected

### Did the test tool supply the requested load?

These measurements describe the **test tool**, not Sendium's outgoing sending
rate or internal queues.

| Measurement | Meaning |
|---|---|
| **Requested versus attempted input rate** | The message rate we asked the generator to produce versus the number of attempts started in its test client per second. An attempt does not prove arrival or acceptance at Sendium. |
| **Planned messages not attempted** | Scheduling misses are targets left unattempted when generation ends or is stopped. Capacity misses occur when the tool cannot start an attempt because its outstanding-request or message-tracking limit is full. Neither is a rejection returned by Sendium. |
| **Generator timing delay** | How late an attempt starts compared with its nominal planned time. For example, planned at 1.000 seconds and started at 1.030 seconds means 30 ms late. This measures timing variation, not network or Sendium processing time. |
| **Attempt starts and send-call starts each second** | Counts messages handed to the test client and messages for which that client begins its transport call. Differences reveal local preparation or dispatch waiting. They show short-term traffic variation, not confirmed arrivals at Sendium. |
| **Test-client waiting time before sending** | Time from starting an attempt in the test client until beginning its transport call, including preparation and local dispatch-queue waiting. It excludes network travel and Sendium's queues. |

The nominal planned times are a timing reference. Briefly late attempts may
recover in short bursts; they are not automatically discarded.

### How did Sendium handle that load?

| Measurement | Meaning |
|---|---|
| **Submission outcomes** | How many requests Sendium accepted or rejected, and how many attempts failed or timed out without a definite acceptance/rejection response. |
| **Provider and DLR outcomes** | Messages observed by the simulated provider, provider responses completed, receipts generated and receipts forwarded back by Sendium. Missing receipts, duplicates and unfinished work are counted separately. |
| **Completions during each minute** | Messages arriving at the provider, provider responses completed and forwarded DLRs received during that minute. Counts divided by the observed time give messages per second. |
| **Outstanding work at each minute's end** | Messages still awaiting Sendium's acknowledgement, accepted messages awaiting provider completion, and accepted messages awaiting a forwarded DLR. These counts come from observed progress; they are **not direct measurements of Sendium's internal queue sizes**. |
| **Time to finish remaining work** | After generation stops, how long messages and receipts take to finish, whether the drain time limit is reached, and how much remains unfinished. |
| **Resource usage** | CPU and resident memory (RSS) for Sendium and the test tool, host CPU/memory, and available EC2 CPU-credit observations. This helps distinguish application pressure from test-tool pressure. |
| **RDS resources** | Database CPU, freeable memory, connections, free storage, read/write I/O and latency, queue depth, and applicable CPU credits. CloudWatch coverage and phase boundaries remain visible; storage latency is not SQL-query latency. |

For example, a minute can contain 5,400 provider completions while 1,200 accepted
messages are still awaiting completion at that minute's end. The first number is
output during the minute; the second is outstanding work at a point in time.

Processing times are reported with **p50/p95/p99 percentiles**:

| Timing | Boundary measured |
|---|---|
| **Admission acknowledgement** | Test-client transport start to Sendium's acceptance/rejection response. |
| **Submission to provider arrival** | Test-client transport start to the simulated provider observing the message. |
| **Provider response completion** | Test-client transport start to the simulated provider completing its response write. This does not prove Sendium consumed the response. |
| **Simulated receipt delay** | Provider response completion to the simulator generating the DLR. This is an intentional, configured delay. |
| **Receipt forwarding** | Simulator DLR generation to receipt of the DLR forwarded by Sendium. The simulated receipt delay above is excluded. |

For example, p99 of 2 seconds means about 99% of the recorded samples completed
within 2 seconds; it is not the average or maximum. Timings must be read alongside
unfinished-message counts because unfinished work has no completion sample yet.

**Acceptance is not delivery, and eventual completion during drain is not sustained
measurement-period throughput.** Read completion rates, latency and backlog
together. A valid report means the observations passed the accounting/evidence
checks; it does not imply a performance target was met or that all work completed.

Reports also identify inconsistent message accounting and missing or coarse
measurement evidence, rather than silently treating missing data as zero.

Detailed definitions are in the [harness documentation](harness/README.md) and
[result contracts](contracts/README.md). The [reporting documentation](workflow/REPORTING.md)
explains evidence collection, publication and cleanup.

## Kannel comparison work

The optional Kannel target is being developed locally first. Its
[build baseline, local benchmark, and protocol findings](kannel/README.md)
describe the verified x86-64/ARM64 build, HTTP/DLR accounting, combined gateway
sampling, and full local benchmark lifecycle. Run it with
`python performance/local/rehearse.py --benchmark-target kannel --output local-load-results`.
Sendium remains the local launcher's default. AWS/workflow target selection is the
next phase. Kannel supports HTTP ingress with SMPP forwarding and HTTP delivery
reports; SMPP ingress is deferred.

## Permanent results archive

After publication, a separate job archives readable results, resource evidence
(including RDS metrics), diagnostics, and a compressed bundle in a dedicated
private S3 bucket. JVM/native and HTTP/SMPP runs share the same archive format;
failed and inconclusive observations retain their original status. The job summary
links to a permanent manifest with provenance and checksums. GitHub artifacts remain
temporary convenience copies. See [archive setup, format and retrieval](workflow/ARCHIVE.md)
for the required bootstrap update and `AWS_ARCHIVE_ROLE_ARN` configuration.
