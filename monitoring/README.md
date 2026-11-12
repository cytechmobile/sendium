# Sendium monitoring dashboards

This directory runs Prometheus and Grafana and provisions two dashboards in the **Sendium** folder:

- **SMS Operations** (`sendium-sms-operations`): a compact overview of incoming acceptance/account traffic, outgoing SMS-part load, provider results/rejection percentage, retained-source backlog and receipt progress, plus two diagnostic panels in a collapsed **Queue details** section. Provider load/results charts repeat for the selected providers.
- **Application Health** (`sendium-application-health`): scrape reachability, uptime, CPU, JVM memory/GC/threads, HTTP performance and database-pool/storage-operation metrics.

It does **not** start Sendium, PostgreSQL or synthetic traffic, and does not install alerts or new application instrumentation. See the [metrics reference](../docs/08-monitoring-observability.md) for counting boundaries and optional alert examples.

The goal is to see how Sendium is operating, not calculate customer/provider charges. Billing stays outside this dashboard. Expand **Queue details** to inspect Selected work by state and Routed work by destination/state when Pending grows. These ownership counts overlap and must not be added together. Cached receipt-storage queries remain in the metrics reference rather than crowding the overview. Application runtime diagnostics stay on Application Health.

Standalone memory Pending/Selected/Routed gauges are always enabled. The single `sendium.metrics.storage.enabled` switch defaults to `true` and controls only PostgreSQL receipt snapshots; set it to `false` and restart to avoid their background database reads without losing the memory backlog view or event counters.

## Start

1. Start Sendium separately with its normal configuration, including PostgreSQL if DLR persistence is enabled. Confirm its `/q/metrics` endpoint responds.
2. Edit the Sendium target in [`prometheus.yml`](prometheus.yml). The default `host.docker.internal:8080` assumes Docker Desktop with Sendium on the host. For a shared Docker network, use the Sendium service's reachable hostname and port. On Linux, use a reachable host address or configure Docker's host-gateway mapping. `localhost` inside the Prometheus container is **not** the host machine. Ensure the endpoint is reachable from that container without opening application ports or changing firewall rules unintentionally.
3. Review published ports before starting. The existing Compose mappings `9090:9090` and `3000:3000` bind on all host interfaces. For a local-only setup, change them to `127.0.0.1:9090:9090` and `127.0.0.1:3000:3000`. Do not expose this development stack publicly; configure authentication, access restrictions and TLS appropriate to your deployment. Account identifiers are visible through metrics and dashboards.
4. From the repository root:

   ```sh
   docker compose -f monitoring/docker-compose.yml config --quiet
   docker compose -f monitoring/docker-compose.yml up -d
   ```

