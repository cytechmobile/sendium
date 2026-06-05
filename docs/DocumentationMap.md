# Sendium Documentation Map

This page is the main documentation entry point for Sendium users, operators, and contributors.

Sendium is an open-source, headless SMS gateway for high-throughput messaging. It sits between internal applications such as CRMs, websites, and IoT platforms and SMS connectivity such as carriers or SMPP providers. It is designed to help teams avoid vendor lock-in, control routing logic, and scale messaging across on-premise or cloud environments.

## Start Here

| If you want to... | Read this |
| :--- | :--- |
| Understand the runtime architecture | [01. Architecture Overview](01-architecture.md) |
| Run Sendium quickly with Docker | [02. Docker Deployment](02-docker-deployment.md) |
| Configure users and passwords | [03. Authentication and Security](03-auth-security.md) |
| Configure SMPP server or carrier binds | [04. SMPP Configuration](04-smpp-configuration.md) |
| Build routing and failover rules | [05. Routing Engine](05-routing-engine.md) |
| Submit SMS over HTTP | [06. HTTP API](06-http-api.md) |
| Receive DLR or MO callbacks | [07. Webhooks](07-webhooks.md) |
| Monitor runtime metrics | [08. Monitoring And Observability](08-monitoring-observability.md) |
| Look up configuration properties | [09. Configuration Reference](09-configuration-reference.md) |
| Diagnose common problems | [10. Troubleshooting](10-troubleshooting.md) |
| Start migrating from Kannel config | [Kannel migration converter](https://cytechmobile.github.io/sendium/) |
| Understand releases and publishing | [11. Release Process](11-release-process.md) |
| Review current features and roadmap | [12. Features And Roadmap](12-features-roadmap.md) |
| Contribute code or docs | [Contributing](../.github/CONTRIBUTING.md) |

## Core Concepts

| Concept | Description |
| :--- | :--- |
| HTTP API | Kannel-compatible `GET /sendsms` endpoint for outbound message submission. |
| SMPP Server | Accepts SMPP binds from downstream clients and applications. |
| SMPP Client | Connects to upstream SMSCs, carriers, or SMPP providers. |
| Routing Engine | Evaluates `routingTable.conf` rules and sends messages to workers or routing chains. |
| DLR Forwarding | Sends delivery receipt status updates back to the originating HTTP system. |
| MO Forwarding | Sends incoming mobile-originated messages to HTTP webhooks. |
| Hot Reload | Credentials and routing files are monitored and reloaded without restarting the service. |

## Runtime Files

Sendium expects these files in the configured `conf` directory. The Docker quick start maps this directory to `/work/conf`.

| File | Purpose |
| :--- | :--- |
| `credentials.yml` | Defines HTTP and SMPP authentication profiles. |
| `smsg.properties` | Defines workers, SMPP server settings, SMPP client binds, logging, and forwarding options. |
| `routingTable.conf` | Defines routing tables, rule conditions, and fallback behavior. |

## Existing Documentation

| Document | Description |
| :--- | :--- |
| [01. Architecture Overview](01-architecture.md) | Runtime components, queues, routing flow, worker lifecycle, DLR/MO handling, and Mermaid diagrams. |
| [02. Docker Deployment](02-docker-deployment.md) | Container setup, image variants, volumes, ports, startup checks, and operational notes. |
| [03. Authentication and Security](03-auth-security.md) | Credential file format, validation rules, IP allowlisting, and reload behavior. |
| [04. SMPP Configuration](04-smpp-configuration.md) | SMPP server, SMPP client, worker, retry, TLS, logging, and MO settings. |
| [05. Routing Engine](05-routing-engine.md) | Routing table syntax, operators, message attributes, and routing examples. |
| [06. HTTP API](06-http-api.md) | Kannel-compatible HTTP submission endpoint, parameters, and response codes. |
| [07. Webhooks](07-webhooks.md) | DLR callback URLs and MO forwarding behavior. |
| [08. Monitoring And Observability](08-monitoring-observability.md) | Prometheus metrics endpoint, Prometheus scrape configuration, Grafana setup, and troubleshooting. |
| [09. Configuration Reference](09-configuration-reference.md) | Application paths, Docker image tags, environment variables, logging, and OpenAPI endpoints. |
| [10. Troubleshooting](10-troubleshooting.md) | Common startup, authentication, routing, SMPP, webhook, and logging issues. |
| [11. Release Process](11-release-process.md) | Release Please flow, Conventional Commit rules, release PR handling, GitHub Packages, and Docker publishing. |
| [12. Features And Roadmap](12-features-roadmap.md) | Current product capabilities, planned roadmap phases, and related feature documentation. |
| [Kannel migration converter](https://cytechmobile.github.io/sendium/) | Browser-only helper for turning a legacy `kannel.conf` into Sendium starter files. |

## API Discovery

When Sendium is running, the HTTP API can be inspected through:

| Endpoint | Description |
| :--- | :--- |
| `/sendsms` | Kannel-compatible SMS submission endpoint. |
| `/swagger-ui` | Interactive Swagger UI. |
| `/openapi.json` | OpenAPI specification. |
| `/q/metrics` | Prometheus-compatible Micrometer metrics endpoint. |

## Community And Project Files

| File | Description |
| :--- | :--- |
| [README](../README.md) | Project overview, capabilities, and quick start. |
| [Contributing](../.github/CONTRIBUTING.md) | Local setup, tests, style, and pull request process. |
| [Code of Conduct](../CODE_OF_CONDUCT.md) | Community behavior expectations and reporting process. |
| [Support](../SUPPORT.md) | Where to ask questions, report bugs, and request features. |
| [Security](../SECURITY.md) | Private vulnerability reporting policy. |
| [License](../LICENSE) | GPL-3.0 license. |

## Suggested Reading Order

1. Read the [README](../README.md) for the project overview.
2. Read [Architecture Overview](01-architecture.md) to understand the runtime components and message flow.
3. Follow [Docker Deployment](02-docker-deployment.md) to start a local gateway.
4. Configure access in [Authentication and Security](03-auth-security.md).
5. Configure binds in [SMPP Configuration](04-smpp-configuration.md).
6. Configure message flow in [Routing Engine](05-routing-engine.md).
7. Submit a test message using [HTTP API](06-http-api.md).
8. Add delivery callbacks using [Webhooks](07-webhooks.md).
9. Monitor the service using [Monitoring And Observability](08-monitoring-observability.md).
10. Review current and planned product scope in [Features And Roadmap](12-features-roadmap.md).
11. Learn how releases are created and published in [Release Process](11-release-process.md).

## Documentation Gaps To Improve Next

These are useful follow-up docs for future contributors:

| Proposed Topic | Why it matters |
| :--- | :--- |
| Production operations guide | Covers sizing, persistence, upgrades, backups, alerting, and maintenance. |
| Configuration examples directory | Gives copy-paste examples for common SMPP and routing scenarios. |
| Message encoding guide | Explains GSM, Latin-1, UCS-2, binary messages, UDH, and multipart SMS. |
| SMPP error-code mapping guide | Helps operators map provider-specific errors to Sendium retry/fail behavior. |
