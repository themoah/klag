---
title: Label Cardinality
description: How Klag's consumer member labels, group/topic filters, and cluster tags multiply Prometheus series, and when to keep or drop them.
---

Klag is cheap to run and light on the brokers, but the series it exports grow with the
combinations of labels you keep. This page maps out the three things that drive
cardinality, and when to switch each one off.

Series count for a consumer-owned, per-partition metric is roughly:

```
series ≈ (monitored groups × partitions × consumer instances per group) × clusters
```

Every extra label that distinguishes series multiplies that number.

## Consumer member labels

By default, Klag tags the consumer-owned per-partition metrics
(`klag.consumer.lag`, `klag.consumer.lag.ms`, `klag.consumer.committed_offset`) with
`member_host`, `consumer_id`, and `client_id` so you can pin a partition's lag to the
specific consumer instance (or pod) that owns it. These are
[on by default](/metrics/overview/) to match kafka-lag-exporter.

That means a partition's series is multiplied by the number of consumer instances, and
a partition's **owner changes on every rebalance** — `consumer_id` rotates per session.
When a partition reassigns, the old instance's series are retired within one or two
scrape intervals, so churn is bounded rather than cumulative. On a large or frequently
rebalancing cluster, that multiplied, churning set of series is the dominant cost.

## When to disable member labels

Keep them when you actually trace lag to a specific consumer instance or pod. Drop them
when you only care about group/topic-level lag and the multiplied, churning series are
noisy or expensive. To drop them and keep only `consumer_group` / `topic` /
`partition`:

```bash
CONSUMER_MEMBER_LABELS_ENABLED=false
```

Doing this at the source is cheaper than a Prometheus `labeldrop` rule for the same
names: `labeldrop` still scrapes every series across the wire before deleting it, so
you pay the cardinality cost on the Prometheus side regardless. The partition-level
`klag.partition.log_end_offset` / `log_start_offset` metrics never carry member labels
either way — they describe the partition, not a consumer.

The [migration guide](/getting-started/migrating-from-kafka-lag-exporter/#member-labels-and-cardinality)
covers the same switch as part of moving from kafka-lag-exporter.

## Group and topic filters

Filters cut cardinality from a different direction — they reduce how many groups (and,
once topic filtering lands, topics) Klag monitors at all:

- **Group include/exclude.** `METRICS_GROUP_FILTER` (include globs) and
  `METRICS_GROUP_EXCLUDE` (exclude globs) decide which consumer groups are monitored.
  A group is exported **iff** it matches any include segment **and** no exclude
  segment. See [Group Filtering](/configuration/group-filtering/).
- **Topic filtering.** Klag does not (yet) filter by topic — see [topic
  include/exclude filtering](https://github.com/themoah/klag/issues/86). Until it
  lands, every monitored group's topics are exported; narrow the groups instead, or
  keep `METRICS_ASSIGNED_TOPICS_ONLY=true` so stable groups skip topics with no member
  assignment.

Filtering is application-level: Klag still issues `listConsumerGroups()` with `DESCRIBE`
permission, so the filters reduce exported series, not the permission you need.

## Cluster tagging

`KAFKA_CLUSTER_NAME` adds a `cluster_name` tag to Kafka series, and each entry in
`KAFKA_CLUSTERS` contributes its `name` as `cluster_name`. If you scrape N clusters,
cardinality multiplies by N (one `cluster_name` value per cluster). JVM and process
metrics do **not** carry `cluster_name`.

When you compare same-named consumer groups across clusters, keep `cluster_name` in your
aggregations (`sum by (cluster_name, consumer_group) (...)`) so series from different
clusters do not collapse into one. See the [Configuration
Reference](/configuration/reference/) for `KAFKA_CLUSTER_NAME` and `KAFKA_CLUSTERS`.

## Quick decision

| Signal | What it multiplies | Turn off when |
|---|---|---|
| `CONSUMER_MEMBER_LABELS_ENABLED` (default `true`) | consumer instances × partitions | you only need group/topic lag, not per-pod |
| `METRICS_GROUP_FILTER` / `_EXCLUDE` | monitored groups | you do not need the group's series at all |
| topic filtering ([#86](https://github.com/themoah/klag/issues/86), once available) | topics per group | you only care about a subset of topics |
| `KAFKA_CLUSTER_NAME` / `KAFKA_CLUSTERS[].name` | clusters | you run a single unnamed cluster and drop it from queries |

Whichever combination you pick, prefer dropping series at the source (the flags above)
over dropping them in Prometheus with `labeldrop`, which still scrapes the full series
first.
