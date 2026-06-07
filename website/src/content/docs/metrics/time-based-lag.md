---
title: Time-Based Lag
description: Klag estimates consumer lag in milliseconds and seconds-to-catch-up, beyond raw message counts, using Kafka log timestamps with a poll-history fallback.
---

Message-count lag is hard to reason about. "50,000 messages behind" means nothing
without throughput context. Klag also estimates lag in **time**.

## Metrics

| Metric | Description |
|---|---|
| `klag.consumer.lag.ms` | Lag in milliseconds: `currentTime − committedMessageTimestamp`. |
| `klag.consumer.lag.time_to_close_seconds` | Estimated seconds until lag reaches zero (only when catching up and lag > threshold). |

Both are tagged with `consumer_group` and `topic` (per-topic granularity).

## How `lag.ms` is computed

**Primary:** linear interpolation between Kafka `listOffsets` log start/end timestamps
and offsets, mapping the committed offset to a wall-clock timestamp.

**Fallback:** when Kafka timestamps are invalid (e.g. `logStartTimestamp=0`), Klag uses
a poll-time `(logEndOffset, systemTime)` history. The fallback requires 2+ poll
intervals and does **not** extrapolate beyond the oldest retained sample
(`TIME_LAG_INTERPOLATION_BUFFER_SIZE`).

## Time-to-close

`time_to_close_seconds` is only emitted when a consumer is **catching up** and lag
exceeds `TIME_LAG_MIN_MESSAGES` (default `100`). It answers "if things stay as they are,
how long until this consumer is caught up?".

## Configuration

See the [Configuration Reference](/configuration/reference/#time-based-lag-estimation):
`TIME_LAG_ENABLED`, `TIME_LAG_MIN_MESSAGES`, `TIME_LAG_INTERPOLATION_BUFFER_SIZE`,
`TIME_LAG_STALE_PRODUCER_THRESHOLD_MS`.
