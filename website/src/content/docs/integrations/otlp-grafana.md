---
title: OTLP & Grafana Cloud
description: Export Klag metrics over OpenTelemetry (OTLP/HTTP) to Grafana Cloud, New Relic, or any OTLP-compatible backend.
---

Klag speaks OTLP (OpenTelemetry Protocol) over HTTP, so it works with Grafana Cloud,
New Relic, and any OTLP-compatible metrics backend.

## Enable

```bash
METRICS_REPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_SERVICE_NAME=klag
```

Protocol is **HTTP only** (port `4318`) and aggregation temporality is **cumulative**.

## Grafana Cloud

```bash
METRICS_REPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-prod-us-east-0.grafana.net/otlp
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64-encoded-credentials>
OTEL_SERVICE_NAME=klag-production
```

## Local OpenTelemetry Collector

```bash
METRICS_REPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_SERVICE_NAME=klag-dev
OTEL_RESOURCE_ATTRIBUTES=environment=development,cluster=local
```

## Configuration variables

Klag honours the standard `OTEL_*` variables and also provides custom `OTLP_*`
overrides:

| Standard | Custom override | Purpose |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `OTLP_ENDPOINT` | Endpoint URL. |
| `OTEL_METRIC_EXPORT_INTERVAL` | `OTLP_STEP_MS` | Export interval (ms). |
| `OTEL_EXPORTER_OTLP_HEADERS` | `OTLP_HEADERS` | Auth headers (`key1=value1,key2=value2`). |
| `OTEL_RESOURCE_ATTRIBUTES` | `OTLP_RESOURCE_ATTRIBUTES` | Resource attributes. |

`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` / `OTEL_EXPORTER_OTLP_METRICS_HEADERS` override the
base endpoint/headers for metrics specifically. The custom `OTLP_*` variables take
precedence over the `OTEL_*` ones. See the full
[Configuration Reference](/configuration/reference/#otlp-when-metrics_reporterotlp).
