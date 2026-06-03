---
title: Metrics Overview
description: The full catalog of metrics Klag exposes, covering consumer lag, offsets, group state, velocity, hot partitions, time-based lag, and data-loss prevention.
---

Klag exports its metrics through Micrometer, so the exact name format depends on the
reporter (Prometheus, Datadog, or OTLP). The logical metrics are the same everywhere.

All metrics are tagged with `consumer_group`, `topic`, and `partition` where applicable.

## Core lag and offsets

| Metric | Description |
|---|---|
| `klag.consumer.lag` | Current lag per partition (also `.sum`, `.max`, `.min`). |
| `klag.consumer.lag.velocity` | Rate of change; positive means falling behind. See [Lag Velocity](/metrics/lag-velocity/). |
| `klag.consumer.committed_offset` | Last committed offset per consumer. |
| `klag.partition.log_end_offset` | Latest offset per partition. |
| `klag.partition.log_start_offset` | Earliest available offset per partition. |
| `klag.topic.partitions` | Partition count per topic. |
| `klag.consumer.group.state` | Group health: Stable, Rebalancing, Dead, Empty. |

## Hot partitions

Reported **only when statistical outliers exist** (see [Hot Partitions](/metrics/hot-partitions/)):

| Metric | Description |
|---|---|
| `klag.hot_partition` | Partition throughput × 100 when statistically high. Tags: `topic`, `partition` only. |
| `klag.hot_partition.lag` | Partition lag when statistically high. |

## Time-based lag

See [Time-Based Lag](/metrics/time-based-lag/). Tags: `consumer_group`, `topic`.

| Metric | Description |
|---|---|
| `klag.consumer.lag.ms` | Lag in milliseconds, from Kafka log timestamps (poll-history fallback). |
| `klag.consumer.lag.time_to_close_seconds` | Estimated seconds until lag reaches zero (only when catching up). |

## Data loss prevention

See [Data Loss Prevention](/metrics/data-loss-prevention/). Tags: `consumer_group`, `topic`.

| Metric | Description |
|---|---|
| `klag.consumer.lag.retention_percent` | Lag as a percentage of the retention window (value × 100). 100% means data loss. |

## Optional JVM metrics

When `METRICS_JVM_ENABLED=true`, standard Micrometer JVM metrics (memory, GC, threads,
classes, CPU) are exported too, and visualized in the [Grafana dashboard](/integrations/grafana-dashboard/).
