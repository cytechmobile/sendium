# DLR Persistence

Sendium stores the state needed to correlate upstream delivery receipts (DLRs) and durably track terminal HTTP/SMPP delivery in PostgreSQL.

This storage boundary does not make Sendium's message queues or all delivery processing durable. Review [Durability Boundaries](#durability-boundaries) before using restart recovery as a delivery guarantee.

## Application Boundary

The standalone `sendium-app` enables PostgreSQL DLR persistence and requires it to be available. Applications that embed `sendium-core` default to no Sendium-owned DLR subsystem and can run without a DLR database.

The `sendium.dlr.persistence.enabled` build-time property controls this boundary. `sendium-core` leaves it undefined and an undefined property means disabled, so an embedding application opts in by declaring it as `true` itself before Quarkus augmentation. When it is not enabled, the DLR services, PostgreSQL datasource, Flyway migration, and storage readiness check are absent; partial or no-op persistence is not provided.

Message paths degrade rather than fail when the subsystem is absent. HTTP and downstream SMPP submissions are accepted and routed without gateway DLR state, undelivered downstream receipts fall back to the worker's in-memory retry, and provider receipts are not correlated, so Sendium emits no delivery receipts of its own. An application that embeds `sendium-core` without this subsystem is expected to supply its own `Tracker` and message store if it needs delivery receipts.

## Storage Model

`sendium_dlr.dlr_message` contains one row per gateway UUID. The row holds ingress metadata, the exact terminal provider outcome, the downstream delivery channel and status, the common HTTP/SMPP payload, the retry schedule, and the monotonically increasing attempt number used for fencing.

`sendium_dlr.provider_correlation` maps the exact `(provider_name, provider_message_id)` pair to the gateway UUID. Multiple provider correlations, including multipart provider IDs, can point to one message. Terminal resolution stores the resolving provider pair and outcome in `dlr_message`, removes every correlation for that gateway message, and retains the message row only when HTTP or SMPP delivery is required.

## Quick Start PostgreSQL

The generated Quick Start runtime creates:

- A private `postgres:17-alpine` service with no published database port.
- A named Docker volume for `/var/lib/postgresql/data`.
- A generated 256-bit database password in `.sendium.env`, with mode `600` where the filesystem can enforce Unix permissions.
- A PostgreSQL health check that gates Sendium startup.
- A readiness check that verifies the DLR schema is available.

Run Quick Start normally:

```bash
sh quick-start.sh
```

`docker compose down` removes the containers and network but retains the PostgreSQL volume. Do not use `docker compose down --volumes` or manually delete the volume unless permanent database deletion is intended.

Quick Start preserves the local database password during `--force` regeneration, including while an external database is temporarily selected. PostgreSQL initialization variables cannot rotate the password of a role that already exists in a persistent data volume. If the saved local password is lost, Quick Start fails with instructions to delete both the volume and its generated Compose marker before a new password is generated.

## Upgrade From MVStore Builds

Older Sendium builds could store DLR state in `data/dlr-mvstore.db`. Current builds do not read or import that file. Before upgrading an MVStore-configured runtime, stop accepting submissions and allow pending provider correlations and terminal deliveries to drain, or explicitly accept that the remaining state will be unavailable after the upgrade. Stop Sendium and preserve the old file before starting the PostgreSQL-only build.

Provision PostgreSQL and require readiness to report `UP` with `backend=postgresql` before reopening traffic. State written to PostgreSQL is not available to an older MVStore build if the application is later downgraded.

## External PostgreSQL

To omit the local PostgreSQL service and connect Sendium to an operator-managed database, first export `SENDIUM_DLR_POSTGRESQL_PASSWORD` from an access-controlled secret source without placing its value in shell history. Then provide the URL and username when generating the runtime:

```bash
SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://db.example.com:5432/sendium?sslmode=verify-full' \
SENDIUM_DLR_POSTGRESQL_USERNAME='sendium' \
  sh quick-start.sh --directory sendium --provider local

unset SENDIUM_DLR_POSTGRESQL_PASSWORD
```

The three values are an all-or-nothing override. Partial configuration is rejected instead of mixing local and external settings.

