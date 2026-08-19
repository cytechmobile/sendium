# Docker Deployment

This guide explains how to run Sendium with Docker for local testing or simple deployments.

## Generated Quick Start

The recommended evaluation path generates random local credentials, Docker Compose, PostgreSQL 17 with a persistent named volume, and the three required configuration files:

```bash
curl -fsSLo quick-start.sh \
  https://raw.githubusercontent.com/cytechmobile/sendium/main/quick-start.sh
less quick-start.sh
sh quick-start.sh
```

Requirements:

- Docker with Docker Compose v2.
- `curl` and a POSIX shell on Linux, macOS, or WSL.
- A WSL filesystem directory, such as a directory under `~`, if Unix secret-file permissions must be enforced. If the script's permission probe cannot enforce mode `600`, it rejects the target unless `--allow-windows-mount` is explicitly supplied.

The interactive script supports:

| Mode | Generated upstream behavior |
| :--- | :--- |
| `ProSMS` | Configures `smpp.prosms.gr:2775`, no TLS, and one transceiver after approved credentials are supplied. Without approved credentials, it links to [ProSMS registration](https://prosms.gr/sms-tool/?v=2&m=8) and generates local-only configuration. |
| `Existing SMPP provider` | Prompts for host, port, system ID, password, and TLS, then configures one transceiver. |
| `Local setup only` | Starts the HTTP API and local SMPP server without an outbound route. |

Use `--provider local`, `--provider prosms`, or `--provider custom` to select a mode non-interactively. Run `sh quick-start.sh --help` for supported environment variables and all options.

The default generated layout is:

```text
sendium/
  .sendium.env
  compose.yml
  conf/
    credentials.yml
    smsg.properties
    routingTable.conf
  logs/
```

`.sendium.env`, `credentials.yml`, and `smsg.properties` contain secrets. The generated `.gitignore` excludes them, but they still require access-controlled storage and backups. The local PostgreSQL service is private to the Compose network and does not publish a database port.

Using `--force` regenerates the HTTP/SMPP credentials and configuration while preserving the generated local database password required by the existing PostgreSQL volume. When startup is enabled, Quick Start recreates the containers so the new credentials and worker configuration take effect together. With `--no-start`, it prints the required `docker compose up -d --force-recreate --remove-orphans` command instead.

To use an operator-managed PostgreSQL database, set `SENDIUM_DLR_POSTGRESQL_JDBC_URL`, `SENDIUM_DLR_POSTGRESQL_USERNAME`, and `SENDIUM_DLR_POSTGRESQL_PASSWORD` together before running Quick Start. The generated Compose file then omits the local PostgreSQL service. See [DLR Persistence](13-dlr-persistence.md) for TLS, permissions, retention, and durability guidance.

To generate a separate runtime using the native image, first stop any generated runtime using the same local ports:

```bash
docker compose -f sendium/compose.yml --project-directory sendium down

sh quick-start.sh \
  --directory sendium-native \
  --image cytechmobile/sendium:latest-native
```

## Manual Deployment

### Prerequisites

- Docker installed on the host machine.
- A working directory with `conf` and `logs` subdirectories.
- The required configuration files inside `conf`: `credentials.yml`, `smsg.properties`, and `routingTable.conf`.

### Directory Layout

```text
sendium-runtime/
  conf/
    credentials.yml
    smsg.properties
    routingTable.conf
  logs/
```

### Ports

| Port | Purpose |
| :--- | :--- |
| `8080` | HTTP API, Swagger UI, and OpenAPI JSON. |
| `27777` | Example SMPP server port from the README quick start. |

The SMPP port depends on `outSms.instance.<name>.srv.port` in `smsg.properties`.
Set `outSms.instance.<name>.srv.host = 0.0.0.0` inside the container for the Docker port mapping to reach the SMPP server. The host-side example below still limits access to loopback.

### Volumes

| Host path | Container path | Purpose |
| :--- | :--- | :--- |
| `./conf` | `/work/conf` | Runtime configuration files. |
| `./logs` | `/work/logs` | Application, SMPP, and HTTP access logs. |

### Docker Images

Sendium publishes two Docker image variants:

| Image | Runtime |
| :--- | :--- |
| `cytechmobile/sendium:latest` | JVM image based on Eclipse Temurin 25 JRE. |
| `cytechmobile/sendium:latest-native` | Native executable image. |

### Run Command

This example expects PostgreSQL to be reachable on port `5432` of the Docker host. Export the database password from an access-controlled secret source before starting Sendium:

```bash
export SENDIUM_DLR_POSTGRESQL_PASSWORD='replace-with-a-secret'

docker run -d --name sendium \
  --add-host host.docker.internal:host-gateway \
  -e SENDIUM_DLR_POSTGRESQL_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/sendium \
  -e SENDIUM_DLR_POSTGRESQL_USERNAME=sendium \
  -e SENDIUM_DLR_POSTGRESQL_PASSWORD \
  -e QUARKUS_LOG_FILE_ENABLE=true \
  -e QUARKUS_LOG_CONSOLE_ENABLE=false \
  -e QUARKUS_LOG_FILE_PATH=/work/logs/smsg.log \
  -e QUARKUS_LOG_FILE_SMPPCLIENT_PATH=/work/logs/smppclient.log \
  -e QUARKUS_LOG_FILE_SMPPSERVER_PATH=/work/logs/smppserver.log \
  -e QUARKUS_HTTP_ACCESS_LOG_DIRECTORY=/work/logs \
  -p 127.0.0.1:8080:8080 \
  -p 127.0.0.1:27777:27777 \
  -v ./conf:/work/conf \
  -v ./logs:/work/logs \
  cytechmobile/sendium:latest

unset SENDIUM_DLR_POSTGRESQL_PASSWORD
```

To run the native image instead, use `cytechmobile/sendium:latest-native`.

## Startup Checks

After starting the container:

1. Check container status with `docker ps`.
2. Open `http://localhost:8080/swagger-ui` to confirm the HTTP API is available.
3. Check `http://localhost:8080/q/health/ready` and confirm the `sendium-dlr-storage` check is `UP` before sending traffic.
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
- Before exposing HTTP externally, terminate HTTPS at a trusted proxy and configure every proxy/access-log layer to omit query strings because `/sendsms` carries credentials and message data in its URL.
- Before exposing SMPP externally, configure SMPP TLS or another appropriately protected private transport rather than publishing the plaintext example port.

## Stop And Remove

For a generated Docker Compose runtime, run these commands inside its directory:

```bash
docker compose logs -f
docker compose down
```

`docker compose down` retains the generated PostgreSQL volume. Adding `--volumes` permanently removes it and should only be used when database deletion is intended.

For the manual `docker run` example:

```bash
docker stop sendium
docker rm sendium
```

See [DLR Persistence](13-dlr-persistence.md) before changing how the database volume is managed.
