---
title: MCP Endpoint
description: Klag's opt-in, read-only MCP endpoint lets AI agents query consumer lag, find lagging groups, and run composite diagnose checks, served from an in-memory snapshot.
---

Klag exposes an optional **MCP** (Model Context Protocol) endpoint so AI agents (SRE
copilots, dev assistants) can query consumer-lag state in natural workflows.

It is **opt-in, read-only, and zero-impact when off**. The endpoint serves an in-memory
snapshot the metrics collector publishes after each cycle; it never queries Kafka or
touches the collection flow.

## Enable

| Variable | Default | Description |
|---|---|---|
| `MCP_ENABLED` | `false` | Expose the `/mcp` endpoint. |
| `MCP_AUTH_TOKEN` | _(empty)_ | When set, requires `Authorization: Bearer <token>`. Empty = open (logged warning). |
| `MCP_PATH` | `/mcp` | HTTP path of the endpoint. |

MCP requires `METRICS_REPORTER` to be set. The snapshot is only populated when metrics
collection runs.

## Transport

Streamable HTTP, **JSON-RPC 2.0 over POST**. A `GET` returns `405`.

## Tools

| Tool | Purpose |
|---|---|
| `list_consumer_groups` | List groups, each with its `overallTrend`. |
| `get_consumer_group_lag` | Lag detail for a group, plus `trends`, `overallTrend`, and `recentTransitions`. |
| `find_lagging_groups` | Groups currently lagging, with `overallTrend`. |
| `diagnose` | Composite severity assessment; flags frequent state changes (rebalance storm / flapping). |

## Trends and state history

Each group snapshot carries a **basic lag trend** (`growing` / `shrinking` / `stable`,
per-topic plus an `overallTrend` rollup) derived from
[lag velocity](/metrics/lag-velocity/) via `LAG_TREND_DEADBAND_MSG_PER_SEC`, and a
rolling **state-change history** (last 10 `from→to` transitions). `diagnose` uses the
transition history to flag rebalance storms and flapping groups.

## Design

The MCP layer reads from a `SnapshotStore` populated by the metrics collector, never
from direct Kafka calls. See the design doc:
[`docs/superpowers/specs/2026-06-01-mcp-support-design.md`](https://github.com/themoah/klag/blob/main/docs/superpowers/specs/2026-06-01-mcp-support-design.md).