For a manual deployment, supply a valid connection URL and the settings required by the database authentication method:

| Variable | Required value or example | Purpose |
| :--- | :--- | :--- |
| `SENDIUM_DLR_POSTGRESQL_JDBC_URL` | `jdbc:postgresql://db.example.com:5432/sendium` | JDBC connection URL. Add PostgreSQL JDBC TLS parameters for external networks. |
| `SENDIUM_DLR_POSTGRESQL_USERNAME` | `sendium` | Database role used by Sendium and Flyway when required by the authentication method. |
| `SENDIUM_DLR_POSTGRESQL_PASSWORD` | Secret value | Database password when required. Supply it through an access-controlled environment or secret mechanism. |

The database role must be able to connect to the database and create and manage the `sendium_dlr` schema. Flyway creates or validates the schema during startup. Keep migration privileges available for future application upgrades.

Use TLS with certificate verification when the database connection crosses an untrusted network. The exact JDBC parameters and certificate path depend on the PostgreSQL service. Protect database credentials from shell history, logs, source control, screenshots, and unauthorized container inspection.

## Pool Settings

These optional settings retain their shown defaults:

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `SENDIUM_DLR_POSTGRESQL_POOL_MIN_SIZE` | `0` | Minimum number of datasource connections. |
| `SENDIUM_DLR_POSTGRESQL_POOL_MAX_SIZE` | `10` | Maximum number of datasource connections. |
| `SENDIUM_DLR_POSTGRESQL_ACQUISITION_TIMEOUT` | `5S` | Maximum wait for a pooled connection. |

Size the pool against measured gateway concurrency and the database connection budget. Do not increase it beyond the database's safe connection capacity.

## Startup And Monitoring

Check readiness rather than only checking whether the HTTP listener is open:

```bash
curl -fsS http://127.0.0.1:8080/q/health/ready
```

The `sendium-dlr-storage` readiness check reports `backend=postgresql`. It returns `DOWN` with a sanitized `unavailable` reason when the PostgreSQL schema cannot be queried.

Inspect storage and datasource metrics with:

```bash
curl -fsS http://127.0.0.1:8080/q/metrics | grep -E 'sendium_dlr_storage|agroal'
```

Relevant metrics include storage-operation latency/counts tagged by backend, operation, and success or error outcome. PostgreSQL pool metrics use the Agroal metric prefix.

PostgreSQL is fail-closed. If required persistence is unavailable, new HTTP submissions return the retryable `503` response and new SMPP submissions return `ESME_RSYSERR`; Sendium does not fall back to local or in-memory storage.

Provider message IDs are correlated within the outbound provider namespace rather than globally. The worker instance name is the default namespace; workers connected to the same SMSC account can share `msg.hash.prefix` when that SMSC may deliver their receipts interchangeably. Different providers may therefore return the same message ID without overwriting each other's state. The namespace must remain stable while correlations are outstanding: changing `msg.hash.prefix` or renaming a worker using the default makes earlier receipts unresolvable.

Sendium requests final delivery receipts from upstream SMPP providers. A valid unsolicited `ACCEPTD` or `ENROUTE` receipt, including a receipt identified through its SMPP ESM class, is acknowledged successfully but is not forwarded and does not consume its provider correlation. The first terminal receipt consumes every correlation for the gateway message and produces the downstream DLR; later receipts for those provider message IDs cannot resolve it. Multipart submissions retain this first-terminal behavior and do not aggregate delivery states across every segment. A terminal persistence failure returns `deliver_sm_resp` with `STATUS_SYSERR` so the provider can retry. A successful provider acknowledgement confirms durable resolution only; it does not wait for downstream HTTP or SMPP delivery.

A terminal receipt remains in `sendium_dlr.dlr_message` while HTTP or SMPP delivery is pending or after HTTP delivery reaches terminal `FAILED` status. The delivery attempt number is a fencing token: a stale completion or failure cannot mutate a newer attempt, and a process-local storage guard prevents duplicate starts within one Sendium instance.

## Retention

The V1 retention thresholds are fixed application behavior, not environment settings:

