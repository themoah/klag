---
title: Prometheus
description: Scrape Klag metrics with Prometheus via the /metrics endpoint.
---

Prometheus is the default, zero-dependency way to consume Klag metrics.

## Enable

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:latest
```

Metrics are then served at `http://localhost:8888/metrics` in Prometheus text format.

## Scrape config

```yaml
scrape_configs:
  - job_name: klag
    metrics_path: /metrics
    static_configs:
      - targets: ['klag:8888']
```

On Kubernetes, the [Helm chart](/deployment/kubernetes/) can render a `ServiceMonitor`
for the Prometheus Operator — see the chart's `values.yaml`.

## What you get

All [metrics](/metrics/overview/) are exposed with `consumer_group`, `topic`, and
`partition` labels where applicable. Import the
[Grafana dashboard](/integrations/grafana-dashboard/) for ready-made panels.
