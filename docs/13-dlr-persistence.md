# DLR Persistence

Sendium stores the state needed to correlate upstream delivery receipts (DLRs) and replay receipts that could not be delivered to a downstream SMPP client. PostgreSQL is the recommended backend for new deployments. MVStore remains available for compatibility with existing installations.

This storage boundary does not make Sendium's message queues or all delivery processing durable. Review [Durability Boundaries](#durability-boundaries) before using restart recovery as a delivery guarantee.

## Quick Start PostgreSQL

The generated Quick Start runtime selects PostgreSQL and creates:

- A private `postgres:17-alpine` service with no published database port.
- A named Docker volume for `/var/lib/postgresql/data`.
- A generated 256-bit database password in `.sendium.env`, with mode `600` where the filesystem can enforce Unix permissions.
- A PostgreSQL health check that gates Sendium startup.
- A readiness check that verifies the selected DLR schema is available.

Run Quick Start normally:

```bash
sh quick-start.sh
```

`docker compose down` removes the containers and network but retains the PostgreSQL volume. Do not use `docker compose down --volumes` or manually delete the volume unless permanent database deletion is intended.

Quick Start preserves the local database password during `--force` regeneration. PostgreSQL initialization variables cannot rotate the password of a role that already exists in a persistent data volume.

## External PostgreSQL

To omit the local PostgreSQL service and connect Sendium to an operator-managed database, first export `SENDIUM_DLR_POSTGRESQL_PASSWORD` from an access-controlled secret source without placing its value in shell history. Then provide the URL and username when generating the runtime:

```bash
SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://db.example.com:5432/sendium?sslmode=verify-full' \
SENDIUM_DLR_POSTGRESQL_USERNAME='sendium' \
  sh quick-start.sh --directory sendium --provider local

unset SENDIUM_DLR_POSTGRESQL_PASSWORD
```

The three values are an all-or-nothing override. Partial configuration is rejected instead of mixing local and external settings.

For a manual deployment, configure:

| Variable | Required value or example | Purpose |
| :--- | :--- | :--- |
| `SENDIUM_DLR_STORAGE` | `postgresql` | Selects the PostgreSQL storage adapter. |
| `SENDIUM_DLR_POSTGRESQL_ACTIVE` | `true` | Activates the named datasource and its Flyway migrations. |
| `SENDIUM_DLR_POSTGRESQL_JDBC_URL` | `jdbc:postgresql://db.example.com:5432/sendium` | JDBC connection URL. Add PostgreSQL JDBC TLS parameters for external networks. |
| `SENDIUM_DLR_POSTGRESQL_USERNAME` | `sendium` | Database role used by Sendium and Flyway. |
| `SENDIUM_DLR_POSTGRESQL_PASSWORD` | Secret value | Database password. Supply it through an access-controlled environment or secret mechanism. |

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

The `sendium-dlr-storage` readiness check reports the selected backend. It returns `DOWN` with a sanitized `unavailable` reason when the selected PostgreSQL schema cannot be queried.

Inspect storage and datasource metrics with:

```bash
curl -fsS http://127.0.0.1:8080/q/metrics | grep -E 'sendium_dlr_storage|agroal'
```

Relevant metrics include the selected backend and storage-operation latency/counts tagged by operation and success or error outcome. PostgreSQL pool metrics use the Agroal metric prefix.

PostgreSQL is fail-closed. If required persistence is unavailable, new HTTP submissions return the retryable `503` response and new SMPP submissions return `ESME_RSYSERR`; Sendium does not fall back to MVStore or memory.

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

These limits are intentional V1 boundaries. PostgreSQL replaces the existing DLR persistence store; it is not a durable queue, distributed claim coordinator, or delivery outbox.

## MVStore Compatibility

For a manual deployment that must remain on MVStore, use:

```text
SENDIUM_DLR_STORAGE=mvstore
SENDIUM_DLR_POSTGRESQL_ACTIVE=false
SENDIUM_DLR_MVSTORE_PATH=/work/data/dlr-mvstore.db
```

The MVStore path must be on persistent storage. If the file cannot be opened, MVStore compatibility behavior can fall back to in-memory storage; check readiness data for `mode=persistent` rather than assuming the mount is working.

## Cut Over From MVStore

There is no MVStore-to-PostgreSQL importer, dual-read period, or live migration. Existing correlations and unpushed receipts do not move when the backend changes.

1. Confirm whether pending DLR state can be allowed to expire or be abandoned. The safest compatibility choice is to remain on MVStore until a deliberate maintenance window is acceptable.
2. Stop accepting new HTTP and SMPP submissions.
3. Allow in-flight provider receipts and downstream replay to drain. The longest cleanup threshold is seven days, and cleanup is opportunistic rather than an exact deadline; continuous-traffic installations cannot obtain a lossless cutover without an importer.
4. Stop Sendium and back up the complete runtime, including `data/dlr-mvstore.db`.
5. Provision PostgreSQL, backups, access controls, and TLS where required.
6. Configure the five PostgreSQL variables described above and start exactly one Sendium instance.
7. Require `/q/health/ready` to report `UP` with `backend=postgresql` before reopening traffic.
8. Submit controlled HTTP and SMPP messages and verify provider correlation, callbacks, and downstream receipts.
9. Retain the MVStore backup and PostgreSQL database until the rollback decision window has closed.

Provider receipts for messages that existed only in MVStore will be unknown after the switch. Do not run MVStore-backed and PostgreSQL-backed Sendium instances concurrently against the same traffic as a migration strategy.

## Roll Back To MVStore

Rollback is also non-seamless. State written to PostgreSQL is not copied back to MVStore.

1. Stop accepting traffic and stop every Sendium instance.
2. Preserve the PostgreSQL database; do not drop its schema or volume.
3. Restore the previous MVStore file and runtime configuration.
4. Set `SENDIUM_DLR_STORAGE=mvstore` and `SENDIUM_DLR_POSTGRESQL_ACTIVE=false`. Remove the PostgreSQL URL and credentials from the Sendium container environment when they are no longer needed.
5. Start one Sendium instance and require readiness to report `backend=mvstore` and `mode=persistent`.
6. Reopen traffic only after controlled HTTP/SMPP checks pass.

Messages accepted while PostgreSQL was active remain only in PostgreSQL. A later switch back to PostgreSQL can see still-retained PostgreSQL rows, so preserve both stores and record the exact cutover times during any rollback.

## Related Documentation

- [Architecture Overview](01-architecture.md)
- [Docker Deployment](02-docker-deployment.md)
- [Monitoring And Observability](08-monitoring-observability.md)
- [Configuration Reference](09-configuration-reference.md)
- [Troubleshooting](10-troubleshooting.md)
