---
title: Datadog
description: Ship Klag metrics directly to Datadog using the Datadog Micrometer registry.
---

Klag can submit metrics straight to Datadog via the Micrometer Datadog registry — no
Prometheus scrape required.

## Enable

Set the reporter to `datadog` and provide your Datadog API key and interval:

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
           -e METRICS_REPORTER=datadog \
           -e METRICS_INTERVAL_MS=30000 \
           themoah/klag:latest
```

Configure the Datadog API credentials and site as required by the Micrometer Datadog
registry (API key, application key, and Datadog site/region) through the standard
configuration channels — classpath properties, external file, or environment variables
(see the [Configuration Reference](/configuration/reference/)).

## Notes

- `METRICS_INTERVAL_MS` controls how often metrics are pushed.
- All [metrics](/metrics/overview/) carry `consumer_group`, `topic`, and `partition`
  tags where applicable, so you can build Datadog monitors per group or topic.
