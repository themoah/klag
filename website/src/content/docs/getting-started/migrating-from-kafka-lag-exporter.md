---
title: Migrating from kafka-lag-exporter
description: Map kafka-lag-exporter metrics, labels, and configuration to Klag, including drop-in dashboard compatibility and the per-consumer member labels.
---

[kafka-lag-exporter](https://github.com/seglo/kafka-lag-exporter) was archived in 2024. Klag is a
maintained, drop-in-shaped replacement: it reads the same data from Kafka (offsets via the Admin
API, `DESCRIBE`-only) and exposes the same core lag metrics, plus velocity, time-based lag, hot
partitions, retention/data-loss alerting, and an [MCP endpoint](/ai/mcp/) for AI agents.

This guide maps what you have today to Klag so existing dashboards and alerts keep working with
minimal edits.

## Metric name mapping

Klag exports through Micrometer; the Prometheus names below are what you scrape (dots become
underscores). The logical metrics match kafka-lag-exporter one-to-one:

| kafka-lag-exporter | Klag (Prometheus name) | Notes |
|---|---|---|
| `kafka_consumergroup_group_lag` | `klag_consumer_lag` | per partition |
| `kafka_consumergroup_group_lag` (summed) | `klag_consumer_lag_sum` | per group |
| `kafka_consumergroup_group_max_lag` | `klag_consumer_lag_max` | per group |
| `kafka_consumergroup_group_offset` | `klag_consumer_committed_offset` | committed offset |
| `kafka_partition_latest_offset` | `klag_partition_log_end_offset` | partition end |
| `kafka_partition_earliest_offset` | `klag_partition_log_start_offset` | partition start |
| `kafka_consumergroup_group_lag_seconds` | `klag_consumer_lag_ms` | **milliseconds**, divide by 1000 |
| `kafka_consumergroup_group_max_lag_seconds` | `klag_consumer_lag_ms` (max over topics) | see [Time-Based Lag](/metrics/time-based-lag/) |
| group state (label on lag) | `klag_consumer_group_state` | separate metric; state is a tag |

See the full [Metrics Overview](/metrics/overview/) for everything Klag adds on top.

## Label mapping

The biggest change is the group label name:

| kafka-lag-exporter | Klag |
|---|---|
| `group` | `consumer_group` |
| `topic` | `topic` |
| `partition` | `partition` |
| `member_host` | `member_host` |
| `consumer_id` | `consumer_id` |
| `client_id` | `client_id` |
| `state` (on lag) | `state` (on `klag_consumer_group_state`) |

The per-consumer-instance labels — `member_host`, `consumer_id`, `client_id` — are **on by
default** in Klag and identify which consumer instance owns each partition (the same labels you used
to trace lag to a specific pod). They ride on `klag_consumer_lag` and `klag_consumer_committed_offset`.

### Keep existing dashboards working

If your Grafana panels and alert rules reference `group=`, add a Prometheus relabel rule to alias
`consumer_group` → `group` so they keep working unchanged:

```yaml
scrape_configs:
  - job_name: klag
    metric_relabel_configs:
      - source_labels: [consumer_group]
        target_label: group
```

Once dashboards are updated to use `consumer_group`, drop the rule.

## Member labels and cardinality

Member labels multiply series by the number of consumer instances, and a partition's owner changes
on every rebalance (`consumer_id` rotates per session). Klag retires the old instance's series
within one or two scrape intervals, so churn is bounded — but on very large clusters the extra
cardinality adds up. To opt out and keep only `consumer_group` / `topic` / `partition`:

```bash
CONSUMER_MEMBER_LABELS_ENABLED=false
```

The partition-level `klag_partition_log_end_offset` / `log_start_offset` metrics never carry member
labels (they describe the partition, not a consumer), matching kafka-lag-exporter's `kafka_partition_*`.

## Configuration mapping

kafka-lag-exporter is configured with a HOCON `application.conf`; Klag uses environment variables
(or `-D` JVM properties / an external properties file — see the [Configuration Reference](/configuration/reference/)).

| kafka-lag-exporter (`application.conf`) | Klag |
|---|---|
| `kafka-lag-exporter.clusters[].bootstrap-brokers` | `KAFKA_BOOTSTRAP_SERVERS` |
| `kafka-lag-exporter.poll-interval` | `METRICS_INTERVAL_MS` |
| `kafka-lag-exporter.client-group-id` / consumer props | `KAFKA_*` (mapped to `kafka.*` AdminClient props) |
| group whitelist (`group-whitelist`) | `METRICS_GROUP_FILTER` (comma-separated globs) |
| group blacklist (`group-blacklist`) | `METRICS_GROUP_EXCLUDE` (comma-separated globs) |
| `port` (Prometheus) | `HTTP_PORT` (scrape at `/metrics`) |
| reporter selection | `METRICS_REPORTER=prometheus` (also `datadog`, `otlp`) |

A minimal Prometheus setup:

```bash
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
METRICS_REPORTER=prometheus
METRICS_INTERVAL_MS=30000
```

Klag needs only `DESCRIBE` on groups and topics — see [Kafka ACL Permissions](/kafka/acl-permissions/).
Deploy on Kubernetes with the [Helm chart](/deployment/kubernetes/).

## Time-based lag is different

kafka-lag-exporter estimates lag-in-time by extrapolating offset/timestamp samples. Klag reports
`klag_consumer_lag_ms` from Kafka's own log timestamps via interpolation (with a poll-history
fallback), so it does not extrapolate beyond observed data. It also adds a
`klag_consumer_lag_time_to_close_seconds` estimate while a group is catching up. See
[Time-Based Lag](/metrics/time-based-lag/).

## What Klag adds — and what it doesn't

**Adds:** [lag velocity](/metrics/lag-velocity/), [hot partition detection](/metrics/hot-partitions/),
[data-loss / retention alerting](/metrics/data-loss-prevention/), commit freshness, Datadog and OTLP
sinks, and a read-only [MCP endpoint](/ai/mcp/).

**Doesn't have:** kafka-lag-exporter had no built-in alerting rules or notifiers, and neither does
Klag — alert in Prometheus/Grafana on the exported metrics as before.
