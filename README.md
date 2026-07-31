# Sendium | Open-Source Headless SMS Gateway

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Built with Quarkus](https://img.shields.io/badge/Built%20with-Quarkus-blueviolet.svg)](https://quarkus.io/)
[![Site](https://img.shields.io/badge/Website-Sendium.org-orange)](https://sendium.org)

**Sendium** is an open-source, headless SMS gateway engineered for high-throughput delivery. Designed to sit seamlessly between your internal applications (CRMs, websites, IoT platforms) and your SMS connectivity (carriers, SMPP providers), it puts you firmly in control of your messaging infrastructure.

Eliminate vendor lock-in, manage your own routing logic, and scale your messaging horizontally across any on-premise or cloud environment.

---

## 🚀 Why Sendium?

* **Modern Stack:** A practical, container-native Kannel replacement built on **Java & Quarkus**.
* **Total Control:** Manage connections, adjust routing on the fly, and dictate fail-over rules.
* **Protocol Agnostic:** Bridge the gap between modern HTTP webhooks and legacy SMPP infrastructure.
* **High Throughput:** Designed for low latency and massive scale.

---
## 🏢 Backed by Cytech

**Sendium** is proudly created and maintained by **[Cytech](https://www.cytechmobile.com/)**.

With over 20 years of experience building high-performance telecommunications software, we open-sourced Sendium to provide the community with a modern, reliable Kannel alternative. Sendium is also the underlying routing engine that powers **[mCore](https://www.cytechmobile.com/mobile/mcore-a2p-wholesale-platform/)**, our complete enterprise business platform.

---
## 🛠 Core Capabilities

| Feature | Description |
| :--- | :--- |
| **SMPP Server & Client** | Full TX/RX/TRX bind support for downstream clients and upstream carriers, including TLS/SSL options. |
| **HTTP & SMPP Bridging** | Kannel-compatible HTTP `/sendsms` API for outbound SMS and SMPP-to-HTTP webhooks for inbound MO traffic. |
| **Advanced Routing** | Route by destination, sender ID, content, account, or message attributes with fallback routing chains. |
| **Granular TPS Control** | Protect links with Transactions-Per-Second limits per worker or SMPP server account defaults. |
| **Retry & Queue Control** | Retry and re-enqueue messages when `submit_sm` to an SMPP provider fails, with priority-aware queue support. |
| **Delivery Receipts** | Correlate provider message IDs, normalize DLR statuses, and propagate callbacks to originating systems. |
| **Operations & Observability** | Docker/native images, hot-reloaded configuration, operational logs, Swagger/OpenAPI, and Prometheus `/q/metrics`. |

---

## 🔄 How It Works

1.  **Request:** Your application sends a message to Sendium via **HTTP** or **SMPP**.
2.  **Logic:** Sendium applies routing rules based on destination, sender ID, account, message attributes, and priority.
3.  **Delivery:** Sendium delivers via one or more **SMPP** connections to upstream providers.
4.  **Verification:** Asynchronous Delivery Receipts (DLRs) are received and normalized.
5.  **Callback:** Sendium forwards the status back to your system via **HTTP Webhooks**.

---

## 📦 Quick Start

### Prerequisites

* Docker with Docker Compose v2.
* `curl` and a POSIX shell.

### Generate and Start Sendium

Download and run the setup script:

```bash
curl -fsSLo quick-start.sh https://raw.githubusercontent.com/cytechmobile/sendium/main/quick-start.sh && sh quick-start.sh
```

The script creates a `sendium/` runtime directory, generates random HTTP and SMPP credentials, writes Docker Compose and all required configuration files, starts Sendium, and waits for the HTTP API.

It asks you to choose one upstream option:

1. **ProSMS:** Uses `smpp.prosms.gr:2775` with a transceiver connection. [Create a ProSMS account](https://prosms.gr/sms-tool/?v=2&m=8) if needed; SMPP credentials require manual approval from ProSMS. Until credentials are approved, the script creates a local-only setup without a failing placeholder connection.
2. **Existing SMPP provider:** Enter your provider host, port, credentials, and TLS choice. Quick Start uses a transceiver connection.
3. **Local setup only:** Starts Sendium's local HTTP and SMPP interfaces without an outbound provider. You can explore the API, but messages cannot be delivered until an upstream route is configured.

HTTP and SMPP ports are bound to `127.0.0.1` by default. Use the [Docker deployment guide](docs/02-docker-deployment.md) for generated-file details, manual setup, native images, and non-local deployments.

When startup completes, the script prints the Swagger URL and an exact command for following live logs

### Send Your First SMS

For a configured upstream provider, load the generated local HTTP credentials and submit a message:

```bash
cd sendium
set -a
. ./.sendium.env
set +a

curl -i -G http://127.0.0.1:8080/sendsms \
  --data-urlencode "username=${SENDIUM_HTTP_USER}" \
  --data-urlencode "password=${SENDIUM_HTTP_PASSWORD}" \
  --data-urlencode "from=Sendium" \
  --data-urlencode "to=306910000000" \
  --data-urlencode "text=Hello from Sendium!"
```

Replace the sender and recipient with values accepted by your provider. A `202 Accepted` response and UUID mean Sendium validated and queued the request; they do not confirm that the upstream provider accepted or delivered the SMS.

See the [Docker deployment guide](docs/02-docker-deployment.md) for the complete manual container setup. Configuration details are maintained in [Authentication and Security](docs/03-auth-security.md), [SMPP Configuration](docs/04-smpp-configuration.md), [Routing Engine](docs/05-routing-engine.md), and the [Configuration Reference](docs/09-configuration-reference.md).

## 💬 Documentation & Support

The documentation entry point is **[docs/DocumentationMap.md](docs/DocumentationMap.md)**. It includes the recommended reading order, current docs index, runtime files, API discovery endpoints, roadmap, and community resources.

Migrating from Kannel? Use the browser-only **[Kannel migration converter](https://cytechmobile.github.io/sendium/)** to paste a legacy `kannel.conf` and generate Sendium starter files locally in your browser.

If you run into issues, have questions, or want to share what you're building, we'd love to hear from you! We use **[GitHub Discussions](https://github.com/cytechmobile/sendium/discussions)** for our community hub.

To help us help you faster, please use the appropriate category:

* **🙏 Q&A:** Stuck on the Quick Start? Need help with your routing config? Ask your questions here.
* **💡 Ideas:** Have a feature request or a suggestion to make Sendium better? Let's discuss it.
* **🙌 Show and tell:** Are you using Sendium in production? Did you build a cool integration? Share it with the community!
* **💬 General:** For all other chats and discussions about the project.
