# DLR Persistence

Sendium stores the state needed to correlate upstream delivery receipts (DLRs) and replay receipts that could not be delivered to a downstream SMPP client in PostgreSQL.

This storage boundary does not make Sendium's message queues or all delivery processing durable. Review [Durability Boundaries](#durability-boundaries) before using restart recovery as a delivery guarantee.

## Application Boundary

The standalone `sendium-app` enables PostgreSQL DLR persistence and requires it to be available. Applications that embed `sendium-core` default to no Sendium-owned DLR subsystem and can run without a DLR database.

The `sendium.dlr.persistence.enabled` build-time property controls this boundary. When it is `false`, the DLR services, PostgreSQL datasource, Flyway migration, and storage readiness check are absent. Set the property to `true` before Quarkus augmentation to opt into the complete DLR subsystem; partial or no-op persistence is not provided.

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

Quick Start preserves the local database password during `--force` regeneration. PostgreSQL initialization variables cannot rotate the password of a role that already exists in a persistent data volume.

## Upgrade From MVStore Builds

Older Sendium builds could store DLR state in `data/dlr-mvstore.db`. Current builds do not read or import that file. Before upgrading an MVStore-configured runtime, stop accepting submissions and allow pending provider correlations and unpushed downstream receipts to drain, or explicitly accept that the remaining state will be unavailable after the upgrade. Stop Sendium and preserve the old file before starting the PostgreSQL-only build.

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

## Retention

The V1 retention thresholds are fixed application behavior, not environment settings:

| State | Eligible for cleanup after |
| :--- | :--- |
| Provider/operator correlation | 3 days |
| Tracked gateway message | 7 days |
| Unpushed downstream SMPP receipt | 7 days |

Cleanup is triggered by storage activity and runs no more than once per hour. These values are therefore eligibility thresholds, not exact physical deletion deadlines: idle records can remain in the database longer, and an active deployment can retain newly eligible state until the next cleanup pass. A provider receipt cannot be matched after its correlation has been removed. Making the thresholds or cleanup schedule configurable is outside the V1 storage replacement.

## Durability Boundaries

| State or transition | PostgreSQL guarantee | Remaining limit |
| :--- | :--- | :--- |
| Initial DLR state for HTTP and downstream SMPP submissions | Persisted before HTTP routing or a successful SMPP acknowledgement. | Router and worker queues remain in memory. A process crash can lose queued outbound work even though its DLR row remains until cleanup. |
| Gateway-to-provider message correlation | Survives Sendium restart after the provider message ID is linked. | Resolving a provider receipt consumes the correlation before callback or downstream delivery completes. A crash in that window can lose the resulting receipt. |
| Unpushed downstream SMPP receipt | Survives restart and is replayed when the same system ID binds again. | The row is removed after admission to the worker queue, not after confirmed downstream delivery. A crash in that window can lose the receipt. |
| Replay claim | Prevents duplicate replay within one Sendium process. | Claims are process-local. Multiple active Sendium replicas can claim and deliver the same database row. V1 supports one active gateway process. |
| Multipart submission | Each acknowledged segment has provisional DLR state; completed aggregates update the primary state. | Multipart assembly and its pending timers are process-local and are not reconstructed after restart. |
| HTTP DLR callback retry | The resolved callback is attempted up to 10 times while the process remains running. | The retry schedule is in memory and is lost on restart. There is no durable callback outbox. |
| Database files | The Quick Start named volume survives normal container replacement and `docker compose down`. | Volume deletion, host-disk loss, and disaster recovery require backups or external PostgreSQL replication managed by the operator. |

These limits are intentional V1 boundaries. PostgreSQL provides DLR persistence; it is not a durable queue, distributed claim coordinator, or delivery outbox.

## Related Documentation

- [Architecture Overview](01-architecture.md)
- [Docker Deployment](02-docker-deployment.md)
- [Monitoring And Observability](08-monitoring-observability.md)
- [Configuration Reference](09-configuration-reference.md)
- [Troubleshooting](10-troubleshooting.md)