| State | Eligible for cleanup after |
| :--- | :--- |
| Provider message correlation | 3 days |
| Message waiting for provider receipt | 7 days from creation |
| Pending or failed terminal delivery | 7 days from resolution |

Cleanup is triggered by storage activity and runs no more than once per hour. These values are therefore eligibility thresholds, not exact physical deletion deadlines: idle records can remain in the database longer, and an active deployment can retain newly eligible state until the next cleanup pass. A provider receipt cannot be matched after its correlation has been removed. Making the thresholds or cleanup schedule configurable is outside the V1 storage replacement.

An HTTP row marked `FAILED` after attempt 10 remains eligible based on the original provider-resolution time, not the time of its final request. SMPP delivery has no fixed attempt cap, but a still-pending SMPP row is also eligible for cleanup seven days after resolution.

Cleanup is best-effort maintenance and is isolated from message handling. One caller at a time runs a pass while every other caller proceeds immediately, and a failed pass is logged and left until the next interval rather than rejecting the submission that triggered it.

## Durability Boundaries

| State or transition | PostgreSQL guarantee | Remaining limit |
| :--- | :--- | :--- |
| Initial DLR state for HTTP and downstream SMPP submissions | Persisted before HTTP routing or a successful SMPP acknowledgement. | Router and worker queues remain in memory. A process crash can lose queued outbound work even though its DLR row remains until cleanup. |
| Gateway-to-provider message correlation | Survives Sendium restart after the provider message ID is linked. Intermediate `ACCEPTD` and `ENROUTE` receipts leave it intact. | The first terminal receipt consumes every correlation for the gateway message. |
| Terminal HTTP/SMPP delivery | The common payload and exact provider outcome remain in one row until fenced completion. | Delivery is at-least-once; acknowledgement can be received before the final delete commits. |
| Active delivery attempt | The database attempt number fences stale completion, retry, and failure updates. | The active-ID guard is process-local. Adapter recreation may start a new attempt for an attempt that was active before a crash. |
| Multipart submission | Each acknowledged segment has provisional DLR state; completed aggregates update the primary state. | Multipart assembly and its pending timers are process-local and are not reconstructed after restart. |
| HTTP DLR callback retry | Pending state, attempt count, and next-attempt timestamp are durable. Checks are scheduled every second in non-overlapping serial batches; failures retry after 120 seconds and attempt 10 failures become `FAILED`. | A request accepted before a crash or failed completion update can be repeated. A slow batch delays later due callbacks. |
| SMPP DLR delivery | One attempt covers every generated receipt part and completes only after matching successful `deliver_sm_resp` PDUs for all parts. Pending rows are enqueued when the same `system_id` binds. | Timeout, `generic_nack`, wrong/non-OK response, enqueue/send failure, or session closure releases the attempt. Replay is bind-driven rather than periodic, and partial success is not checkpointed. |
| Database files | The Quick Start named volume survives normal container replacement and `docker compose down`. | Volume deletion, host-disk loss, and disaster recovery require backups or external PostgreSQL replication managed by the operator. |

Downstream delivery uses bounded at-least-once attempt semantics, not exactly-once delivery. A crash or storage failure after an HTTP receiver accepts a callback, or after an SMPP client sends a successful `deliver_sm_resp`, can cause the receipt to be delivered again. Multipart SMPP replay can repeat already acknowledged parts. Consumers must be idempotent using the gateway or receipted message ID. HTTP retry limits, SMPP bind availability, and seven-day retention mean this is not an unlimited eventual-success guarantee.

These limits are intentional V1 boundaries. PostgreSQL provides DLR persistence and delivery fencing; it is not a distributed worker coordinator or a replacement for the router and worker queues. Attempt guards are process-local, so multiple active Sendium replicas sharing one database can start duplicate deliveries.

## Related Documentation

- [Architecture Overview](01-architecture.md)
- [Docker Deployment](02-docker-deployment.md)
- [Monitoring And Observability](08-monitoring-observability.md)
- [Configuration Reference](09-configuration-reference.md)
- [Troubleshooting](10-troubleshooting.md)
