---
title: Data Loss Prevention
description: Klag's retention-percent metric warns you before consumer lag exceeds Kafka retention and messages are permanently lost.
---

The most dangerous kind of lag is the kind that crosses your **retention window**. Once
a consumer falls further behind than Kafka retains, the oldest unread messages are
deleted — gone, unrecoverable. Klag warns you **before** that happens.

## The metric

| Metric | Description |
|---|---|
| `klag.consumer.lag.retention_percent` | Percentage of the retention window consumed by lag (value × 100 for precision). |

Tagged with `consumer_group` and `topic`. Empty partitions are excluded.

## Formula

```
retention_percent = (lag / (logEndOffset - logStartOffset)) * 100
```

- **A rising value** means the consumer is eating into its safety margin.
- **100%** means the consumer is at or behind `logStartOffset` — **data loss has
  already occurred**.

## Why offsets, not time

Retention in Kafka is enforced by the broker deleting old segments. Comparing lag to the
actual span of **available** offsets (`logEndOffset − logStartOffset`) measures the real,
current safety margin — more reliable than assuming a fixed time-based retention.

## Alerting

Alert when `retention_percent` crosses a threshold well below 100 (e.g. 70–80%) to give
operators time to scale consumers or intervene before messages are lost. The
[Grafana dashboard](/integrations/grafana-dashboard/) includes retention-risk panels and
an at-risk topics table.
