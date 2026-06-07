---
title: Group Filtering
description: Control which Kafka consumer groups Klag monitors using glob include and exclude patterns.
---

By default Klag monitors **every** consumer group. On large or noisy clusters you
usually want to narrow that down. Two settings control it:

| Variable | Default | Meaning |
|---|---|---|
| `METRICS_GROUP_FILTER` | `*` | Comma-separated glob **include** patterns. |
| `METRICS_GROUP_EXCLUDE` | _(empty)_ | Comma-separated glob **exclude** patterns. |

A group is monitored **iff** it matches **any** include segment **and** **no** exclude
segment.

## Examples

Include only groups starting with `ingest` or `categorize`:

```bash
METRICS_GROUP_FILTER=ingest*,categorize*
```

Monitor everything except debug, canary, and shadow groups:

```bash
METRICS_GROUP_FILTER=*
METRICS_GROUP_EXCLUDE=debug-*,canary-*,*-shadow
```

## Filtering vs. ACLs

`METRICS_GROUP_FILTER` and `METRICS_GROUP_EXCLUDE` are **application-level** filters.
Cluster `DESCRIBE` permission is always required because `listConsumerGroups()` queries
all groups before filtering happens. See [ACL Permissions](/kafka/acl-permissions/).

## Reducing broker load

Filtering reduces the number of `describe`/offset calls Klag makes. For very large
clusters, also tune `KAFKA_CHUNK_COUNT` and `KAFKA_CHUNK_DELAY_MS` (see the
[Configuration Reference](/configuration/reference/)) to spread requests over time and
avoid spiking broker CPU.
