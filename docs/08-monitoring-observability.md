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

## SMS Operations Metrics

Sendium provides basic live operational metrics for [#383](https://github.com/cytechmobile/sendium/issues/383): HTTP/SMPP admissions, Pending/Selected/Routed occupancy, coordinated provider requests, receipt events and optional bounded PostgreSQL receipt snapshots. These are useful built-in monitoring signals, not a reporting platform or durable message ledger. Existing runtime and DLR storage-operation metrics remain available.

### Start With Six Operator Questions

After configuring Prometheus and adding its data source to Grafana below, create time-series panels using the queries in [Example Panels](#example-panels). Start with traffic, Pending, Selected/Routed, provider activity and receipt progress; no custom administration UI or dashboard provisioning is required.

- Give gauges a count unit and rates a per-second unit; do not label provider request counts as unique customer messages.
- Use separate series/legends for `ingress`, `state`, `destination`, `provider` or `channel`, as applicable.
- Keep Pending separate from Selected/Routed. The same sources remain owned across those stages; they are not three independent queues to add together.
- Show unavailable snapshots as **No data/unavailable**, not zero. Pair receipt occupancy with its collection-availability panel.
- Treat the sample alerts as optional starting points. Tune thresholds for your configured capacities, normal traffic and expected provider/receipt delays.

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

### Implemented Routed And Provider Request Metrics

`sendium_sms_routed_work{destination="<configured worker>",state="waiting|active|terminal"}` tracks retained logical selections. Waiting ends when work is taken for Dispatch, not necessarily when a network request is sent. Active includes preparation queues, rate waits, replacement attempts and required handoffs. Terminal begins when Routed completion succeeds and ends only when forget succeeds. Successful source deletion alone does not release Routed capacity. Counts are maintained at store transitions, and scrapes do not enumerate work. Destination series are created on first assignment and remain zero after drain; closed stores return NaN. The storage metrics off switch omits these gauges. Programmatic memory Routed stores must bind metrics once before opening.

For coordinated SMPP provider requests, the implemented families are:

- `sendium_sms_provider_attempts_total{provider,kind="initial|retry"}`: one send-boundary invocation per provider SMS part. Replacement work is marked explicitly by the dispatcher, including replacements sent to a different provider; do not infer it from source admission or mutable message retry counters.
- `sendium_sms_provider_in_flight{provider}`: started requests without a claimed response/timeout/local failure. Requests generated but never sent, including unsent multipart parts after a first-send failure, do not occupy this gauge.
- `sendium_sms_provider_response_seconds_count{provider,result}` and `sendium_sms_provider_response_seconds_sum{provider,result}`: count and total latency for the first result. Results are `accepted`, `rejected`, `timeout`, `transport_error`, and `protocol_error`. The timer also exposes its implementation's standard maximum series; histogram buckets are not enabled here.

Timing starts immediately before the coordinated send-boundary invocation, after rate limiting and worker preparation. It includes handler/session selection and any time waiting within the send call, not just network round-trip time. Expiry callbacks and synchronous SMPP timeout exceptions are `timeout`; non-submit responses or recoverable malformed responses are `protocol_error`. A genuine negative submit response is `rejected`. These classifications do not change the existing failure/retry policy.

Routed occupancy keeps the parent's originally recorded `destination`. Provider request metrics use the actual sending worker's `provider` identity, including when routing chooses a different worker for a replacement attempt. A retry does not transfer the parent record or create another Pending source.

For one single-source assigned selection, interrupted completion has these occupancies. These are overlapping ownership counts, not separate messages:

| Completion waiting/failure boundary | Pending sources | Selected retained | Routed active | Routed terminal |
| :--- | ---: | ---: | ---: | ---: |
| Required handoff pending, failed or cancelled | 1 | 1 | 1 | 0 |
| Handoff accepted, Routed completion fails | 1 | 1 | 1 | 0 |
| Routed completed, Pending deletion fails | 1 | 1 | 0 | 1 |
| Pending deleted, Selected cleanup fails | 0 | 1 | 0 | 1 |
| Selected removed, Routed forget fails | 0 | 0 | 0 | 1 |
| All cleanup succeeds | 0 | 0 | 0 | 0 |

Thus zero Pending does not prove all cleanup is finished. Persistent Selected bookkeeping or terminal Routed occupancy warrants checking retained cleanup failures. Cleanup-only retry finishes remaining steps without sending again or increasing provider attempt/response counts. Terminal routing discards have no Routed record; they remain in Selected `routing` until Selected completion, including during a failed handoff or cleanup retry.

The existing per-part response claim prevents repeated callbacks from decrementing in-flight or counting/timing an outcome twice. Release and timing happen before tracking/storage handoffs. Retrying a failed handoff adds neither a provider request nor another response measurement. A transport send failure counts an attempt even when it is uncertain whether any bytes reached the provider. Provider acceptance is still not handset delivery.

Provider series are created on first send. CDI-managed workers share a meter holder by configured full worker name, preserving in-flight state across worker replacement in the same application. Programmatic workers opt in before startup with `bindProviderMetrics(ProviderRequestMetrics)` and share one holder per registry. The existing overloads treat direct embedded submissions as initial attempts; callers scheduling replacements can use the overload with the explicit retry flag. There are no per-message metrics indexes. These request metrics currently cover the coordinated standalone provider path, not the legacy worker `doMessage` path. They remain active when storage-state metrics are disabled.

```promql
# Waiting logical Routed work per configured destination.
sum by (destination) (sendium_sms_routed_work{state="waiting"})

# Retry provider requests per second (not unique accepted sources).
sum by (provider) (rate(sendium_sms_provider_attempts_total{kind="retry"}[5m]))

# Rejected provider requests per second, using the response timer's count.
sum by (provider) (rate(sendium_sms_provider_response_seconds_count{result="rejected"}[5m]))

# Mean time until provider attempt completion; no data when no attempts complete.
sum by (provider) (rate(sendium_sms_provider_response_seconds_sum[5m]))
/
sum by (provider) (rate(sendium_sms_provider_response_seconds_count[5m]))
```

### Implemented Receipt Event Counters

`sendium_dlr_outcomes_total{provider,outcome="delivered|failed|expired"}` counts tracked final provider receipts only when the managed PostgreSQL storage boundary returns a newly committed transition. This covers synchronous resolution and batched processing. A duplicate/unknown receipt returning no transition, failed transaction, admission of a correlation, or provider submission rejection does not increment it. Provider-reported expiry is `expired`; local retention deletion is not a receipt outcome. Delivered includes the existing delivered/seen mappings; other terminal failures use `failed`. The unit is a resolved correlation message record, following existing multipart aggregation, not necessarily one unique customer SMS.

`sendium_dlr_return_attempts_total{channel="http|smpp",result="success|failure"}` measures external receipt-return attempts separately:

| Channel | Attempt / success boundary | Failure boundary | Excluded |
| :--- | :--- | :--- | :--- |
| HTTP | Invocation of the callback HTTP client; a 2xx response is success | Non-2xx response, timeout, transport exception or interruption after the send invocation starts | Mask-suppressed callbacks, invalid callback metadata, claim failures and interruption before invoking the client |
| SMPP | First `deliver_sm` send invocation for a logical receipt batch; success requires successful acknowledgements of all distinct parts | First failed send, timeout, negative/malformed response or closed session ending a started batch | Batches abandoned before any send, duplicate callbacks, late callbacks from a terminal batch and non-receipt traffic |

An SMPP multipart receipt return counts once per logical batch attempt, not once per part. A partially sent batch that fails counts one failure. Generating or queueing parts alone does not establish a send attempt. HTTP and SMPP successes are counted before persistent completion cleanup: a successful external delivery followed by a storage failure remains a success measurement, not a failed return. A later actual resend is another attempt, potentially another success. These are process-local activity counters, not exactly-once customer totals or evidence that pending state was successfully removed.

Receipt counters are independent of `sendium.metrics.storage.enabled`. They cover the existing managed PostgreSQL receipt lifecycle and HTTP/SMPP return paths; raw storage implementations used directly outside the managed boundary do not automatically emit outcome counters. CDI binds transport meters automatically. Programmatic HTTP forwarders opt in with `bindMetrics(MeterRegistry)` and SMPP server workers with `bindReceiptMetrics(MeterRegistry)` before startup. New backends must preserve the committed-transition boundary rather than count incoming receipt packets.

```promql
# Final tracked receipt results per second by provider and outcome.
sum by (provider, outcome) (rate(sendium_dlr_outcomes_total[5m]))

# Failed client receipt-return attempts per second by transport.
sum by (channel) (rate(sendium_dlr_return_attempts_total{result="failure"}[5m]))
```

### Units And Counting Boundaries

Terminology follows [Outbound Message Storage](14-outbound-storage.md): **Pending, Selected, and Routed are stores**; Dispatch, Handoff, and Cleanup are processing phases, not additional stores.

An **accepted source** is one retained ingress message or multipart part, including a newly admitted source whose multipart ordinal duplicates an existing ordinal. A **selection** is logical routing work, potentially aggregating several sources. A **provider attempt** is one submission request for one provider SMS part, including repeated requests on retry. A **receipt outcome** is a committed final correlation-state transition, not a unique customer message or an incoming receipt packet.

Pending includes sources retained during routing, provider execution, required handoffs, and some cleanup steps. **Do not add Pending to later-stage gauges.** Source counts, selection counts, and provider-part attempt counts are different units, especially for multipart SMS. Provider acceptance is not handset delivery.

The following names use Prometheus exposition spelling. Micrometer counter and timer names must map to these names without manually duplicating its `_total`, `_seconds_count`, or `_seconds_sum` suffixes.

### Minimal Operator Catalog

Each metric answers an operator question and suggests an action. The catalog is limited to these six questions:

| Operator question | Measurements | Action when unhealthy |
| :--- | :--- | :--- |
| Are submissions accepted or rejected? | Submissions by ingress and result | Investigate ingress failures or capacity pressure. |
| Is unfinished work growing? | Pending sources | Locate downstream bottlenecks; remember that Pending overlaps later stages. |
| Is routing stuck? | Selected work by state | Check routing rules and processor progress. |
| Is sending falling behind? | Routed work by state and destination | Check paused workers, provider connectivity, sending capacity, or retained cleanup. |
| Is a provider slow or rejecting traffic? | Request attempts, in-flight requests, response timer by result | Investigate provider health or adjust routing; distinguish retries from new work. |
| Are receipts progressing? | Stored receipt states, final outcomes, client-return results | Investigate missing provider receipts or failing HTTP/SMPP returns. |

There are no dedicated counters for multipart duplicates, suppressed callbacks or retention deletions, and no separate Handoff/Cleanup metric families. Their semantics still matter for correctness, but they do not automatically justify another graph. Storage state and retained stage occupancy expose backlog; existing logs support deeper diagnosis. The documented states do not provide separate handoff/cleanup queue totals.

| Metric | Purpose / operator action | Type / labels | Counting boundary |
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

Storage configuration (changes require restart):

| Property | Default / behavior |
| :--- | :--- |
| `sendium.metrics.storage.enabled` | `true`; enable applicable storage-state collection. `false` stops monitoring queries/scans, not normal storage processing or event instrumentation. |
| `sendium.metrics.storage.interval` | `60s`; PostgreSQL snapshot refresh interval, at least 1 second. Independent of Prometheus scrape interval; memory gauges stay live. |
| `sendium.metrics.storage.timeout` | `2s`; positive whole-second JDBC query timeout for the snapshot statement. Does not replace connection acquisition or network timeouts. |
| `sendium.metrics.storage.max-rows` | `100000`; maximum stored receipt records for a complete snapshot, from 1 to 2147483646. Above budget, publish unavailable rather than partial counts. |

These settings initially require restart. They apply to storage occupancy collection, including future backend-specific collection; transition-based provider/admission/receipt event metrics continue independently. No database is introduced for memory-only pipeline metrics. Do not start a SQL collector if PostgreSQL receipt storage is not configured. Existing DLR persistence may itself require PostgreSQL; this switch does not alter that requirement or provide another DLR backend.

PostgreSQL snapshot collection metadata is labeled `backend="postgresql",stage="dlr"`. No SQL collector or corresponding metadata is registered when the DLR persistence subsystem is not configured. Memory gauges are live store counts and do not need periodic-snapshot metadata.

- `sendium_storage_collection_enabled`: 1 when collection is enabled for that configured stage, otherwise 0.
- `sendium_storage_collection_available`: 1 only while a complete, sufficiently fresh snapshot is usable; otherwise 0. An unconfigured backend has no corresponding series.
- `sendium_storage_collection_last_success_timestamp_seconds`: Unix time of the last complete successful snapshot; NaN before first success. Snapshot age is `time() - sendium_storage_collection_last_success_timestamp_seconds`.

PostgreSQL receipt occupancy is NaN, **not zero**, when disabled, uninitialized, over budget, failed or stale. The three snapshot states are `waiting_provider`, `pending_return` (including claims, in-flight returns and retry delays), and `failed_return`. An empty successfully read store has three zeros with availability 1. Retain the last-success timestamp for diagnosis, but do not continue presenting old occupancy as current. A snapshot is stale after two configured collection intervals without success. Never use PromQL `or vector(0)` to conceal unavailable storage tracking. Collection failure alone does not change readiness or admission.

Scrapes read cached snapshots or constant-cost maintained occupancy, not the entire backlog. At most one collection run per stage/backend is active in an instance. Do not publish partial results as a complete snapshot. Do not run retention or processing as a side effect of collecting metrics. No unbounded historical scans or per-message in-memory tracking solely for observability.

The collector starts shortly after application startup and reads only `delivery_status`, using one SQL statement with `LIMIT max-rows + 1`. The extra row detects budget overflow. There is no global count/group/sort, retention cleanup or schema change. Counts are exact for that statement's database snapshot only when all records fit. Overlapping runs are skipped; Prometheus scrapes only read the cached result. Turning collection off still leaves the enabled/available signals at zero and receipt occupancy at NaN; memory occupancy gauges are omitted as documented above.

The row budget bounds returned/processed records, **not** database pages or invisible/dead tuples. PostgreSQL work still depends on table health, concurrent load and connection/network behavior. The JDBC statement timeout cancels long-running queries (including table-lock waits), but is not a hard wall-clock limit on the entire collection. Pool acquisition retains the existing `quarkus.datasource.dlr.jdbc.acquisition-timeout` (default 5 seconds); monitoring does not change this shared processing-pool setting. A stalled run cannot queue overlapping monitoring reads, and old values expire independently at scrape time.

If collection becomes unavailable, check the enable switch, database connectivity and row budget. Increase the interval or turn storage metrics off to reduce monitoring load; increase the row budget only if the additional read cost is acceptable. Other metrics keep working. This intentionally favors a simple safe failure mode over exact backlog visibility at every scale; no summary tables, replication or repair machinery are introduced. PostgreSQL 17 integration tests verify complete counts, overflow, lock-wait cancellation and a limited plan returning 101 records from a 100001-record table without sorting/aggregation. This is correctness/plan evidence, not a universal performance guarantee.

Storage occupancy over a shared PostgreSQL store is a global snapshot, whereas process event counters are instance-local. Do not sum identical database snapshots across instances. For basic deployments, one collecting instance per shared store reduces query load. Remember that the global storage switch also omits that instance's local memory gauges; if those are needed from every instance, leave collection enabled and select a target for the shared-store view. If several instances collect the same store, select a collecting target or use `max` across only that store's healthy targets; never combine independent stores this way or turn unavailable values into zero.

### Requirements For Future Storage Implementations

Each storage backend issue owns these checks before claiming metrics support:

1. Preserve common units, states, transition boundaries, and bounded labels; document unsupported measurements explicitly.
2. Honor storage collection controls and availability/freshness behavior. Supply maintained occupancy or demonstrate bounded collection cost; do not add per-scrape full scans.
3. Test admission, partial publication, take/release, retries, duplicate completion, handoff failure, cleanup failure, and close as applicable.
4. Test durable recovery and reconciliation: rebuild occupancy, avoid counting recovery as new traffic, and retain failed cleanup occupancy until its actual release boundary.
5. Demonstrate disabled/failed/timed-out collection does not affect message-processing semantics, and unknown state does not appear empty.
6. Document per-process versus shared-storage scope, restart limitations, performance evidence, and any added backend-specific diagnostics in this guide.

File-backed and bounded-memory receipt storage remain owned by their respective issues under [#337](https://github.com/cytechmobile/sendium/issues/337). This contract does not implement them or require a generic metrics plugin framework.

## Example Panels

These queries assume the scrape job is named `sendium` as in the configuration below. Filter further by deployment/instance when several Sendium installations share Prometheus. A five-minute rate needs multiple scrapes; newly created counters may initially show no rate. Counter series for provider activity appear after the first request; this alone does not prove a failed collector.

| Grafana panel | PromQL | Unit / interpretation |
| :--- | :--- | :--- |
| Accepted and rejected traffic | `sum by (ingress, result) (rate(sendium_sms_submissions_total{job="sendium"}[5m]))` | Submissions/second, separated by HTTP/SMPP and result. |
| Observed submissions in the last hour | `sum by (ingress, result) (increase(sendium_sms_submissions_total{job="sendium"}[1h]))` | Estimated observed decisions, not an exact ledger or billing total. |
| Pending retained sources | `sendium_sms_pending_sources{job="sendium"}` | Sources per instance, including held parts and later-stage work. |
| Pending growth/drain trend | `delta(sendium_sms_pending_sources{job="sendium"}[10m])` | Positive means net growth; negative means net drain or restart/reset. |
| Selected routing work and bookkeeping | `sum by (instance, state) (sendium_sms_selected_work{job="sendium"})` | Selections; only waiting plus routing occupies unassigned Selected capacity. |
| Routed work by destination | `sum by (instance, destination, state) (sendium_sms_routed_work{job="sendium"})` | Retained selections; waiting, active and terminal have different meanings. |
| Provider traffic including retries | `sum by (provider, kind) (rate(sendium_sms_provider_attempts_total{job="sendium"}[5m]))` | Provider-part request invocations/second; keep initial and retry series separate. |
| Requests awaiting provider response | `sendium_sms_provider_in_flight{job="sendium"}` | Requests per provider and instance, not logical selections. |
| Provider response results | `sum by (provider, result) (rate(sendium_sms_provider_response_seconds_count{job="sendium"}[5m]))` | First results/second; acceptance is not handset delivery. |
| Final receipt outcomes | `sum by (provider, outcome) (rate(sendium_dlr_outcomes_total{job="sendium"}[5m]))` | Committed tracked receipt transitions/second. |
| Client receipt-return activity | `sum by (channel, result) (rate(sendium_dlr_return_attempts_total{job="sendium"}[5m]))` | Logical return attempts/second; successful retries can deliver the same receipt again. |
| Receipt snapshot availability | `sendium_storage_collection_available{job="sendium",stage="dlr"}` | 1 = usable, 0 = unavailable. Check enabled flag and budget/database settings. |
| Receipt snapshot age | `time() - sendium_storage_collection_last_success_timestamp_seconds{job="sendium",stage="dlr"}` | Seconds since last complete success; unavailable before first success. |

Use the following additional queries for latency and receipt backlog. The latency denominator is filtered to positive activity rather than replacing idle time with zero:

```promql
# Mean provider completion latency in seconds; no result when no requests complete.
sum by (provider) (rate(sendium_sms_provider_response_seconds_sum{job="sendium"}[5m]))
/
(sum by (provider) (rate(sendium_sms_provider_response_seconds_count{job="sendium"}[5m])) > 0)

# Receipt backlog by state and instance, only while its snapshot is available.
sendium_dlr_storage_messages{job="sendium"}
and on (job, instance, backend)
(sendium_storage_collection_available{job="sendium",stage="dlr"} == 1)
```

For receipt occupancy, select one collecting instance when several targets share one PostgreSQL store. Do not sum those copies. In contrast, summing instance-local event rates for the same deployment is meaningful. Keep independent database stores separated with your Prometheus target labels.

### Basic Alerts

Save the following as a Prometheus rule file and reference it with `rule_files` in your Prometheus configuration. This only supplies examples; Sendium does not install or enable alerts for you. Thresholds are illustrative, not service-level objectives. A sustained backlog is a reason to investigate, not proof that processing is completely stuck: compare Selected/Routed state, provider traffic, pauses, capacity and diagnostic logs.

```yaml
groups:
  - name: sendium-basic
    rules:
      - alert: SendiumTargetDown
        expr: up{job="sendium"} == 0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Sendium target {{ $labels.instance }} cannot be scraped"
          description: "Check the process, network and metrics endpoint before interpreting missing traffic."

      - alert: SendiumPendingBacklog
        expr: sendium_sms_pending_sources{job="sendium"} > 1000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Sendium retained-source backlog is sustained"
          description: "Check Selected/Routed state, worker pauses, provider progress and cleanup errors. Tune for your pending capacity."

      - alert: SendiumProviderFailures
        expr: sum by (job, instance, provider) (increase(sendium_sms_provider_response_seconds_count{job="sendium",result!="accepted"}[5m])) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Provider {{ $labels.provider }} has repeated request failures"
          description: "Inspect rejection, timeout, transport and protocol result series; these are provider-part attempts, not unique messages."

      - alert: SendiumReceiptReturnFailures
        expr: sum by (job, instance, channel) (increase(sendium_dlr_return_attempts_total{job="sendium",result="failure"}[10m])) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Client receipt returns are failing on {{ $labels.channel }}"
          description: "Check HTTP callback reachability/responses or SMPP client sessions and acknowledgements."

      - alert: SendiumReceiptCollectionUnavailable
        expr: (sendium_storage_collection_enabled{job="sendium",stage="dlr"} == 1) and on (job, instance, backend, stage) (sendium_storage_collection_available{job="sendium",stage="dlr"} == 0)
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "Receipt storage snapshot is unavailable"
          description: "Check the row budget, last-success time, database connectivity and timeout. Unavailable does not mean an empty backlog."
```

Add this top-level setting to your Prometheus configuration after saving the examples as `sendium-alerts.yml`:

```yaml
rule_files:
  - /etc/prometheus/sendium-alerts.yml
```

For the Docker deployment below, also mount that file with `-v "${SCRIPT_DIRECTORY}/sendium-alerts.yml:/etc/prometheus/sendium-alerts.yml:ro"`. Leave `rule_files` unset if you are not enabling alerts.

The collection alert intentionally excludes disabled collection. An unconfigured DLR backend has no collection series and is not treated as a failed backend. Unreachable configured targets are covered separately by `SendiumTargetDown`; a target removed entirely from discovery needs an expected-target check outside these basic examples. Adapt labels and target selectors before using these rules across multiple deployments or shared stores.

## Coverage And Limitations

- **Supported baseline:** live store gauges cover the implemented standalone `memory/memory/memory` pipeline. Memory work is lost on closure/restart; monitoring introduces no durability guarantee. PostgreSQL receipt occupancy is rebuilt by the next successful snapshot after restart, without replaying event counters.
- **Sending phases:** Routed waiting means not yet taken for Dispatch. Active includes preparation FIFO/rate waits, provider processing, replacement attempts and handoffs. There is no exact combined waiting-to-send gauge including the preparation FIFO, nor an independent handoff queue total. Do not subtract provider in-flight requests from active Routed selections to infer these counts: multipart/retry units differ.
- **Cleanup:** terminal Routed and retained Selected states expose outstanding bookkeeping, but a failure before Routed terminal marking still appears active. Routing discards remain Selected routing work until completion. See the cleanup table above rather than assuming every retained record is waiting to send.
- **Embedding:** core constructors are passive and programmatic workers/stores opt in through documented binding methods. Coordinated provider metrics do not instrument the legacy `doMessage` provider path. Raw receipt storage used outside the managed wrapper does not automatically emit final-outcome counters.
- **Not a ledger:** process counters reset, scrapes can miss activity, and accepted sources, selections, provider parts and receipt events are not interchangeable. Provider acceptance is not handset delivery. Duplicate receipts, multipart aggregation, suppressed returns and local retention cleanup have the documented boundaries above.
- **Bounded snapshots:** database collection may be unavailable above the configured row budget, during failures or after staleness. Disabling it leaves event counters working, but omits memory store gauges as well. No unsupported or unavailable backend is represented as a zero backlog.
- **No new backends or reporting platform:** file/memory DLR and durable outbound implementations belong to #337. They must preserve the shared meanings and add backend-specific collection/recovery tests. No account labels, per-message lookup, billing reports, dashboard provisioning, replicas or persisted metric summaries are added here.

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
