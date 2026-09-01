---
title: Quick Start
description: Run Klag with Docker in one command, or use the GraalVM native image for faster startup and lower memory.
---

Monitoring consumer progress is essential in production. The fastest way to try Klag is
the published Docker image.

The bare application defaults `METRICS_REPORTER` to `none`, so select `prometheus`,
`datadog`, or `otlp` to start collection. The Helm chart defaults its reporter to
`prometheus`.

## Docker (JVM)

The broker address below is a replaceable example. Set it to an address that resolves
and is reachable from inside the Klag container. If Kafka runs in another container,
attach both containers to the same Docker network and use the Kafka service DNS name.
If Kafka is exposed by your host, use `host.docker.internal:9092` on Docker Desktop.

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:latest
```

Metrics are then available at `http://localhost:8888/metrics`.

## Native image (faster startup, lower memory)

A GraalVM native build is published alongside the JVM image, tagged `:native` and
`:<version>-native`:

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:native
```

The native binary starts in **~70–100 ms using ~44 MB RSS**, versus ~500 ms / ~119 MB
for the JVM image, ideal for fast scaling and low-footprint deployments. Same config,
endpoints, and metrics. See [Native Image](/deployment/native-image/) for build details.

## HTTP endpoints

Once running, Klag exposes:

| Endpoint | Purpose |
|---|---|
| `/healthz` | Liveness probe (always 200). |
| `/readyz` | Readiness probe (200 if any Kafka cluster is UP, 503 if every cluster is DOWN). JSON includes top-level `kafka` plus a `clusters` array. |
| `/metrics` | Prometheus scrape endpoint (when enabled). |
| `/version` | Build information. |
| `/mcp` | [MCP endpoint](/ai/mcp/) for AI agents (when `MCP_ENABLED=true`). |

## What's next

- [Installation](/getting-started/installation/): Helm chart, Docker env-file.
- [Configuration Reference](/configuration/reference/): every environment variable.
- [Metrics Overview](/metrics/overview/): the full metric catalog.
- [Troubleshooting](/guides/troubleshooting/): missing metrics, Kafka readiness, ACLs,
  MCP, and ServiceMonitor discovery.
