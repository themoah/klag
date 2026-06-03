---
title: Configuration Reference
description: Complete reference of every Klag environment variable across app, Kafka, metrics, hot partitions, time-based lag, MCP, OTLP, and logging.
---

Klag is configured via `src/main/resources/application.properties`, an external config
file, or environment variables. Resolution order is **classpath → external file → env
vars** (env vars win).

## Application

| Variable | Default | Description |
|---|---|---|
| `HTTP_PORT` | `8888` | HTTP server port. |
| `KAFKA_HEALTH_CHECK_INTERVAL_MS` | `30000` | Health-check interval. |
| `VERTX_USE_VIRTUAL_THREADS` | `false` | Use virtual threads. |

## Kafka

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker addresses. |
| `KAFKA_REQUEST_TIMEOUT_MS` | `30000` | Request timeout. |
| `KAFKA_CHUNK_COUNT` | `1` | Split offset requests into N batches. |
| `KAFKA_CHUNK_DELAY_MS` | `0` | Delay (ms) between batches. |

For SASL/SSL, set `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM`, and
`KAFKA_SASL_JAAS_CONFIG`. See [Installation](/getting-started/installation/).

## Metrics

| Variable | Default | Description |
|---|---|---|
| `METRICS_REPORTER` | `none` | `none`, `prometheus`, `datadog`, or `otlp`. |
| `METRICS_INTERVAL_MS` | `60000` | How often to collect metrics. |
| `METRICS_GROUP_FILTER` | `*` | Comma-separated glob include patterns. |
| `METRICS_GROUP_EXCLUDE` | _(empty)_ | Comma-separated glob exclude patterns. |
| `METRICS_JVM_ENABLED` | `false` | Export JVM metrics. |
| `LAG_TREND_DEADBAND_MSG_PER_SEC` | `1.0` | STABLE band for the MCP lag-trend classifier. |

A group is monitored **iff** it matches any include segment **and** no exclude segment.
See [Group Filtering](/configuration/group-filtering/).

## Hot partition detection

| Variable | Default | Description |
|---|---|---|
| `HOT_PARTITION_ENABLED` | `true` | Enable hot-partition detection. |
| `HOT_PARTITION_SIGMA_MULTIPLIER` | `2.0` | Std-devs for the outlier threshold. |
| `HOT_PARTITION_MIN_PARTITIONS` | `3` | Min partitions per topic for detection. |
| `HOT_PARTITION_MIN_SAMPLES` | `3` | Min samples for throughput calc. |
| `HOT_PARTITION_BUFFER_SIZE` | `20` | Samples retained per partition. |

## Time-based lag estimation

| Variable | Default | Description |
|---|---|---|
| `TIME_LAG_ENABLED` | `true` | Enable time-based lag estimation. |
| `TIME_LAG_MIN_MESSAGES` | `100` | Min lag messages for time-to-close estimates. |
| `TIME_LAG_INTERPOLATION_BUFFER_SIZE` | `60` | Offset/timestamp points per partition. |
| `TIME_LAG_STALE_PRODUCER_THRESHOLD_MS` | `180000` | Time before a producer is considered stale. |

## MCP (AI agent access)

| Variable | Default | Description |
|---|---|---|
| `MCP_ENABLED` | `false` | Expose the `/mcp` endpoint (opt-in). |
| `MCP_AUTH_TOKEN` | _(empty)_ | Require `Authorization: Bearer <token>` when set. |
| `MCP_PATH` | `/mcp` | HTTP path of the MCP endpoint. |

See [MCP Endpoint](/ai/mcp/) for details.

## OTLP (when `METRICS_REPORTER=otlp`)

**Standard OpenTelemetry variables:**

| Variable | Description |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Base endpoint (e.g. `http://localhost:4318`). |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | Metrics-specific endpoint (overrides base). |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (`key1=value1,key2=value2`). |
| `OTEL_EXPORTER_OTLP_METRICS_HEADERS` | Metrics-specific headers. |
| `OTEL_METRIC_EXPORT_INTERVAL` | Export interval (ms), default `60000`. |
| `OTEL_SERVICE_NAME` | Service name, default `klag`. |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes. |

**Custom variables (override `OTEL_*`):** `OTLP_ENDPOINT`, `OTLP_STEP_MS`,
`OTLP_HEADERS`, `OTLP_RESOURCE_ATTRIBUTES`. Protocol is HTTP only (port 4318);
temporality is cumulative. See [OTLP & Grafana Cloud](/integrations/otlp-grafana/).

## Logging

`LOG_LEVEL`, `LOG_LEVEL_KLAG`, `LOG_LEVEL_KAFKA`, `LOG_LEVEL_HEALTH`,
`LOG_LEVEL_METRICS` set per-area log levels.