5. Open [Prometheus targets](http://localhost:9090/targets) and check the `sendium` target is `UP`. Open [Grafana](http://localhost:3000), sign in (new Grafana installations normally start with `admin` / `admin`), and change the password when prompted. Existing Grafana volumes retain their credentials; Compose does not reset them.
6. Open the **Sendium** folder, or use these links:
   - [SMS Operations](http://localhost:3000/d/sendium-sms-operations)
   - [Application Health](http://localhost:3000/d/sendium-application-health)

Choose the **Prometheus**, **Job** and **Instance** selectors at the top. The default job is `sendium`. Choose one instance: combining different process lifetimes makes raw totals confusing, and summing snapshot copies can double-count a shared PostgreSQL store. If jobs are reused across deployments, use uniquely identifiable instance labels/targets in your Prometheus configuration.

On SMS Operations, **Provider panels** selects which provider load/response charts to repeat (all by default), up to two charts per row. Each provider gets its own unstacked filled chart, so identical rates do not cover one another. The load panels share a scale with one another, as do the response panels: an invisible reference to the largest rate across providers sets a common axis extent. It is hidden from the legend/tooltip and is not extra traffic. The other overview charts still show all providers for the selected job/instance; the selector is not a dashboard-wide provider filter.

The datasource is provisioned as `Prometheus` with UID `sendium-prometheus`. The dashboard datasource selector also permits other Prometheus sources. The provisioned datasource interval is 15 seconds, matching the supplied scrape configuration; update it if you change that interval so Grafana's `$__rate_interval` remains appropriate. If an existing Grafana database already has a provisioned `Prometheus` datasource with another UID, check datasource provisioning logs and resolve that identity conflict before changing or deleting existing sources.

## Understand the numbers

| View | Meaning |
| :--- | :--- |
| Since-start total cards | Raw counters for the selected instance, **not counts within the selected period**. They reset when Sendium restarts. Changing a lookback from 30 to 5 minutes with the end at **now** does not change these totals. Selecting a historical end evaluates the counters at that historical time instead. They are not a durable billing ledger. |
| Traffic and latency charts | Follow the selected time range. Rates need multiple scrapes; means are not p95/p99. Idle latency or an undefined ratio has no value, not zero latency. |
| Filled SMS rate charts | All charts are unstacked: each filled area shows the individual series, not a cumulative total. Provider load/results use separate charts per selected provider with a common scale within each group. Legend shows last/maximum values and hover shows all visible series. Equal series in a shared chart can overlap; use the legend/tooltip to inspect them. |
| Incoming traffic | Accepted/rejected submissions per second by HTTP/SMPP, plus accepted submissions by authenticated accountId and ingress. HTTP long messages are one submission but may produce several provider parts; accepted SMPP parts are separate ingress submissions. |
| SMS-part send attempts | Provider-part request invocations, including retries and requests later rejected/timed out. Not unique parts, accepted provider parts or billable units. For example, 720 single-part plus 180 two-part HTTP messages give 900 submissions and 1,080 part attempts if there are no retries. |
| Provider results / rejection percentage | First attempt completions: accepted, rejected, timeout, transport_error or protocol_error. Rejection percentage is explicit rejections divided by all completed attempts in the rate window. Idle providers have no defined percentage. A failed final DLR is not a submission rejection. |
| Pending retained sources | Retained ingress sources, including later-stage ownership/bookkeeping. Not an exact waiting-to-send queue. Watch sustained growth. Detailed Selected/Routed metrics use different units and overlap Pending; do not add their counts. |
| Final receipt outcomes | Delivered/failed/expired committed transitions after multipart aggregation, by configured receipt-provider identity. These provider labels may differ from outgoing worker names. Not a one-to-one conversion funnel with admissions or provider-part attempts. |
| Receipt forwarding failures | Failed attempts to forward a receipt to the submitting application, not delivery failure at the handset. A provider-reported failed delivery can still be forwarded successfully. |
| Metrics endpoint reachable | Prometheus scrape success, **not application readiness** or successful SMS delivery. Use Sendium's `/q/health/ready` separately for readiness; this stack adds no readiness probe. |

Account series are created after the first accepted submission from an authenticated credential with a configured `accountId`. Multiple credentials sharing accountId aggregate. Missing accountId and best-effort metric failures can make account traffic lower than global admissions; provider/receipt units also differ. Account panels require a Sendium build exposing those metrics. These files do not invent unsupported values. Estimated period-total pies and account-total bars are deliberately omitted to keep the operational view clear.

Counts use full numbers with separators, not `K` abbreviations. Missing series display **No data** or **Unavailable**. JVM panels may be absent with native executables; database-pool/DLR panels may be absent when their subsystem is disabled. A first-start counter that has never observed an event may have no series. Do not interpret every empty panel as a failure.

## Provisioning and customization

- Dashboard JSON: [`grafana/dashboards/`](grafana/dashboards/).
- File provider: [`grafana/provisioning/dashboards/sendium.yml`](grafana/provisioning/dashboards/sendium.yml).
- Datasource: [`grafana/provisioning/datasources/prometheus.yml`](grafana/provisioning/datasources/prometheus.yml).

Dashboard files are mounted read-only and polled every 30 seconds (works with Docker Desktop bind mounts). Dashboard edits belong in JSON; the provisioned dashboards are not editable in Grafana. To experiment in the UI, save a copy with a different UID rather than changing the provisioned original. JSON-only edits are picked up automatically. After adding/changing Compose mounts or datasource/provider configuration, rerun `docker compose -f monitoring/docker-compose.yml up -d`; restart only the Grafana service if a configuration change was not reloaded. Service names/container names and host ports can conflict with an already running monitoring stack: reuse that stack by importing the JSON, or stop/change only the conflicting resources intentionally.

For an existing Grafana deployment, import the two JSON files, select its Prometheus datasource, and choose job/instance. If its provisioned datasource has a different UID, choose it in the dashboard datasource selector. No hard-coded preview URL, account name, credential or Windows path is required.

## Stop without deleting history

```sh
docker compose -f monitoring/docker-compose.yml stop
```

Or use `down` to remove this stack's containers/network while keeping named data volumes. Do **not** add `--volumes` unless you explicitly intend to erase its Prometheus history and Grafana database. Stopping this stack does not stop Sendium or PostgreSQL. Images currently follow the existing `latest` tags; pin versions according to your deployment policy rather than treating this development Compose file as a hardened production installation.
