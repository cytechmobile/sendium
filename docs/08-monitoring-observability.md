# Monitoring And Observability

Sendium includes Prometheus-compatible metrics through the Quarkus Micrometer Prometheus extension. Operators can scrape the Sendium HTTP endpoint with Prometheus and visualize metrics in Grafana.

The dependency is provided by `quarkus-micrometer-registry-prometheus` in `sendium-core/pom.xml`. Because `sendium-app` depends on `sendium-core`, the runnable application includes the metrics endpoint.

## Metrics Endpoint

When Sendium is running, metrics are exposed at:

```text
http://<sendium-host>:8080/q/metrics
```

For a local Docker or development run, verify the endpoint with:

```bash
curl http://localhost:8080/q/metrics
```

The endpoint exposes Quarkus, JVM, HTTP server, and Micrometer runtime metrics. Sendium's PostgreSQL DLR subsystem also registers its selected-backend gauge and storage-operation timers.

## Planned SMS Operations Metrics Contract

> Design contract for [#383](https://github.com/cytechmobile/sendium/issues/383). HTTP/SMPP admission counters and Pending/Selected gauges are implemented as described below; the remaining catalog and collection metadata are planned. Existing runtime and DLR storage-operation metrics remain available.

### Implemented HTTP Admission Counter

`sendium_sms_submissions_total{ingress="http",result="accepted|rejected"}` counts one response decision for each `GET /sendsms` or `POST /api/v1/messages` handled by the REST layer. HTTP 202 is accepted; all other response statuses are rejected, including authentication, validation, unsupported media and capacity failures. Optional trailing slashes are recognized. Both APIs share the same counter and label values; no account, URL or error details become labels.

Counting happens once in a response filter, not in each authentication/resource/error path. It measures HTTP submission decisions, not delivery or a durable count of source records. It includes the existing legacy Kannel enqueue path when embedded without an outbound coordinator. Other routes, unsupported methods (including native GET), OPTIONS and metrics scrapes are excluded. Requests rejected before reaching the REST layer, such as HTTP-server-level limits or connection failures, are not observable here. An accepted source whose HTTP response is never processed by the filter can be missing from this counter; it is not a message ledger.

The counter remains active when `sendium.metrics.storage.enabled=false`.

```promql
# Accepted HTTP submissions per second.
rate(sendium_sms_submissions_total{ingress="http",result="accepted"}[5m])

# Rejected HTTP submissions per second.
rate(sendium_sms_submissions_total{ingress="http",result="rejected"}[5m])
```

### Implemented SMPP Admission Counter

`sendium_sms_submissions_total{ingress="smpp",result="accepted|rejected"}` counts `submit_sm_resp` decisions after successful enqueue to the worker's response executor. `STATUS_OK` is accepted; all other statuses are rejected. This shared boundary includes validation/processor failures, before-insert filter drops, stopped-worker rejection and Pending admission failure. It also includes legacy embedded admission without the coordinator. A newly admitted duplicate multipart ordinal counts as another accepted submission/source; reassembly, publication, cleanup and provider retries do not create new admission responses.

Bind/authentication exchanges, enquire-link, unbind, deliver_sm receipts and generic nacks are not SMS admission decisions. Frames that cannot be decoded into handled submissions, and response-enqueue failures, are not counted. A counted response can subsequently fail transmission: the metric measures the server decision, not receipt of the acknowledgement by the client. Like the HTTP counter, it is not an exact durable ledger of source records.

CDI-managed workers automatically bind to the application registry, independent of the storage metrics switch. Programmatic workers opt in with `bindSubmissionMetrics(MeterRegistry)` before startup. Workers sharing a registry contribute to the same two SMPP series; there is no worker/account/message label.

```promql
# Accepted SMPP submissions per second.
rate(sendium_sms_submissions_total{ingress="smpp",result="accepted"}[5m])

# Combined accepted submission rate across HTTP and SMPP instances.
sum(rate(sendium_sms_submissions_total{result="accepted"}[5m]))
```

### Implemented Pending And Selected Gauges

The standalone memory pipeline exposes `sendium_sms_pending_sources{backend="memory"}` and `sendium_sms_selected_work{state="waiting|routing|retained"}`. Each reads maintained store sizes under the existing store lock, without enumerating records or maintaining another per-message index. Values are live rather than periodic snapshots; separate series can be read across concurrent transitions and are not an atomic cross-store snapshot.

`sendium.metrics.storage.enabled` defaults to `true`. Set it to `false` and restart to omit these gauges; admission and normal processing remain active. Before a store is ready or after it closes, registered gauges return NaN rather than implying a healthy empty store. Embedding applications opt in explicitly with the memory stores' `bindMetrics(MeterRegistry)` methods, once per store/registry ownership lifetime. Importing the core library does not register these metrics automatically.

Only `waiting + routing` represents unassigned Selected capacity. A terminal discard stays counted as taken routing work until Selected completion; the memory store does not record a separate discard phase. `retained` means destination-recorded bookkeeping only. Pending remains occupied until source completion, even after routing; Selected bookkeeping can outlive source deletion during partial cleanup.

### Units And Counting Boundaries

Terminology follows [Outbound Message Storage](14-outbound-storage.md): **Pending, Selected, and Routed are stores**; Dispatch, Handoff, and Cleanup are processing phases, not additional stores.

An **accepted source** is one retained ingress message or multipart part, including a newly admitted source whose multipart ordinal duplicates an existing ordinal. A **selection** is logical routing work, potentially aggregating several sources. A **provider attempt** is one submission request for one provider SMS part, including repeated requests on retry. A **receipt outcome** is a committed final correlation-state transition, not a unique customer message or an incoming receipt packet.

Pending includes sources retained during routing, provider execution, required handoffs, and some cleanup steps. **Do not add Pending to later-stage gauges.** Source counts, selection counts, and provider-part attempt counts are different units, especially for multipart SMS. Provider acceptance is not handset delivery.

The following names use Prometheus exposition spelling. Micrometer counter and timer names must map to these names without manually duplicating its `_total`, `_seconds_count`, or `_seconds_sum` suffixes.

### Minimal Operator Catalog

Keep a metric only when it answers an operator question and suggests an action. The proposed catalog is limited to these six questions:

| Operator question | Measurements | Action when unhealthy |
| :--- | :--- | :--- |
| Are submissions accepted or rejected? | Submissions by ingress and result | Investigate ingress failures or capacity pressure. |
| Is unfinished work growing? | Pending sources | Locate downstream bottlenecks; remember that Pending overlaps later stages. |
| Is routing stuck? | Selected work by state | Check routing rules and processor progress. |
| Is sending falling behind? | Routed work by state and destination | Check paused workers, provider connectivity, sending capacity, or retained cleanup. |
| Is a provider slow or rejecting traffic? | Request attempts, in-flight requests, response timer by result | Investigate provider health or adjust routing; distinguish retries from new work. |
| Are receipts progressing? | Stored receipt states, final outcomes, client-return results | Investigate missing provider receipts or failing HTTP/SMPP returns. |

No dedicated counters for multipart duplicates, suppressed callbacks, or retention deletions, and no separate Handoff/Cleanup metric families are proposed. Their semantics still matter for correctness, but they do not automatically justify another graph. Storage state and retained stage occupancy expose backlog; existing logs support deeper diagnosis. Separate handoff/cleanup occupancy, if required to satisfy #383 beyond this minimal view, must be reviewed explicitly rather than added implicitly.

| Proposed metric | Purpose / operator action | Type / labels | Counting boundary |
| :--- | :--- | :--- | :--- |
| `sendium_sms_submissions_total` | See accepted/rejected traffic per protocol; investigate ingress failures or capacity pressure. | Counter; `ingress`, `result` | One classified SMS submission decision at HTTP/SMPP ingress: `accepted` or `rejected`. Newly admitted duplicate multipart ordinals count as accepted sources and consume Pending capacity. Internal retries are not submissions. |
| `sendium_sms_pending_sources` | Detect growing unfinished work and admission-capacity pressure; locate downstream bottlenecks. | Gauge; `backend` | Unique retained accepted sources; increment only on new admission and release only on successful source deletion. Includes held/incomplete multipart sources and sources in later stages. |
| `sendium_sms_selected_work` | Detect stalled routing or retained selection bookkeeping; check routing rules, processor progress, or cleanup. | Gauge; `state` | Retained Selected records: `waiting` for routing, `routing` while taken and unassigned (including a discard awaiting completion), or `retained` after marking the destination recorded while bookkeeping awaits cleanup. Only waiting plus routing occupies unassigned Selected capacity. Taking does not free that capacity; successful destination recording/retirement does. Remove retained occupancy only after successful Selected completion. Partial publication must not create a second selection. |
| `sendium_sms_routed_work` | Locate destination backlogs; check paused workers, sending capacity, provider progress, or cleanup. | Gauge; `destination`, `state` | All retained Routed records: `waiting` before destination take, `active` after take until routed work is marked terminal, or `terminal` until forget succeeds. Returning unfinished work restores waiting. Active includes Dispatch, provider outcomes, retries, and Handoff; it is not the number of network requests awaiting responses. Terminal records still consume Routed capacity during Cleanup. |
| `sendium_sms_provider_attempts_total` | Graph provider traffic and retry load; investigate rising retries or lost sending progress. | Counter; `provider`, `kind` | One actual invocation of the provider request-send boundary per SMS part, with `kind=initial` or `retry`. Requests generated but never passed to sending are excluded. Includes invocations ending in transport failure, not proof the provider received them. Retry scheduling or handoff retry alone does not increment this counter. |
| `sendium_sms_provider_in_flight` | Identify accumulating unanswered requests; check provider connectivity and response progress. | Gauge; `provider` | Attempt started but not yet completed by its first response, timeout, or local failure. Release before required handoff processing, not at submission-chain completion. |
| `sendium_sms_provider_response_seconds` | See slow, rejecting, or timing-out providers; investigate their health or adjust routing. | Timer; `provider`, `result` | Attempt start through first response/timeout/failure; `result=accepted`, `rejected`, `timeout`, `transport_error`, or `protocol_error`. Excludes later storage/handoff latency. Its `_count` series supplies outcome counts, avoiding a separate completion counter; `_sum` supplies total elapsed seconds. Synthetic failures are not provider rejections. |
| `sendium_dlr_outcomes_total` | Follow final delivery results; investigate rising failures or provider-reported expiry. | Counter; `provider`, `outcome` | Committed final receipt transitions: `delivered`, `failed`, or provider-reported `expired`. Submission rejection and local retention expiry are not handset receipt outcomes. |
| `sendium_dlr_return_attempts_total` | Detect failed client receipt returns; check HTTP endpoints or SMPP client sessions. | Counter; `channel`, `result` | Actual HTTP callback or SMPP receipt send attempts: `success` or `failure` at transport/protocol completion. HTTP success means 2xx; SMPP success means successful acknowledgement. Suppressed callbacks and invalid metadata are not send attempts. A successful external return followed by failed storage cleanup can be retried and counted again. |
| `sendium_dlr_storage_messages` | Find missing provider receipts or growing client-return backlogs, including work retained across restart. | Snapshot gauge; `backend`, `state` | Stored message records in `waiting_provider`, `pending_return`, or `failed_return`. Pending includes claimed/in-flight returns and retry delays. Count message records, not correlation rows or multipart provider IDs. |

HTTP and SMPP use the same admission metric, separated by the bounded `ingress=http|smpp` label rather than different metric names. Both count SMS submission decisions; accepted submissions count sources, not provider parts or protocol housekeeping. Operators can graph each protocol separately or sum across ingress for a combined view.

There is no claim that accepted submissions, selections, provider requests, final correlated outcomes, and client-return attempts form a one-to-one funnel. HTTP requests rejected before recognition as SMS submissions and SMPP bind/enquire/link operations are outside the SMS admission counter. Authentication, parsing, validation, filter, and storage rejections of recognized SMS submissions must have an explicit single ingress outcome. Provider/filter preparation retries which send nothing do not increment request-attempt counters.

Suppressed callbacks are not successful client returns. Retention removal is not a provider-reported expiry or delivery outcome. These distinctions remain counting rules even without dedicated counters. Timer families expose multiple Prometheus series; this catalog minimizes redundant measurements, not merely metric-name count. Histogram buckets are not enabled by default without an identified percentile/alert requirement.

### Idempotency, Restart, And Labels

- Admission counters describe ingress decisions; Pending describes distinct stored sources. Every newly admitted client submission has its own source handle, including duplicate multipart ordinals. For A=part1, B=part1, C=part2 and D=part3, four accepted submissions occupy four Pending slots; A+C+D form one aggregate while B's handle remains retained with the group. All four handles complete together. Only an internal retry of the same retained source ID avoids another admission/slot. Repeated completion callbacks do not create admissions. Aggregation creates no new sources, and group timeout releases received original parts with their duplicate handles for processing rather than declaring delivery failure.
- Update occupancy inside the owner of the successful state transition. A decorator which only observes method return/exception is insufficient where partial publication is possible. Duplicate response handling must pass the existing response-claim guard before recording completion or releasing in-flight occupancy.
- Final receipt counters increment after a committed state transition, not before commit or on every receipt packet. Rollback/deadlock retry and duplicate/unknown receipts do not produce another outcome. Multipart outcomes follow the implemented correlation/aggregation boundary; they must not be advertised as unique customer totals.
- Runtime counters reset on process restart; Prometheus `rate()`/`increase()` handle counter resets but cannot reconstruct events lost between scrapes. These metrics are not an exactly-once ledger. Memory occupancy follows the lifetime of memory work and has no restart durability guarantee.
- Future durable backends initialize occupancy from recovered state without replaying admissions, submissions, or outcomes into event counters. Recovery and cross-store reconciliation must not count retained ownership twice.
- `ingress` is `http` or `smpp`; `channel` is `http` or `smpp`. Backend identity and configured provider/destination identities must be bounded by deployment configuration, not derived from request data. No account labels in this implementation. Enum labels use only the values defined here.
- Never label metrics with gateway/provider message IDs, phone numbers, payloads, callback URLs, arbitrary exception text, or error codes received from external systems. Adding or redefining a common label/state requires a contract review.

### Storage Collection Controls And Availability

Storage configuration (`enabled` is implemented for Pending/Selected; periodic collection settings remain planned):

| Property | Default / behavior |
| :--- | :--- |
| `sendium.metrics.storage.enabled` | `true`; enable applicable storage-state collection. `false` stops monitoring queries/scans, not normal storage processing or event instrumentation. |
| `sendium.metrics.storage.interval` | Positive duration; default to be selected after collection cost validation. Independent of Prometheus scrape interval. |
| `sendium.metrics.storage.timeout` | Positive duration; default to be selected after strategy validation. Bound collection operations, including database resource waits where applicable. |

These settings initially require restart. They apply to storage occupancy collection, including future backend-specific collection; transition-based provider/admission/receipt event metrics continue independently. No database is introduced for memory-only pipeline metrics. Do not start a SQL collector if PostgreSQL receipt storage is not configured. Existing DLR persistence may itself require PostgreSQL; this switch does not alter that requirement or provide another DLR backend.

Proposed collection metadata, labeled by bounded `backend` and `stage` (`pending`, `selected`, `routed`, or `dlr`, as implemented):

- `sendium_storage_collection_enabled`: 1 when collection is enabled for that configured stage, otherwise 0.
- `sendium_storage_collection_available`: 1 only while a complete, sufficiently fresh snapshot is usable; otherwise 0. An unconfigured backend has no corresponding series.
- `sendium_storage_collection_last_success_timestamp_seconds`: Unix time of the last complete successful snapshot; absent before first success. Snapshot age is `time() - sendium_storage_collection_last_success_timestamp_seconds`.

Occupancy must be unavailable (NaN or absent, settled consistently during implementation), **not zero**, when disabled, uninitialized, failed, or stale. Retain the last-success timestamp for diagnosis, but do not continue presenting old occupancy as current. A snapshot is stale after two configured collection intervals without success. Never use PromQL `or vector(0)` to conceal unavailable storage tracking. Collection failure alone does not change readiness or admission.

Scrapes read cached snapshots or constant-cost maintained occupancy, not the entire backlog. At most one collection run per stage/backend is active in an instance. Do not publish partial results as a complete snapshot. Do not run retention or processing as a side effect of collecting metrics. No unbounded historical scans or per-message in-memory tracking solely for observability.

**PostgreSQL decision gate:** exact `COUNT(*)` scales with qualifying rows even with indexes. Before implementation, select and validate deadline-limited indexed collection versus an explicitly approximate/bounded alternative. Deadline-limited exact collection may become unavailable under load; a timeout does not prove bounded rows examined. Transactional summary tables would add schema and write-path overhead and require separate explicit approval. No such machinery is approved by this contract.

Storage occupancy over a shared PostgreSQL store is a global snapshot, whereas process event counters are instance-local. Do not sum identical database snapshots across instances. The implementation must document storage scope and an appropriate single-store aggregation rule before shipping examples for multi-instance deployments.

### Requirements For Future Storage Implementations

Each storage backend issue owns these checks before claiming metrics support:

1. Preserve common units, states, transition boundaries, and bounded labels; document unsupported measurements explicitly.
2. Honor storage collection controls and availability/freshness behavior. Supply maintained occupancy or demonstrate bounded collection cost; do not add per-scrape full scans.
3. Test admission, partial publication, take/release, retries, duplicate completion, handoff failure, cleanup failure, and close as applicable.
4. Test durable recovery and reconciliation: rebuild occupancy, avoid counting recovery as new traffic, and retain failed cleanup occupancy until its actual release boundary.
5. Demonstrate disabled/failed/timed-out collection does not affect message-processing semantics, and unknown state does not appear empty.
6. Document per-process versus shared-storage scope, restart limitations, performance evidence, and any added backend-specific diagnostics in this guide.

File-backed and bounded-memory receipt storage remain owned by their respective issues under [#337](https://github.com/cytechmobile/sendium/issues/337). This contract does not implement them or require a generic metrics plugin framework.

## Prometheus Configuration

Create a `prometheus.yml` file in your monitoring deployment directory.

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets:
          - localhost:9090

  - job_name: sendium
    metrics_path: /q/metrics
    scrape_interval: 15s
    static_configs:
      - targets:
          - sendium.example.com:8080
```

Use the target that matches where Prometheus runs:

| Prometheus location | Sendium target example |
| :--- | :--- |
| Prometheus on the same host as Sendium | `localhost:8080` |
| Prometheus in Docker, Sendium on the host | `host.docker.internal:8080` on Docker Desktop, or the host IP on Linux |
| Prometheus and Sendium in the same Docker network | `sendium:8080` |
| Prometheus on another server | `<sendium-hostname-or-ip>:8080` |

## Deploy Prometheus With Docker

Create `prometheus.sh` in the same directory as `prometheus.yml`.

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "${SCRIPT_DIRECTORY}/prometheus-data"

docker rm -fv sendium-metrics-prometheus 2>/dev/null || true
docker run -d \
  --name sendium-metrics-prometheus \
  -p 9090:9090 \
  -v "${SCRIPT_DIRECTORY}/prometheus-data:/prometheus" \
  -v "${SCRIPT_DIRECTORY}/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  prom/prometheus \
  --config.file=/etc/prometheus/prometheus.yml
```

Run it with:

```bash
chmod +x prometheus.sh
./prometheus.sh
```

Prometheus will be available at:

```text
http://localhost:9090
```

In Prometheus, open **Status > Targets** and confirm that the `sendium` target is `UP`.

## Deploy Grafana With Docker

Create `grafana.sh` in the same directory.

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "${SCRIPT_DIRECTORY}/grafana-data"

docker rm -fv sendium-metrics-grafana 2>/dev/null || true
docker run -d \
  --name sendium-metrics-grafana \
  -p 3000:3000 \
  -v "${SCRIPT_DIRECTORY}/grafana-data:/var/lib/grafana" \
  grafana/grafana
```

Run it with:

```bash
chmod +x grafana.sh
./grafana.sh
```

Grafana will be available at:

```text
http://localhost:3000
```

The default login is usually `admin` / `admin`. Grafana will prompt you to change the password on first login.

## Add Prometheus To Grafana

In Grafana:

1. Go to **Connections > Data sources**.
2. Add a **Prometheus** data source.
3. Set the Prometheus server URL.
4. Click **Save & test**.

Use the URL that matches where Grafana runs:

| Grafana location | Prometheus URL example |
| :--- | :--- |
| Grafana on the same host as Prometheus | `http://localhost:9090` |
| Grafana in Docker, Prometheus published on host port 9090 | `http://host.docker.internal:9090` on Docker Desktop, or the host IP on Linux |
| Grafana and Prometheus in the same Docker network | `http://sendium-metrics-prometheus:9090` |

## Useful Checks

Check the Sendium metrics endpoint directly:

```bash
curl http://localhost:8080/q/metrics
```

Check Prometheus targets:

```text
http://localhost:9090/targets
```

Check Grafana:

```text
http://localhost:3000
```

## Troubleshooting

| Problem | Check |
| :--- | :--- |
| `/q/metrics` returns 404 | Confirm the application was built with `quarkus-micrometer-registry-prometheus` on the runtime classpath. |
| Prometheus target is `DOWN` | Check the target hostname, port `8080`, firewall rules, and whether Prometheus is running inside Docker. |
| Prometheus can scrape itself but not Sendium | Use `host.docker.internal:8080`, the host IP, or a shared Docker network target instead of `localhost:8080`. |
| Grafana cannot connect to Prometheus | Use the Prometheus URL reachable from the Grafana container, not necessarily the URL reachable from your browser. |

## Related Documentation

* [Docker Deployment](02-docker-deployment.md)
* [Configuration Reference](09-configuration-reference.md)
* [Troubleshooting](10-troubleshooting.md)
* [Documentation Map](DocumentationMap.md)
