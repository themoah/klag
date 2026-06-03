---
title: Lag Velocity
description: How Klag measures whether consumer lag is growing or shrinking over time, so you can catch problems before they escalate.
---

A single lag number tells you where a consumer is **right now**. **Lag velocity** tells
you where it's **heading** — whether the consumer is falling behind or catching up.

## The metric

| Metric | Description |
|---|---|
| `klag.consumer.lag.velocity` | Rate of change of lag in **messages/second × 100**. |

- **Positive** velocity → lag is growing, the consumer is falling behind.
- **Negative** velocity → lag is shrinking, the consumer is catching up.
- **Near zero** → lag is stable.

The value is multiplied by 100 so fractional messages/second survive integer-style
metric pipelines.

## Why it matters

Lag that is high but **shrinking** is often fine — the consumer is recovering from a
blip. Lag that is **low but growing fast** can be the early warning of an outage. Alert
on velocity, not just absolute lag, to catch problems while there is still time to act.

## Related: lag trend for AI agents

The [MCP endpoint](/ai/mcp/) derives a coarse **lag trend** (`growing` / `shrinking` /
`stable`) per topic plus an `overallTrend` rollup from this velocity, using the
`LAG_TREND_DEADBAND_MSG_PER_SEC` dead-band (default `1.0`). Velocity within the band is
classified STABLE.
