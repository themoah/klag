# Klag — Kafka Consumer Lag Monitoring for Grafana

Track Kafka consumer lag, consumer group health, and data-loss risk in real time. Powered by [Klag](https://github.com/themoah/klag), an open-source Kafka lag exporter. Works with any Prometheus-compatible source: Prometheus, Grafana Cloud, Mimir, Thanos, VictoriaMetrics, and OTLP pipelines.

Consumer lag counts how far a consumer group trails the latest produced offset. When lag passes topic retention, Kafka deletes messages the consumer never read. You lose data. This dashboard shows lag trends early, so you scale consumers or alert before that happens.

## Features

- **Consumer lag** per group, color-coded by threshold
- **Lag velocity** shows whether lag grows or shrinks (messages/sec)
- **Consumer group state** tracking: Stable, Rebalancing, Dead
- **Partition & offset** detail: per-partition lag, log offsets, topic throughput
- **Hot partition detection** flags skewed partitions via statistical outliers
- **Time-based lag** in milliseconds plus estimated time to catch up
- **Data Loss Prevention** shows % of retention consumed and at-risk topics
- **JVM metrics**: heap, GC pause, threads, CPU
- Template variables filter by consumer group and topic

## Requirements

- A running Klag instance with `METRICS_REPORTER=prometheus` or `otlp`
- A Prometheus-compatible data source
- Grafana 11.3+

## Import

1. Grafana → **Dashboards → New → Import**
2. Paste this dashboard ID, click **Load**
3. Pick your Prometheus source, click **Import**

## Keywords

Kafka, Kafka lag, consumer lag, consumer group, Kafka monitoring, lag exporter, Klag, Prometheus, Grafana Cloud, OTLP, partition lag, hot partition, data loss prevention, consumer offset, topic retention, observability, SRE, Kafka alerting.

Source and docs: https://github.com/themoah/klag
