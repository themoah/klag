---
title: MCP Endpoint
description: Klag's opt-in, read-only MCP endpoint lets AI agents query consumer lag, find lagging groups, and run composite diagnose checks, served from an in-memory snapshot.
---

Klag exposes an optional **MCP** (Model Context Protocol) endpoint so AI agents (SRE
copilots, dev assistants) can query consumer-lag state in natural workflows.

:::note[Two different MCP servers]
This page is about the endpoint on **your Klag instance**, which answers questions about
your Kafka consumer groups. klag.dev separately hosts a read-only **documentation** MCP
server at `https://klag.dev/mcp`, which answers questions about Klag itself — config keys,
metrics, deployment. See [Developers](/developers/) for that one.
:::

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
collection runs. When more than one Kafka cluster is configured, the snapshot is the
**first** cluster only.

## Transport

Streamable HTTP, **JSON-RPC 2.0 over POST**. A `GET` returns `405`.

The full HTTP surface, including this endpoint, is published as an OpenAPI 3.1 spec at
[`klag.dev/openapi.json`](https://klag.dev/openapi.json).

## Tools

| Tool | Arguments | Response data |
|---|---|---|
| `list_consumer_groups` | None | Snapshot age and group count; each group has `group`, `state`, `totalLag`, `overallTrend`, and topic count. |
| `get_consumer_group_lag` | Required `group` | Group totals and state; partition offsets and lag; velocity, trends, transitions, topic-level time lag, time-to-close, retention risk, and `commitStalenessSeconds`. |
| `find_lagging_groups` | Optional `sortBy` (`lag`, `velocity`, or `retention`) and `limit` (default 10) | Ranked groups with state, total lag, trend, maximum velocity, maximum retention percentage, and `commitStalenessSeconds`. |
| `diagnose` | Required `group` | Overall `severity`, `summary`, and findings with `severity`, `title`, and `detail`. |

The tool payloads use these fields:

- `list_consumer_groups`: `snapshotAgeMs`, `groupCount`, and `groups`. Each group has
  `group`, `state`, `totalLag`, `overallTrend`, and `topics`.
- `get_consumer_group_lag`: `group`, `state`, `totalLag`, `maxLag`, `minLag`,
  `partitions`, `velocity`, `trends`, `overallTrend`, `recentTransitions`, `lagMs`,
  `timeToClose`, `retentionRisk`, and `commitStalenessSeconds`. Partition entries include
  `topic`, `partition`, `lag`, `committedOffset`, `logEndOffset`, and `logStartOffset`.
  Velocity entries have `topic` and `messagesPerSec`; trend entries have `topic`,
  `direction`, and `velocity`; transitions have `from`, `to`, `timestampMs`, and
  `ageMs`; time-lag entries have `topic`, `lagMs`, and `lagMessages`; time-to-close
  entries have `topic` and `estimatedSeconds`; retention entries have `topic` and
  `percent`.
- `find_lagging_groups`: `sortBy`, `limit`, and `groups`. Each result has `group`,
  `state`, `totalLag`, `overallTrend`, `maxVelocity`, `maxRetentionPercent`, and
  `commitStalenessSeconds`.
- `diagnose`: `group`, `severity`, `summary`, and `findings`. Each finding has
  `severity`, `title`, and `detail`.

`commitStalenessSeconds` is the maximum across the group's lagging topics. `-1`
means no staleness value is available. Klag infers this value from observed offset
changes; it resets when Klag restarts. The raw value appears in
`get_consumer_group_lag` and `find_lagging_groups`, not in the `diagnose` response.

## Trends and state history

Each group snapshot carries a **basic lag trend** (`growing` / `shrinking` / `stable`,
per-topic plus an `overallTrend` rollup) derived from
[lag velocity](/metrics/lag-velocity/) via `LAG_TREND_DEADBAND_MSG_PER_SEC`, and a
rolling **state-change history** (last 10 `from→to` transitions). This history is not
time-windowed. `diagnose` raises a state-churn warning after three retained transitions,
but those transitions may be far apart; the warning then remains until Klag restarts or
the group is cleaned up. Treat it as a triage prompt, not objective proof of frequent
changes or a rebalance storm, and inspect `recentTransitions` timestamps and ages.

## Diagnose checks and severity

`diagnose` runs deterministic checks against the latest group snapshot. Overall
severity is the highest finding in this order: `OK`, `INFO`, `WARNING`, `CRITICAL`.
When no check produces a finding, it returns one `OK` finding.

| Check | Finding |
|---|---|
| Group state | `DEAD` is `CRITICAL`; `EMPTY` is `WARNING`; rebalancing and `UNKNOWN` are `INFO`; `STABLE` adds no finding. |
| State churn | Three retained transitions is `WARNING`. The retained history is not time-windowed, so inspect `recentTransitions` before concluding that changes are frequent or constitute a rebalance storm. |
| Retention risk | At least 100% is `CRITICAL`; 80% to below 100% is `WARNING`. |
| ISR | Each under-replicated partition consumed by the group is `WARNING`; zero in-sync replicas is `CRITICAL`. |
| Growing lag | Positive topic velocity while total group lag is above zero is `WARNING`. |
| Catching up | Negative velocity while total group lag is above 100 messages is `INFO`; the detail includes time-to-close when available. |
| Hot partition | Each lag outlier in `hotPartitionsByLag` is `WARNING`. |
| Size skew | Each consumed topic with retained-size max/mean ≥ 2.0 is `WARNING` (requires `DATA_SKEW_ENABLED`). |
| Stuck consumer | Total lag above zero plus `commitStalenessSeconds >= 300` is `WARNING`. |

The stuck-consumer threshold is fixed at five minutes for `diagnose`. Alert on
`klag.consumer.commit.staleness_seconds` if you need another threshold. ISR remains a
partition-level signal; `diagnose` only includes under-replicated partitions present in
the selected group's consumed partition set.

## Raw JSON-RPC example

Clients normally perform initialization and tool discovery. Klag's handler also accepts
this minimal direct `tools/call`, the same request shape covered by its HTTP integration
tests:

```bash
curl -sS http://localhost:8888/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_consumer_groups","arguments":{}}}'
```

If `MCP_AUTH_TOKEN` is empty, omit the `Authorization` header. A representative
response has the MCP tool result in `result.content[0].text`; that text is a JSON
string containing the tool-specific payload:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\n  \"snapshotAgeMs\" : 125,\n  \"groupCount\" : 1,\n  \"groups\" : [ {\n    \"group\" : \"payments\",\n    \"state\" : \"stable\",\n    \"totalLag\" : 60,\n    \"overallTrend\" : \"growing\",\n    \"topics\" : 1\n  } ]\n}"
      }
    ],
    "isError": false
  }
}
```

## Connect from an AI client

Klag speaks standard **Streamable HTTP MCP** (JSON-RPC 2.0 over POST). Any client that
supports remote/HTTP MCP servers can connect — there's no Klag-specific SDK. You need
one thing: the endpoint URL, `http://<host>:8888/mcp` by default (`HTTP_PORT` +
`MCP_PATH`). If `MCP_AUTH_TOKEN` is set, add an `Authorization: Bearer <token>` header.
Klag implements MCP protocol version `2025-11-25`.

