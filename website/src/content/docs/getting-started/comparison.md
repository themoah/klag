---
title: Comparison
description: How Klag compares to Burrow and KMinion for Kafka consumer lag monitoring.
---

Klag, [Burrow](https://github.com/linkedin/Burrow), and [KMinion](https://github.com/redpanda-data/kminion)
all monitor Kafka consumer lag. They differ in what they measure and where they send it.

## At a glance

| | **Klag** | **Burrow** | **KMinion** |
|---|---|---|---|
| Runtime | Java / Vert.x / GraalVM | Go | Go |
| Primary output | Prometheus, Datadog, OTLP | HTTP API + notifiers | Prometheus |
| License | Apache 2.0 | Apache 2.0 | MIT |

## Feature comparison

| Feature | Klag | Burrow | KMinion |
|---|:---:|:---:|:---:|
| Lag in messages | ✅ | ✅ | ✅ |
| Consumer group state | ✅ | ✅ | ✅ |
| Lag velocity (growing/shrinking) | ✅ | ⚠️ status only | ❌ |
| AI agent endpoint (MCP) | ✅ | ❌ | ❌ |
| Time-based lag (seconds/minutes) | ✅ | ❌ | ❌ |
| Time-to-catch-up estimate | ✅ | ❌ | ❌ |
| Hot partition detection | ✅ | ❌ | ❌ |
| Data loss / retention alerting | ✅ | ❌ | ❌ |
| Lag status evaluation rules | ❌ | ✅ | ❌ |
| Built-in notifiers (email/Slack/HTTP) | ❌ | ✅ | ❌ |
| Broker / topic / log-dir metrics | ⚠️ partial | ❌ | ✅ |
| Prometheus native | ✅ | ⚠️ separate exporter | ✅ |
| Datadog / OTLP native | ✅ | ❌ | ❌ |
| Read-only ACLs (DESCRIBE only) | ✅ | ✅ | ⚠️ writes for e2e |
| Helm chart | ✅ | community | community |


> Spotted something out of date? Burrow and KMinion both evolve. Open an issue or PR
> against [`website/`](https://github.com/themoah/klag/tree/main/website) and we'll fix it.
