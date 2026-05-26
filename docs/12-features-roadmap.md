# Features And Roadmap

Sendium is an open-source, headless SMS gateway built for high-throughput SMS delivery. It connects modern HTTP applications and downstream SMPP clients to upstream SMPP providers, while giving operators control over routing, throughput, delivery receipts, and observability.

This page separates the features available today from the planned roadmap, so operators and contributors can understand the current scope and where the project is heading.

## Current Features

### SMPP Server And Client

- Accept inbound SMPP binds from downstream applications or customers.
- Support transmitter, receiver, and transceiver bind modes.
- Maintain outbound SMPP client connections to upstream carriers, SMSCs, or aggregators.
- Support SMPP TLS/SSL configuration for secured connectivity.
- Support multiple upstream bind types, backup hosts, reconnect behavior, and SMPP keep-alives.

### HTTP And SMPP Protocol Bridging

- Accept outbound SMS submissions through a Kannel-compatible HTTP `GET /sendsms` API.
- Route HTTP-originated messages to SMPP providers.
- Accept SMPP-originated submissions from downstream clients.
- Forward mobile-originated messages from SMPP providers to HTTP webhooks.

### Routing Engine

- Route messages using configurable `routingTable.conf` rules.
- Match routes by message type, destination, sender ID, owner/account, body, product, and other message attributes.
- Use fallback/default rules when no specific route matches.
- Build routing chains by forwarding messages between routing tables.
- Support retry and re-enqueue behavior when `submit_sm` to an SMPP provider fails.

### Throughput And Queue Control

- Configure TPS limits per worker or SMPP server account defaults.
- Configure parallel connection counts and queue behavior per worker.
- Support priority-aware queues, honoring each message's priority attribute.
- Configure retry policies, suspension behavior, delayed retry, and router re-enqueue behavior.

### Delivery Receipts And MO Forwarding

- Track outbound messages and correlate provider message IDs with Sendium gateway IDs.
- Forward delivery receipt callbacks to originating HTTP systems using Kannel-style `dlr-url` placeholders.
- Normalize common delivery receipt status values for delivered, failed, buffered, and submitted states.
- Forward mobile-originated messages to HTTP endpoints using JSON or form-encoded payloads.
- Retry failed webhook callbacks.

### Configuration And Operations

- Use file-based configuration for credentials, SMPP workers, routing, and runtime behavior.
- Reload credentials and routing configuration without restarting the service.
- Run as a container-native Quarkus application.
- Use Docker images for JVM and native runtime variants.
- Expose OpenAPI and Swagger UI endpoints for API discovery.

### Monitoring And Observability

- Expose Prometheus-compatible metrics at `/q/metrics`.
- Provide JVM, Quarkus, HTTP, and Micrometer runtime metrics.
- Support Prometheus and Grafana integration.
- Provide operational logs for SMPP clients, SMPP servers, HTTP access, and runtime behavior.

## Roadmap

The roadmap will be shared and discussed through [GitHub Discussions](https://github.com/cytechmobile/sendium/discussions). Community members are encouraged to provide feedback on planned features, suggest priorities, and propose new ideas for future Sendium releases.
