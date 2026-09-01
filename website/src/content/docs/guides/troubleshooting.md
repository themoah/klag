---
title: Troubleshooting
description: Diagnose missing metrics, Kafka readiness and ACL failures, filtering, MCP responses, and Prometheus ServiceMonitor discovery.
---

Monitoring Kafka consumer progress is essential in production. Start with Klag's logs,
`/healthz`, `/readyz`, and the reporter-specific endpoint or backend.

## `/metrics` returns 404

**Likely cause:** The bare application defaults `METRICS_REPORTER` to `none`, or you
selected the `datadog` or `otlp` push reporter. Klag registers `/metrics` only for
Prometheus.

**Fix:** Set `METRICS_REPORTER=prometheus` and restart Klag. The Helm chart already
defaults `metrics.reporter` to `prometheus`. See
[Prometheus](/integrations/prometheus/) and the
[Configuration Reference](/configuration/reference/#metrics).

## Metrics or the MCP snapshot are empty after startup

**Likely cause:** The first Kafka collection has not completed, Kafka access failed, or
no group remains after filtering. The collector starts one cycle immediately; velocity
and some derived metrics still need more samples.

If Kafka is unreachable at startup, Klag **stays running (degraded)** instead of
exiting — `/readyz` returns `503` until at least one cluster is connected.
This is intentional: the process tolerates a broker outage at boot like the health
monitor, so a transient Kafka blip does not crash-loop the pod.

**Fix:** Check the logs for a completed collection or Kafka errors. Verify
`KAFKA_BOOTSTRAP_SERVERS`, the [required ACLs](/kafka/acl-permissions/), and the
[group filters](/configuration/group-filtering/). Wait for a successful cycle. If the
cluster is large, allow longer than `METRICS_INTERVAL_MS`.

## `/readyz` returns 503

**Likely cause:** Every configured Kafka health check cannot complete
`describeCluster`. Common causes are an unreachable broker, bad TLS/SASL settings,
or missing cluster `DESCRIBE`. With several clusters, HTTP 200 means at least one
is up; inspect `clusters[]` in the `/readyz` JSON to see which names are
`disconnected`.

**Fix:** Test network and DNS access from the Klag container, then compare your Kafka
security variables with [Installation](/getting-started/installation/). Grant the
cluster ACL documented in [ACL Permissions](/kafka/acl-permissions/). `/healthz` can
still return 200 because it only checks that the process is running.

## Logs show Kafka authorization errors

**Likely cause:** The principal lacks `DESCRIBE` on the cluster, a consumer group, or a
topic. Group filtering does not remove the need for cluster `DESCRIBE`.

**Fix:** Grant the read-only permissions in
[ACL Permissions](/kafka/acl-permissions/). If you grant prefixed group or topic
access, align those prefixes with `METRICS_GROUP_FILTER`.

## No consumer groups appear

**Likely cause:** `METRICS_GROUP_FILTER` matches none of the group IDs, or
`METRICS_GROUP_EXCLUDE` removes every included group.

**Fix:** Temporarily use:

```bash
METRICS_GROUP_FILTER=*
METRICS_GROUP_EXCLUDE=
```

Then add patterns back one at a time. Excludes run after includes. See
[Group Filtering](/configuration/group-filtering/).

## Old series never disappear from `/metrics`

**Likely cause:** Stale-gauge cleanup only runs after a **complete** collection cycle. If
one group fails every cycle — a missing group `DESCRIBE` ACL, a wedged coordinator — the
cycle is permanently partial and cleanup never runs, so series for deleted groups, topics,
and rotated consumer members linger indefinitely. The log carries
`Failed to collect lag for group <id> (skipped this cycle)` and
`Collection cycle was partial` every interval, naming the group.

This is deliberate: cleaning up against an incomplete key set would delete live series.
Stale values beat deleted ones — but the freeze lasts as long as the failure does.

**Fix:** Grant the group the [required ACLs](/kafka/acl-permissions/), or drop it with
`METRICS_GROUP_EXCLUDE`. Cleanup resumes on the next complete cycle. The MCP snapshot is
unaffected: it keeps publishing the groups that did succeed.

## A deleted topic's series stay, or a live topic's series disappear

Klag filters each cycle's topic set against the cluster's topic list before requesting
metadata, because the Kafka admin call fails as a whole if any topic in the batch is
unknown — and a group's committed offsets outlive a deleted topic until
`offsets.retention.minutes` (7 days by default). Without the filter, one deleted topic
would keep every cycle partial, and cleanup frozen, for that long.

The cost is an ACL asymmetry: `listTopics` only returns topics the principal can see. If
Klag can read a group's committed offsets for a topic it cannot describe, that topic looks
deleted and its series are retired within 1–2 cycles. The log records
`Skipping N topic(s) absent from the cluster topic list`.

**Fix:** Grant topic `DESCRIBE` matching the group access you granted — see
[ACL Permissions](/kafka/acl-permissions/). Asymmetric grants are the only case where a
live topic goes missing this way.

## Collection cycles overrun the interval on large clusters

**Likely cause:** Each cycle issues one batched `listOffsets` per request type covering
every partition in scope. The Kafka AdminClient splits that per leader broker, and the
timestamp lookups it performs scale with **partitions per broker**, so a cluster with few
brokers and many partitions can approach `KAFKA_REQUEST_TIMEOUT_MS` (default 30000). The
log shows `Skipping collection tick: previous cycle still running`.

**Fix:** Set `KAFKA_CHUNK_COUNT` above 1. Chunking splits the work into partition-weighted
batches processed sequentially, with `KAFKA_CHUNK_DELAY_MS` between them. Raising
`METRICS_INTERVAL_MS` or `KAFKA_REQUEST_TIMEOUT_MS` also helps. See the
[Configuration Reference](/configuration/reference/#kafka).

## Velocity or time-lag metrics are missing at first

**Likely cause:** Lag velocity needs three collection samples. The time-lag fallback
needs at least two poll intervals when Kafka log timestamps cannot support primary
interpolation. Time-to-close also requires shrinking lag and at least
`TIME_LAG_MIN_MESSAGES` messages.

**Fix:** Wait for the required samples. Shorten `METRICS_INTERVAL_MS` only if Kafka can
handle the added polling load. See [Lag Velocity](/metrics/lag-velocity/) and
[Time-Based Lag](/metrics/time-based-lag/).

## MCP 401, 405, or empty snapshot

**Likely cause:**

- `401`: `MCP_AUTH_TOKEN` is set and the Bearer token is missing or wrong.
- `405`: The client sent `GET`; Klag accepts JSON-RPC 2.0 over `POST`.
- Snapshot not ready: metrics collection is disabled or the first cycle has not
  succeeded, so no snapshot exists yet.
- Snapshot empty (`groupCount: 0`): reports ran, but the group filters left no
  groups to collect. Each cycle publishes a refreshed empty snapshot rather than
  returning stale data from an earlier run.

**Fix:** Send `Authorization: Bearer <token>`, use a Streamable HTTP MCP client that
posts JSON-RPC requests, and select a reporter with `METRICS_REPORTER`. Then resolve
any Kafka or filtering problem reported in the logs. See [MCP Endpoint](/ai/mcp/).

## Prometheus does not discover the ServiceMonitor

**Likely cause:** `serviceMonitor.enabled` is false, the reporter is not Prometheus, or
the Prometheus Operator's ServiceMonitor selector does not match the resource labels.
The chart rejects `serviceMonitor.enabled=true` with a non-Prometheus reporter.

**Fix:** Install or upgrade with:

```bash
helm upgrade --install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="prometheus" \
  --set serviceMonitor.enabled=true
```

If your Prometheus resource selects a label such as `release: monitoring`, add
`--set serviceMonitor.labels.release=monitoring`. Confirm that the Prometheus
namespace and object selectors permit the ServiceMonitor and that its endpoint selects
the Klag Service. See [Kubernetes deployment](/deployment/kubernetes/#prometheus-servicemonitor).
