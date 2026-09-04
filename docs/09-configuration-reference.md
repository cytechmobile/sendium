# Configuration Reference

This page summarizes runtime configuration files, application paths, environment variables, and API discovery endpoints.

## Runtime Configuration Paths

By default, Sendium reads these files relative to the application working directory:

| Property | Default | Description |
| :--- | :--- | :--- |
| `smsg.routing.file.path` | `conf/routingTable.conf` | Routing rules file. |
| `smsg.properties.file.path` | `conf/smsg.properties` | Worker and SMPP configuration file. |
| `smsg.credentials.file.path` | `conf/credentials.yml` | HTTP and SMPP credential file. |

In the Docker image, the working directory is `/work`, so the default configuration directory is `/work/conf`.

## Docker Images

| Image | Runtime |
| :--- | :--- |
| `cytechmobile/sendium:latest` | JVM image based on Eclipse Temurin 25 JRE. |
| `cytechmobile/sendium:latest-native` | Native executable image. |

## Important Files

| File | Description |
| :--- | :--- |
| `credentials.yml` | Authentication profiles for HTTP and SMPP clients. |
| `smsg.properties` | Worker definitions, SMPP server/client configuration, retry behavior, logging-related worker settings. |
| `routingTable.conf` | Routing tables and rules used to select workers. |

## Common Docker Environment Variables

| Variable | Example | Description |
| :--- | :--- | :--- |
| `QUARKUS_LOG_LEVEL` | `INFO` | Root Quarkus log level. |
| `LOG_LEVEL` | `INFO` | Sendium package log level. |
| `QUARKUS_LOG_CONSOLE_ENABLE` | `false` | Enables or disables console logging. |
| `QUARKUS_LOG_FILE_ENABLE` | `true` | Enables or disables file logging. |
| `QUARKUS_LOG_FILE_PATH` | `/work/logs/smsg.log` | Main application log path. |
| `QUARKUS_LOG_FILE_SMPPCLIENT_PATH` | `/work/logs/smppclient.log` | SMPP client log path. |
| `QUARKUS_LOG_FILE_SMPPSERVER_PATH` | `/work/logs/smppserver.log` | SMPP server log path. |
| `QUARKUS_HTTP_ACCESS_LOG_ENABLE` | `true` | Enables HTTP access logging. |
| `QUARKUS_HTTP_ACCESS_LOG_DIRECTORY` | `/work/logs` | HTTP access log directory. |

## DLR Storage Environment Variables

| Variable | Default | Description |
| :--- | :--- | :--- |
| `SENDIUM_DLR_POSTGRESQL_JDBC_URL` | Empty | PostgreSQL JDBC URL. |
| `SENDIUM_DLR_POSTGRESQL_USERNAME` | Empty | PostgreSQL role name when required by the database authentication method. |
| `SENDIUM_DLR_POSTGRESQL_PASSWORD` | Empty | PostgreSQL password when required; provide through an access-controlled environment or secret. |
| `SENDIUM_DLR_POSTGRESQL_POOL_MIN_SIZE` | `0` | Minimum datasource pool size. |
| `SENDIUM_DLR_POSTGRESQL_POOL_MAX_SIZE` | `10` | Maximum datasource pool size. |
| `SENDIUM_DLR_POSTGRESQL_ACQUISITION_TIMEOUT` | `5S` | Maximum wait for a pooled connection. |

PostgreSQL is the only DLR persistence backend, and startup is fail-closed. Startup requires a valid datasource URL and any username, password, certificates, or tokens required by the database authentication method; a bare launch fails rather than falling back to local or in-memory storage. See [DLR Persistence](13-dlr-persistence.md) for the complete durability contract.

### Core Embedding

`sendium.dlr.persistence.enabled` is a build-time setting. The `sendium-core` module leaves it undefined, which means disabled; the standalone `sendium-app` sets it to `true`.

When disabled, Sendium does not create its DLR services, PostgreSQL datasource, Flyway migration, or storage readiness check. Submissions are still accepted and routed, but no gateway DLR state is stored and Sendium emits no delivery receipts of its own. An application that embeds `sendium-core` must declare the property as `true` before Quarkus augmentation to opt into the complete DLR subsystem, then provide the PostgreSQL settings above. Enabled persistence is fail-closed for startup and HTTP ingress; accepted SMPP submissions retry persistence internally before routing. Those retries and the ingress backlog remain in memory, so a sustained outage requires operational intervention before memory is exhausted.

## Logs

| Log | Description |
| :--- | :--- |
| `smsg.log` | Main application log. |
| `smppclient.log` | SMPP client connection and message activity. |
| `smppserver.log` | SMPP server bind, session, and message activity. |
| `httpapi.log` | HTTP API access log when file logging is enabled. |

`httpapi.log` records the HTTP method, request path, protocol, response status, bytes sent, response time, referer, and user agent by default. It intentionally omits the query string because the Kannel-compatible `/sendsms` API carries credentials, phone numbers, callback URLs, and message text in query parameters.

If you customize `quarkus.http.access-log.pattern`, avoid `%r`, `%q`, `%{QUERY_STRING}`, and `%{q,...}` unless the resulting logs are treated as sensitive data.

Worker diagnostic flags such as `log.pdus`, `log.bytes`, `print.msgs`, `print.resps`, and `print.mos` are disabled by default. Enabling them can write SMPP credentials, phone numbers, provider identifiers, callback URLs, and message bodies to logs.

`message.*` lifecycle trace logs are controlled by `message.trace.mode`. The default `necessary` mode keeps `message.accepted`, `message.submitted`, `message.dlr`, and `message.deliver.sent`; use `off` to disable all message-flow logs or `all` to include route/enqueue/response/retry detail.

## OpenAPI

When the HTTP server is running, Sendium exposes:

| Endpoint | Description |
| :--- | :--- |
| `/swagger-ui` | Interactive Swagger UI. |
| `/openapi.json` | OpenAPI JSON document. |
| `/q/health/ready` | Readiness status and PostgreSQL DLR availability. |
| `/q/metrics` | Prometheus metrics, including DLR storage and datasource metrics. |

## Related Documentation

- [Docker Deployment](02-docker-deployment.md)
- [Authentication and Security](03-auth-security.md)
- [SMPP Configuration](04-smpp-configuration.md)
- [Routing Engine](05-routing-engine.md)
- [DLR Persistence](13-dlr-persistence.md)
