---
title: Quick Start
description: Run Klag with Docker in one command, or use the GraalVM native image for faster startup and lower memory.
---

The fastest way to try Klag is the published Docker image.

## Docker (JVM)

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:latest
```

Metrics are then available at `http://localhost:8888/metrics`.

## Native image (faster startup, lower memory)

A GraalVM native build is published alongside the JVM image, tagged `:native` and
`:<version>-native`:

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
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
| `/readyz` | Readiness probe (200 if Kafka UP, 503 if DOWN). |
| `/metrics` | Prometheus scrape endpoint (when enabled). |
| `/version` | Build information. |
| `/mcp` | [MCP endpoint](/ai/mcp/) for AI agents (when `MCP_ENABLED=true`). |

## What's next

- [Installation](/getting-started/installation/): Helm chart, Docker env-file.
- [Configuration Reference](/configuration/reference/): every environment variable.
- [Metrics Overview](/metrics/overview/): the full metric catalog.
