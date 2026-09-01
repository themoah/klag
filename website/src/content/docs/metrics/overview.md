---
title: Metrics Overview
description: The full catalog of metrics Klag exposes, covering consumer lag, offsets, group state, velocity, hot partitions, time-based lag, data-loss prevention, ISR, and topic data skew.
---

Klag exports its metrics through Micrometer, so the exact name format depends on the
reporter (Prometheus, Datadog, or OTLP). The logical metrics are the same everywhere.

Tags depend on the metric's scope. Consumer metrics commonly use `consumer_group`,
topic-level metrics add `topic`, and only partition-level series add `partition`.
Broker/topic signals such as throughput and ISR do not have a `consumer_group` tag.
When `KAFKA_CLUSTER_NAME` or a `KAFKA_CLUSTERS` entry `name` is set, Kafka series
also carry `cluster_name`. JVM and process metrics do not.

## Core lag and offsets

| Metric | Description |
|---|---|
| `klag.consumer.lag` | Current lag per partition (also `.sum`, `.max`, `.min`). |
| `klag.consumer.lag.velocity` | Rate of change; positive means falling behind. See [Lag Velocity](/metrics/lag-velocity/). |
| `klag.consumer.committed_offset` | Last committed offset per partition. |
| `klag.partition.log_end_offset` | Latest offset per partition. |
| `klag.partition.log_start_offset` | Earliest available offset per partition. |
| `klag.topic.partitions` | Partition count per topic. |
| `klag.consumer.group.state` | Consumer-group state and observed state-change count. |

### Consumer-group state

`klag.consumer.group.state` has `consumer_group` and a lowercase `state` tag. The
possible values are `stable`, `preparing_rebalance`, `completing_rebalance`, `assigning`,
`reconciling`, `empty`, `dead`, and `unknown`. There is no generic `rebalancing` value.

Groups on the KIP-848 consumer protocol (Kafka 4.0+) report `assigning` and `reconciling`
in place of `preparing_rebalance` and `completing_rebalance`. Alerts that name only the
classic pair go silent on upgraded groups — match all four:

```promql
klag_consumer_group_state{state=~"preparing_rebalance|completing_rebalance|assigning|reconciling"}
```

The gauge value is a consecutive state-change count, **not** an encoded state or a
lifetime cumulative total. It starts at `0`, rises while the state changes on
back-to-back collections, and resets to `0` on an unchanged collection. Select state by
its tag rather than comparing the gauge to a state number.
After a transition, two-phase stale-series cleanup can leave the previous state-tagged
series visible for one or two collection intervals.

`klag.consumer.lag`, per-partition `klag.consumer.lag.ms`, and
`klag.consumer.committed_offset` also carry `member_host`, `consumer_id`, and `client_id` tags
identifying the consumer **instance** that owns each partition — handy for pinning lag to a
specific pod. Unowned partitions (Empty/Dead groups) get empty-string values. Disable with
`CONSUMER_MEMBER_LABELS_ENABLED=false` to cut cardinality (cheaper than Prometheus
`labeldrop` of the same names, which still scrapes the series first). Topic-level `lag.ms` aggregates and
partition-level `klag.partition.log_*_offset` metrics stay member-agnostic.

## Hot partitions

Reported **only when statistical outliers exist** (see [Hot Partitions](/metrics/hot-partitions/)):

| Metric | Description |
|---|---|
| `klag.hot_partition` | Partition throughput × 100 when statistically high. Tags: `topic`, `partition` only. |
| `klag.hot_partition.lag` | Partition lag when statistically high. Tags: `consumer_group`, `topic`, `partition`. |

## Under-replicated partitions (ISR)

Reported **only when a partition is under-replicated** (see [ISR Monitoring](/metrics/isr/)):

| Metric | Description |
|---|---|
| `klag.partition.under_replicated` | Missing in-sync replica count (`replicaCount - inSyncReplicaCount`). Tags: `topic`, `partition` only. |

## Topic data skew

Opt-in via `DATA_SKEW_ENABLED` (default `false`). See [Topic Data Skew](/metrics/data-skew/):

| Metric | Description |
|---|---|
| `klag.topic.size_skew` | `max/mean` retained-size ratio × 100 (`retained = max(0, logEndOffset − logStartOffset)`). Tags: `topic`, plus `cluster_name` when the cluster is named. 100 = even; 200 = fullest partition holds 2× the average. |

## Time-based lag

See [Time-Based Lag](/metrics/time-based-lag/). Topic aggregate tags: `consumer_group`, `topic`.
Partition tags: `consumer_group`, `topic`, `partition`, plus optional member labels.

| Metric | Description |
|---|---|
| `klag.consumer.lag.ms` | Lag in milliseconds, from Kafka log timestamps (poll-history fallback). |
| `klag.consumer.lag.time_to_close_seconds` | Estimated seconds until lag reaches zero (only when catching up). |

## Commit staleness

| Metric | Description |
|---|---|
| `klag.consumer.commit.staleness_seconds` | Seconds since Klag last observed the committed-offset sum change for a lagging group and topic. Tags: `consumer_group`, `topic`. |

This metric is reported only while lag is greater than zero, so an idle, caught-up
consumer is not marked stale. Kafka does not expose a commit timestamp, so Klag observes
the sum of committed offsets across the group/topic's partitions. Any change to that
sum, including a rewind, resets the clock. When the group/topic catches up, Klag removes
the baseline; if lag resumes, the next observation establishes a new baseline. Restarting
Klag also resets observation. This is therefore an inferred signal, not the absolute age
of the latest Kafka commit. Disable collection with `COMMIT_FRESHNESS_ENABLED=false`. See
[Detect Stuck Consumers](/guides/detect-stuck-consumers/).

## Data loss prevention

See [Data Loss Prevention](/metrics/data-loss-prevention/). Topic aggregates have
`consumer_group` and `topic` and omit `partition`; per-partition series additionally
have `partition`. In PromQL, `{partition=""}` matches a missing label, so use it for
topic rollups and `{partition!=""}` for partition detail.

| Metric | Description |
|---|---|
| `klag.consumer.lag.retention_percent` | Lag as a percentage of the retention window, exported as percentage × 100 for precision. Raw `10000` represents 100% and the data-loss boundary. |

## Optional JVM metrics

When `METRICS_JVM_ENABLED=true`, standard Micrometer JVM metrics (memory, GC, threads,
classes, CPU) are exported too, and visualized in the [Grafana dashboard](/integrations/grafana-dashboard/).
