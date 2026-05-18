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

## 🛠 Core Capabilities

| Feature | Description |
| :--- | :--- |
| **SMPP Server & Client** | Full TX/RX/TRX bind support for downstream clients and upstream carriers. |
| **Granular TPS Control** | Protect your links with Transactions-Per-Second rate limiting per connection. |
| **Advanced Routing** | Route by destination prefix, sender ID, or content with automatic failover. |
| **Protocol Translation** | HTTP-to-SMPP for outbound; SMPP-to-HTTP webhooks for inbound (MO). |
| **Real-time DLRs** | Normalized delivery receipt management propagated back to your originating systems. |

---

## 🔄 How It Works

1.  **Request:** Your application sends a message to Sendium via **HTTP** OR **SMPP**.
2.  **Logic:** Sendium applies routing rules (Country, Prefix, Sender ID, Priority).
3.  **Delivery:** Sendium delivers via one or more **SMPP** connections to upstream providers.
4.  **Verification:** Asynchronous Delivery Receipts (DLRs) are received and normalized.
5.  **Callback:** Sendium forwards the status back to your system via **HTTP Webhooks**.

---

## 📦 Quick Start

### Prerequisites
* Docker installed on your host machine.
* Create three empty directories in your current working folder: `./conf`, `./data`, and `./logs`.
* Before starting the container, Sendium requires three configuration files inside your newly created ./conf directory. These files control your binds, authentication, and routing logic.

Create the following three files inside ./conf and paste the sample configurations:
#### 1. credentials.yml
- Setting smpp and http credentials on sendium

```yml
credentials:
  - type: SMPP
    systemId: "test1"
    password: "test1"
  - type: HTTP
    systemId: "test2"
    password: "test2"
```
#### 2. smsg.properties
- Setting a smpp client connection and the smpp server.
```properties
# sample smpp client worker
outSms.instance.testRoute.enable = true
outSms.instance.testRoute.type = smppclient
outSms.instance.testRoute.username = testSystemId
outSms.instance.testRoute.password = testPass
outSms.instance.testRoute.host = smpp.test.com
outSms.instance.testRoute.port = 2775
outSms.instance.testRoute.tps = 0
outSms.instance.testRoute.connections.transceivers = 1

# smpp server
outSms.instance.smpp.enable = true
outSms.instance.smpp.type = smppserver
outSms.instance.smpp.tps = 0
outSms.instance.smpp.srv.port = 27777
outSms.instance.smpp.srv.defaultWindowSize = 1000
outSms.instance.smpp.srv.maxConnections = 1000
outSms.instance.smpp.srv.maxConnectionsPerIP = 4
outSms.instance.smpp.conf.maxConnectionsPerUser.default = 4
outSms.instance.smpp.conf.maxRate.default = 0
```
#### 3. routingTable.conf
- Routing configuration for sending all the traffic by default on the smpp client named testRoute

```conf
[default]
MESSAGE:type:==:0
MESSAGE:type:==:11
MESSAGE:type:==:14
MESSAGE:type:==:17
MESSAGE:type:==:10
smppserver.smpp:type:==:18

[MESSAGE]
testRoute::default:
```

### Running with Docker
Run the following command to start the gateway in the background:

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
  cytechmobile/sendium:latest-native
```

## 💬 Documentation & Support

If you run into issues, have questions, or want to share what you're building, we'd love to hear from you! We use **[GitHub Discussions](https://github.com/cytechmobile/sendium/discussions)** for our community hub.

To help us help you faster, please use the appropriate category:

* **🙏 Q&A:** Stuck on the Quick Start? Need help with your routing config? Ask your questions here.
* **💡 Ideas:** Have a feature request or a suggestion to make Sendium better? Let's discuss it.
* **🙌 Show and tell:** Are you using Sendium in production? Did you build a cool integration? Share it with the community!
* **💬 General:** For all other chats and discussions about the project.

*Note: If you are confident you have found a bug, please open a formal [GitHub Issue](https://github.com/cytechmobile/sendium/issues) so we can track it.*