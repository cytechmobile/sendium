# Docker Deployment

This guide explains how to run Sendium with Docker for local testing or simple deployments.

## Prerequisites

- Docker installed on the host machine.
- A working directory with `conf`, `data`, and `logs` subdirectories.
- The required configuration files inside `conf`: `credentials.yml`, `smsg.properties`, and `routingTable.conf`.

## Directory Layout

```text
sendium-runtime/
  conf/
    credentials.yml
    smsg.properties
    routingTable.conf
  data/
  logs/
```

## Ports

| Port | Purpose |
| :--- | :--- |
| `8080` | HTTP API, Swagger UI, and OpenAPI JSON. |
| `27777` | Example SMPP server port from the README quick start. |

The SMPP port depends on `outSms.instance.<name>.srv.port` in `smsg.properties`.

## Volumes

| Host path | Container path | Purpose |
| :--- | :--- | :--- |
| `./conf` | `/work/conf` | Runtime configuration files. |
| `./data` | `/work/data` | Local runtime data. |
| `./logs` | `/work/logs` | Application, SMPP, and HTTP access logs. |

## Docker Images

Sendium publishes two Docker image variants:

| Image | Runtime |
| :--- | :--- |
| `cytechmobile/sendium:latest` | JVM image based on Eclipse Temurin 25 JRE. |
| `cytechmobile/sendium:latest-native` | Native executable image. |

## Run Command

This command starts the default JVM image:

```bash
docker run -d --name sendium \
  -e QUARKUS_LOG_FILE_ENABLE=true \
  -e QUARKUS_LOG_CONSOLE_ENABLE=false \
  -e QUARKUS_LOG_FILE_PATH=/work/logs/smsg.log \
  -e QUARKUS_LOG_FILE_SMPPCLIENT_PATH=/work/logs/smppclient.log \
  -e QUARKUS_LOG_FILE_SMPPSERVER_PATH=/work/logs/smppserver.log \
  -e QUARKUS_HTTP_ACCESS_LOG_DIRECTORY=/work/logs \
  -p 8080:8080 \
  -p 27777:27777 \
  -v ./conf:/work/conf \
  -v ./data:/work/data \
  -v ./logs:/work/logs \
  cytechmobile/sendium:latest
```

To run the native image instead, use `cytechmobile/sendium:latest-native`.

## Startup Checks

After starting the container:

1. Check container status with `docker ps`.
2. Open `http://localhost:8080/swagger-ui` to confirm the HTTP API is available.
3. Open `http://localhost:8080/openapi.json` to confirm OpenAPI is available.
4. Inspect `logs/smsg.log`, `logs/smppclient.log`, and `logs/smppserver.log` if startup fails.

## Configuration Files

| File | Documentation |
| :--- | :--- |
| `credentials.yml` | [Authentication and Security](03-auth-security.md) |
| `smsg.properties` | [SMPP Configuration](04-smpp-configuration.md), [Configuration Reference](09-configuration-reference.md) |
| `routingTable.conf` | [Routing Engine](05-routing-engine.md) |

## Operational Notes

- Keep secrets out of public issues, logs, and screenshots.
- Use explicit versioned Docker image tags in production instead of floating tags such as `latest` or `latest-native`.
- Map `logs` to persistent storage if logs are required after container replacement.
- Review `QUARKUS_LOG_CONSOLE_ENABLE` and `QUARKUS_LOG_FILE_ENABLE` based on your logging stack.
- When exposing SMPP externally, firewall the port and configure credential IP allowlists where possible.

## Stop And Remove

```bash
docker stop sendium
docker rm sendium
```
