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

## Logs

| Log | Description |
| :--- | :--- |
| `smsg.log` | Main application log. |
| `smppclient.log` | SMPP client connection and message activity. |
| `smppserver.log` | SMPP server bind, session, and message activity. |
| `httpapi.log` | HTTP API access log when file logging is enabled. |

## OpenAPI

When the HTTP server is running, Sendium exposes:

| Endpoint | Description |
| :--- | :--- |
| `/swagger-ui` | Interactive Swagger UI. |
| `/openapi.json` | OpenAPI JSON document. |

## Related Documentation

- [Docker Deployment](02-docker-deployment.md)
- [Authentication and Security](03-auth-security.md)
- [SMPP Configuration](04-smpp-configuration.md)
- [Routing Engine](05-routing-engine.md)
