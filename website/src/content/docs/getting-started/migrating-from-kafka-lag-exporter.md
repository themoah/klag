---
title: Migrating from kafka-lag-exporter
description: Map kafka-lag-exporter metrics, labels, units, and configuration to Klag, including the dashboard and relabeling changes required during migration.
---

[kafka-lag-exporter](https://github.com/seglo/kafka-lag-exporter) was archived in 2024.
Klag is a maintained replacement for consumer-progress monitoring. Both read offsets
through Kafka's Admin API, but their metric names, labels, aggregation levels, and
time-lag units differ.

Use the mapping below, then update Prometheus relabeling, dashboards, and alerts before
cutting traffic over.

## Metric name mapping

Klag exports through Micrometer; the Prometheus names below are what you scrape (dots
become underscores):

| kafka-lag-exporter | Klag (Prometheus name) | Notes |
|---|---|---|
| `kafka_consumergroup_group_lag` | `klag_consumer_lag` | per partition |
| `kafka_consumergroup_group_topic_sum_lag` | `klag_consumer_lag_sum` | per group+topic |
| `kafka_consumergroup_group_sum_lag` | `sum by (consumer_group)(klag_consumer_lag_sum)` | Klag derives the group total from topic rollups |
| `kafka_consumergroup_group_max_lag` | `klag_consumer_lag_max` | per group+topic; group max: `max by (consumer_group)(klag_consumer_lag_max)` |
| `kafka_consumergroup_group_offset` | `klag_consumer_committed_offset` | committed offset |
| `kafka_partition_latest_offset` | `klag_partition_log_end_offset` | partition end |
| `kafka_partition_earliest_offset` | `klag_partition_log_start_offset` | partition start |
| `kafka_consumergroup_group_lag_seconds` | `klag_consumer_lag_ms` | Klag reports milliseconds; divide by 1000 for seconds |
| `kafka_consumergroup_group_max_lag_seconds` | `max by (consumer_group) (klag_consumer_lag_ms{partition=""}) / 1000` | Klag exposes topic-level max rollups separately from partition series |

The archived exporter has no minimum-lag aggregate. Klag's
`klag_consumer_lag_min` therefore has no source metric to map.

See the full [Metrics Overview](/metrics/overview/) for everything Klag adds on top.

## Label mapping

The biggest change is the group label name:

| kafka-lag-exporter | Klag |
|---|---|
| `cluster_name` | `cluster_name` when `KAFKA_CLUSTER_NAME` or `KAFKA_CLUSTERS[].name` is set; otherwise omit (or add a Prometheus external label). |
| `group` | `consumer_group` |
| `topic` | `topic` |
| `partition` | `partition` |
| `member_host` | `member_host` |
| `consumer_id` | `consumer_id` |
| `client_id` | `client_id` |
| `is_simple_consumer` | No Klag equivalent. |

Klag exports group state as the `state` tag on a separate
`klag_consumer_group_state` metric.

The per-consumer-instance labels `member_host`, `consumer_id`, and `client_id` are **on by
default** in Klag and identify which consumer instance owns each partition (the same labels you used
to trace lag to a specific pod). They ride on per-partition `klag_consumer_lag`,
`klag_consumer_lag_ms`, and `klag_consumer_committed_offset` series. The topic-level
`klag_consumer_lag_ms` aggregate is not member-tagged because it is a rollup across partitions.

### Relabel during the transition

If your Grafana panels and alert rules reference `group=`, add a Prometheus relabel rule to alias
`consumer_group` to `group` while you update queries:

```yaml
scrape_configs:
  - job_name: klag
    metric_relabel_configs:
      - source_labels: [consumer_group]
        target_label: group
```

This rule only aliases the label. You still need to replace metric names, adjust group
and topic aggregations, and convert time lag from milliseconds to seconds where old
queries expect seconds. Once dashboards use `consumer_group`, remove the rule.

## Member labels and cardinality

Member labels multiply series by the number of consumer instances, and a partition's owner changes
on every rebalance (`consumer_id` rotates per session). Klag retires the old instance's series
within one or two scrape intervals, so churn is bounded — but on very large clusters the extra
cardinality adds up. To opt out and keep only `consumer_group` / `topic` / `partition`:

```bash
CONSUMER_MEMBER_LABELS_ENABLED=false
```

The partition-level `klag_partition_log_end_offset` / `log_start_offset` metrics never carry member
labels (they describe the partition, not a consumer), matching kafka-lag-exporter's
`kafka_partition_*`.

## Configuration mapping

kafka-lag-exporter uses a HOCON `application.conf`; Klag primarily uses environment
variables. Any `Env`-backed setting also accepts a JVM `-D` property (env wins when both
are set). Config precedence is classpath `application.properties`, then an external file
at `KLAG_CONFIG_FILE`, then environment variables. See the
[Configuration Reference](/configuration/reference/).

| kafka-lag-exporter (`application.conf`) | Klag |
|---|---|
| `kafka-lag-exporter.clusters[]` | `KAFKA_CLUSTERS` JSON array (`name`, `bootstrapServers`, optional `groupFilter` / `properties`) |
| `kafka-lag-exporter.clusters[].bootstrap-brokers` (single cluster) | `KAFKA_BOOTSTRAP_SERVERS` |
| `kafka-lag-exporter.clusters[].name` (single cluster) | `KAFKA_CLUSTER_NAME` |
| `kafka-lag-exporter.poll-interval` | `METRICS_INTERVAL_MS` |
| `kafka-lag-exporter.client-group-id` / consumer props | `KAFKA_*` (mapped to `kafka.*` AdminClient props) |
| group whitelist (`group-whitelist`) | `METRICS_GROUP_FILTER` (comma-separated globs) |
| group blacklist (`group-blacklist`) | `METRICS_GROUP_EXCLUDE` (comma-separated globs) |
| `port` (Prometheus) | `HTTP_PORT` (scrape at `/metrics`) |
| reporter selection | `METRICS_REPORTER=prometheus` (also `datadog`, `otlp`) |

A minimal Prometheus setup is shown below. Replace the broker example with an address
that resolves and is reachable from the Klag runtime:

```bash
KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092
METRICS_REPORTER=prometheus
METRICS_INTERVAL_MS=30000
```

Klag needs only `DESCRIBE` on the cluster, groups, and topics. See
[Kafka ACL Permissions](/kafka/acl-permissions/).
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
