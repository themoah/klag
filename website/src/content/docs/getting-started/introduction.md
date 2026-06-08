---
title: Introduction
description: What Klag is, why consumer lag matters, and the key features of this Kafka lag exporter.
---

**Klag** is a Kafka consumer lag exporter built with [Vert.x](https://vertx.io) and
[Micrometer](https://micrometer.io). It continuously monitors consumer lag and
consumer-group state across your cluster and exposes the data to Prometheus, Datadog,
or any OTLP-compatible backend.

It is inspired by [kafka-lag-exporter](https://github.com/seglo/kafka-lag-exporter)
(archived in 2024), rebuilt on a modern reactive stack.

## Why consumer lag matters

Consumer lag is the gap between what Kafka has **produced** and what your consumers
have **processed**. Left unmonitored, growing lag leads to:

- **Stale data** in downstream systems.
- **Memory pressure** as consumers struggle to catch up.
- **Silent failures** when consumer groups die without alerts.

Klag surfaces these problems early, with enough signal to act before users notice.

## Key features

| Feature | Why it matters |
|---|---|
| **Lag velocity** | Know if lag is growing or shrinking, to catch problems before they escalate. |
| **Time-based lag estimation** | See lag in seconds/minutes, beyond raw message counts. |
| **Hot partition detection** | Find partitions with uneven load causing bottlenecks. |
| **Consumer group state tracking** | Alert on Rebalancing, Dead, or Empty states. |
| **Request batching** | Safely monitor large clusters without overwhelming brokers. |
| **Stale group cleanup** | Automatically stops reporting deleted/inactive groups. |
| **Data loss prevention** | Catch the case where lag exceeds retention and data is lost. |

## Scales to large clusters

Klag monitors thousands of consumer groups in ~50 MB heap. Request batching with
configurable delays lets it fetch offsets for 500+ groups without spiking broker CPU.
See [Group Filtering](/configuration/group-filtering/) and the `KAFKA_CHUNK_COUNT` /
`KAFKA_CHUNK_DELAY_MS` settings in the [Configuration Reference](/configuration/reference/).

## Read-only by design

Klag requires **read-only** (DESCRIBE) access to Kafka, no write or alter permissions.
See [ACL Permissions](/kafka/acl-permissions/) for the exact grants on self-managed
Kafka and Confluent Cloud.

## Next steps

- [Quick Start](/getting-started/quick-start/): run Klag in one command.
- [Installation](/getting-started/installation/): Helm, Docker, and env-file setups.
- [Metrics Overview](/metrics/overview/): the full list of what Klag exposes.
- [Comparison](/getting-started/comparison/): Klag vs Burrow vs KMinion.
