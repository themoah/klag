---
title: Hot Partitions
description: Klag detects partitions with statistically abnormal throughput so you can find skewed load and bottlenecks within a topic.
---

Within a single topic, partitions should carry roughly even load. When one partition
runs much hotter than its peers — usually from a skewed partition key — it becomes a
bottleneck. Klag detects these **statistical outliers**.

## Metrics

Reported **only when an outlier exists** (so they stay quiet on healthy topics):

| Metric | Description |
|---|---|
| `klag.hot_partition` | Partition throughput × 100 when statistically high. |
| `klag.hot_partition.lag` | Partition lag on a hot partition specifically. |

`klag.hot_partition` has only `topic` and `partition` tags — throughput is
partition-level and independent of any consumer.

## How detection works

For each topic, Klag computes per-partition throughput over a rolling sample buffer and
flags partitions whose throughput exceeds the mean by more than
`HOT_PARTITION_SIGMA_MULTIPLIER` standard deviations.

Detection only runs when there is enough data to be meaningful:

| Variable | Default | Role |
|---|---|---|
| `HOT_PARTITION_ENABLED` | `true` | Master switch. |
| `HOT_PARTITION_SIGMA_MULTIPLIER` | `2.0` | Std-devs above mean to flag an outlier. |
| `HOT_PARTITION_MIN_PARTITIONS` | `3` | Min partitions per topic before detection runs. |
| `HOT_PARTITION_MIN_SAMPLES` | `3` | Min samples needed for a throughput estimate. |
| `HOT_PARTITION_BUFFER_SIZE` | `20` | Samples retained per partition. |

## Acting on it

A hot partition usually points at a partitioning-key problem in the producer. Use the
[Grafana dashboard](/integrations/grafana-dashboard/) hot-partition panels to spot which
`topic`/`partition` is skewed, then rebalance the key or repartition the topic.