Use `https://` and a token for anything outside localhost.

:::caution
The snippets below use `Bearer <token>` placeholders. Project-level files like `.mcp.json`
and `.cursor/mcp.json` are often committed to source control — don't put a real token
there. Prefer an untracked global config (`~/.cursor/mcp.json`), your client's
environment-variable/secret interpolation, or a git-ignored file.
:::

### Claude Code

```bash
claude mcp add --transport http klag https://klag.example.com/mcp \
  --header "Authorization: Bearer <token>"
```

Or add it to a project `.mcp.json`:

```json
{
  "mcpServers": {
    "klag": {
      "type": "http",
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

### Cursor

Add to `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global):

```json
{
  "mcpServers": {
    "klag": {
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

### Codex

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.klag]
url = "https://klag.example.com/mcp"
bearer_token_env_var = "KLAG_MCP_TOKEN"
```

`bearer_token_env_var` names the environment variable that holds the token, so it is never
written to `config.toml` — export it before launching Codex. The inline form
`http_headers = { Authorization = "Bearer <token>" }` works too, but stores the token on disk.

Older Codex builds only spoke stdio and needed the `mcp-remote` bridge
(`command = "npx"`, `args = ["mcp-remote", "https://klag.example.com/mcp"]`). Check your
Codex version's MCP docs if the `url` form isn't recognized.

### GitHub Copilot

**VS Code** — add to `.vscode/mcp.json` (workspace) or your user MCP config. VS Code uses
the top-level key `servers`, not `mcpServers`. Prefer `${input:...}` for tokens:

```json
{
  "inputs": [
    {
      "type": "promptString",
      "id": "klag-mcp-token",
      "description": "Klag MCP token",
      "password": true
    }
  ],
  "servers": {
    "klag": {
      "type": "http",
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer ${input:klag-mcp-token}" }
    }
  }
}
```

Switch Copilot Chat to **Agent** mode and confirm the server appears under the tools picker.

**Copilot CLI** — add to `~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "klag": {
      "type": "http",
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" },
      "tools": ["*"]
    }
  }
}
```

Or from the terminal:

```shell
copilot mcp add --transport http \
  --header "Authorization: Bearer <token>" \
  klag https://klag.example.com/mcp
```

Visual Studio and JetBrains IDEs use the same `servers` object in `mcp.json`; remote
servers can also use `requestInit.headers` instead of `headers`.

### OpenCode

Add to `~/.config/opencode/opencode.json` (global) or `opencode.json` in your project.
OpenCode v2 nests servers under `mcp.servers`. Set `oauth: false` when Klag uses a bearer
token — otherwise OpenCode may attempt OAuth discovery first:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "klag": {
        "type": "remote",
        "url": "https://klag.example.com/mcp",
        "oauth": false,
        "headers": {
          "Authorization": "Bearer {env:KLAG_MCP_TOKEN}"
        }
      }
    }
  }
}
```

Use `{env:VAR}` for secret interpolation. Older OpenCode builds place servers directly
under `mcp` with the same fields.

### Kilo Code

Open the MCP settings (`mcp_settings.json`) and add:

```json
{
  "mcpServers": {
    "klag": {
      "type": "streamable-http",
      "url": "https://klag.example.com/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

:::note
MCP clients evolve quickly and config field names change between versions. If a snippet
above doesn't match your client, consult its current MCP docs — Klag only requires a
Streamable-HTTP JSON-RPC POST endpoint, so any correct remote-MCP config will work.
:::

Once connected, ask the agent to `list_consumer_groups`, `find_lagging_groups`, or
`diagnose` a specific group.

For `401`, `405`, or snapshot-not-ready responses, see
[Troubleshooting](/guides/troubleshooting/#mcp-401-405-or-empty-snapshot).

## Design

The MCP layer reads from a `SnapshotStore` populated by the metrics collector, never
from direct Kafka calls. See the design doc:
[`docs/superpowers/specs/2026-06-01-mcp-support-design.md`](https://github.com/themoah/klag/blob/main/docs/superpowers/specs/2026-06-01-mcp-support-design.md).
