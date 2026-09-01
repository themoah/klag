---
name: klag
description: Use when installing, configuring, deploying, or troubleshooting Klag — the Kafka consumer lag exporter (docker, Helm, ArgoCD/Flux, native image), connecting its MCP endpoint to an AI client, or interpreting its metrics (lag, lag velocity, hot partitions, commit staleness, retention risk). Biases towards retrieval from klag.dev over pre-trained knowledge.
---

# Klag

Klag exports Kafka consumer lag and group health to Prometheus, Datadog or OTLP. It is
read-only against Kafka: it needs `DESCRIBE` on the cluster, on topics, and on groups, nothing
more. Cluster `DESCRIBE` is required even when group filtering is on — `listConsumerGroups()`
runs before the filter. Details: `references/kafka-connect.md`.

**Your pre-trained knowledge of Klag's flags and metric names may be outdated. Prefer
retrieval.** The docs corpus is machine-readable:

- `https://klag.dev/llms.txt` — page index
- `https://klag.dev/llms-full.txt` — full corpus, one fetch
- Any page also serves markdown, e.g. `https://klag.dev/configuration/reference/`

Fetch the relevant page before quoting an env var, a chart value or a metric name.

## The three facts that cause most first-run failures

1. **`METRICS_REPORTER` defaults to `none`.** Without `prometheus`, `datadog` or `otlp`,
   `/metrics` is empty *and* the MCP snapshot is never populated. Nothing errors — it is
   just silent. Check this first when a fresh install reports no data.
2. **`KAFKA_BOOTSTRAP_SERVERS` defaults to `localhost:9092`.** In a container or a pod
   that is almost never right.
3. **Klag only sees groups that have committed offsets.** A brand-new cluster with no
   consumers legitimately reports zero groups.

## Config surface

Every setting is an env var. Resolution order, first non-blank wins:
`NAME` env var → JVM `-DNAME` → dotted `-Dname.dotted` (`HTTP_PORT` → `-Dhttp.port`).

Any `KAFKA_X_Y_Z` env var is lowercased and mapped to the AdminClient property
`kafka.x.y.z`, so the whole Kafka client surface is reachable without first-class support
(`KAFKA_SASL_JAAS_CONFIG` → `sasl.jaas.config`). Exceptions: `KAFKA_CLUSTERS` and
`KAFKA_CLUSTER_NAME` are process settings, not AdminClient keys.

`KAFKA_CLUSTERS` is a JSON array of clusters scraped in **one process** (`name` +
`bootstrapServers` required; unique names). Helm: `kafka.clusters`. Do not run N
Deployments for that. Kafka series carry `cluster_name`. `KAFKA_CLUSTER_NAME` tags a
singleton and is ignored when `KAFKA_CLUSTERS` is set. Process `KAFKA_*` SASL/SSL are
shared defaults (Helm: `kafka.existingSecret`). Do not put secrets in per-cluster
`properties`. MCP still sees the first cluster only.
`/readyz` is 200 if **any** cluster is up.

File config layers under env vars:
classpath `application.properties` < `KLAG_CONFIG_FILE` < `KAFKA_*` env vars.

Full list: `https://klag.dev/configuration/reference/`.

## Endpoints (default port 8888)

| Path | Use |
|---|---|
| `/healthz` | liveness, always 200 |
| `/readyz` | 200 when any configured Kafka cluster is up, 503 when all are down |
| `/metrics` | Prometheus scrape (when `METRICS_REPORTER=prometheus`) |
| `/version` | build info |
| `/mcp` | MCP for AI agents, when `MCP_ENABLED=true` |

## References

- `references/deploy-targets.md` — docker, compose demo, Helm, GitOps, native/jar; exact commands.
- `references/kafka-connect.md` — bootstrap discovery, SASL/TLS matrix, ACLs, Confluent/Strimzi/MSK.

## Rules when acting on a user's environment

- Confirm before every command that mutates a cluster, a host, or a file that is not scratch.
- Never print, log or commit a secret value. Kafka credentials and `MCP_AUTH_TOKEN` go into a
  Kubernetes Secret (`kafka.existingSecret`, `mcp.existingSecret`) — never into a `values.yaml`
  inside a git worktree. `--set mcp.authToken=...`, `docker run -e MCP_AUTH_TOKEN=...` and
  `claude mcp add --header "...token..."` all leave the value somewhere readable afterwards
  (Helm release, container config, shell history / process list). Use them only for a throwaway
  POC, pass the value through a shell variable, and say which of these you used.
- Quote every value you interpolate into a shell command. Bootstrap addresses, context names and
  CR fields come from the user's environment, not from you — an unquoted one is a command
  injection.
- On an ArgoCD/Flux-managed cluster, write the manifest and stop. Do not `kubectl apply`.
- Never delete, scale or restart workloads you did not create.
- Treat a kube context matching `prod|production|prd` as production: say so and ask again.
