---
title: Topic Data Skew
description: Klag scores how uneven retained data is across a topic's partitions as a max/mean ratio, so you can alert on size imbalance that hot-partition throughput outliers miss.
---

Within a topic, partitions should hold roughly even amounts of data. When keys are
skewed, compaction is uneven, or some partitions sit idle, one partition can retain
far more messages than its peers. That is a different signal from
[hot partitions](/metrics/hot-partitions/), which flag **produce-rate** outliers.

Klag scores this from offsets it already fetches every cycle (`logEndOffset` and
`logStartOffset`) — no additional Kafka calls or ACLs.

## Metrics

Opt-in. Nothing is emitted until `DATA_SKEW_ENABLED=true`.

| Metric | Description |
|---|---|
| `klag.topic.size_skew` | `max(retained) / mean(retained)` × 100, where `retained = max(0, logEndOffset − logStartOffset)`. |

`klag.topic.size_skew` is tagged with `topic`, plus `cluster_name` when the cluster is
named. **100** means even; **200** means the
fullest partition holds twice the average. Grafana panels divide by 100 so you read
the ratio directly.

Topics with fewer than `DATA_SKEW_MIN_PARTITIONS` partitions (default 2) are skipped.
An all-empty topic scores 100 (balanced empty).

## Configuration

| Variable | Default | Role |
|---|---|---|
| `DATA_SKEW_ENABLED` | `false` | Master switch. Opt-in; ships off. |
| `DATA_SKEW_MIN_PARTITIONS` | `2` | Min partitions per topic before a score is emitted. |

On Kubernetes, set these via `extraEnv`:

```yaml
extraEnv:
  - name: DATA_SKEW_ENABLED
    value: "true"
```

## Acting on it

A high ratio usually means a partitioning-key problem, uneven compaction, or idle
partitions. Use the Grafana **Retained Messages by Partition** panel (built from
existing `klag.partition.log_end_offset` − `klag.partition.log_start_offset` gauges)
to see which partition is fat, then rebalance the key or repartition.

Klag's [`diagnose` MCP tool](/ai/mcp/) raises WARNING when a consumed topic's ratio
is at least 2.0. Alert on the raw gauge at any threshold.
