# Monitoring And Observability

Sendium includes Prometheus-compatible metrics through the Quarkus Micrometer Prometheus extension. Operators can scrape the Sendium HTTP endpoint with Prometheus and visualize metrics in Grafana.

The dependency is provided by `quarkus-micrometer-registry-prometheus` in `sendium-core/pom.xml`. Because `sendium-app` depends on `sendium-core`, the runnable application includes the metrics endpoint.

## Metrics Endpoint

When Sendium is running, metrics are exposed at:

```text
http://<sendium-host>:8080/q/metrics
```

For a local Docker or development run, verify the endpoint with:

```bash
curl http://localhost:8080/q/metrics
```

The endpoint exposes Quarkus, JVM, HTTP server, and Micrometer runtime metrics. Sendium-specific business metrics require explicit instrumentation in code, such as counters, timers, or gauges registered through Micrometer.

## Prometheus Configuration

Create a `prometheus.yml` file in your monitoring deployment directory.

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets:
          - localhost:9090

  - job_name: sendium
    metrics_path: /q/metrics
    scrape_interval: 15s
    static_configs:
      - targets:
          - sendium.example.com:8080
```

Use the target that matches where Prometheus runs:

| Prometheus location | Sendium target example |
| :--- | :--- |
| Prometheus on the same host as Sendium | `localhost:8080` |
| Prometheus in Docker, Sendium on the host | `host.docker.internal:8080` on Docker Desktop, or the host IP on Linux |
| Prometheus and Sendium in the same Docker network | `sendium:8080` |
| Prometheus on another server | `<sendium-hostname-or-ip>:8080` |

## Deploy Prometheus With Docker

Create `prometheus.sh` in the same directory as `prometheus.yml`.

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "${SCRIPT_DIRECTORY}/prometheus-data"

docker rm -fv sendium-metrics-prometheus 2>/dev/null || true
docker run -d \
  --name sendium-metrics-prometheus \
  -p 9090:9090 \
  -v "${SCRIPT_DIRECTORY}/prometheus-data:/prometheus" \
  -v "${SCRIPT_DIRECTORY}/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  prom/prometheus \
  --config.file=/etc/prometheus/prometheus.yml
```

Run it with:

```bash
chmod +x prometheus.sh
./prometheus.sh
```

Prometheus will be available at:

```text
http://localhost:9090
```

In Prometheus, open **Status > Targets** and confirm that the `sendium` target is `UP`.

## Deploy Grafana With Docker

Create `grafana.sh` in the same directory.

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "${SCRIPT_DIRECTORY}/grafana-data"

docker rm -fv sendium-metrics-grafana 2>/dev/null || true
docker run -d \
  --name sendium-metrics-grafana \
  -p 3000:3000 \
  -v "${SCRIPT_DIRECTORY}/grafana-data:/var/lib/grafana" \
  grafana/grafana
```

Run it with:

```bash
chmod +x grafana.sh
./grafana.sh
```

Grafana will be available at:

```text
http://localhost:3000
```

The default login is usually `admin` / `admin`. Grafana will prompt you to change the password on first login.

## Add Prometheus To Grafana

In Grafana:

1. Go to **Connections > Data sources**.
2. Add a **Prometheus** data source.
3. Set the Prometheus server URL.
4. Click **Save & test**.

Use the URL that matches where Grafana runs:

| Grafana location | Prometheus URL example |
| :--- | :--- |
| Grafana on the same host as Prometheus | `http://localhost:9090` |
| Grafana in Docker, Prometheus published on host port 9090 | `http://host.docker.internal:9090` on Docker Desktop, or the host IP on Linux |
| Grafana and Prometheus in the same Docker network | `http://sendium-metrics-prometheus:9090` |

## Useful Checks

Check the Sendium metrics endpoint directly:

```bash
curl http://localhost:8080/q/metrics
```

Check Prometheus targets:

```text
http://localhost:9090/targets
```

Check Grafana:

```text
http://localhost:3000
```

## Troubleshooting

| Problem | Check |
| :--- | :--- |
| `/q/metrics` returns 404 | Confirm the application was built with `quarkus-micrometer-registry-prometheus` on the runtime classpath. |
| Prometheus target is `DOWN` | Check the target hostname, port `8080`, firewall rules, and whether Prometheus is running inside Docker. |
| Prometheus can scrape itself but not Sendium | Use `host.docker.internal:8080`, the host IP, or a shared Docker network target instead of `localhost:8080`. |
| Grafana cannot connect to Prometheus | Use the Prometheus URL reachable from the Grafana container, not necessarily the URL reachable from your browser. |

## Related Documentation

* [Docker Deployment](02-docker-deployment.md)
* [Configuration Reference](09-configuration-reference.md)
* [Troubleshooting](10-troubleshooting.md)
* [Documentation Map](DocumentationMap.md)
