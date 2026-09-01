---
title: Kubernetes (Helm)
description: Deploy Klag on Kubernetes with the official Helm chart, including SASL authentication and ServiceMonitor support.
---

The official Helm chart is the recommended way to run Klag on Kubernetes. It is
published to a Helm repository served from GitHub Pages and indexed on
[Artifact Hub](https://artifacthub.io/packages/helm/klag/klag).

## Install

```bash
helm repo add klag https://themoah.github.io/klag
helm repo update
helm search repo klag   # find the latest version

helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

For repeatable deployments, choose a release from
`helm search repo klag --versions` and pass `--version <chart-version>`.

## With SASL authentication

```bash
helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.saslJaasConfig="org.apache.kafka.common.security.plain.PlainLoginModule required username='user' password='pass';"
```

## From a local checkout (development)

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

## Configuration

The chart exposes Kafka connection, metrics reporter, resource limits, and (optionally) a
`ServiceMonitor` for the Prometheus Operator. See the
[chart README](https://github.com/themoah/klag/blob/main/charts/klag/README.md) for the
full list of values, and the [Configuration Reference](/configuration/reference/) for the
underlying environment variables.

The chart defaults `metrics.reporter` to `prometheus`. The bare application defaults
`METRICS_REPORTER` to `none`, so non-Helm deployments must select a reporter.

## Production guidance

### Keep one replica unless you deduplicate

Klag has no leader election. Each replica polls the same Kafka data and reports the same
metrics. Keep `replicaCount: 1` unless your backend deduplicates them. Multiple
Prometheus replicas create pod-distinguished duplicate series; Datadog and OTLP replicas
double-report the values and may increase ingestion cost.

### Prometheus ServiceMonitor

Set the reporter and ServiceMonitor together:

```bash
helm upgrade --install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="prometheus" \
  --set serviceMonitor.enabled=true
```

The chart rejects a ServiceMonitor with a non-Prometheus reporter because `/metrics`
would return `404`. Add labels under `serviceMonitor.labels` when your Prometheus
Operator uses a label selector. Use `serviceMonitor.metricRelabelings` (post-scrape)
and `serviceMonitor.relabelings` (pre-scrape) to drop high-cardinality labels or mint
extra labels without a second ServiceMonitor. Dropping `member_host`, `consumer_id`,
and `client_id` with `labeldrop` overlaps
`CONSUMER_MEMBER_LABELS_ENABLED=false`, which is cheaper — Klag never builds those
series. See
[Troubleshooting](/guides/troubleshooting/#prometheus-does-not-discover-the-servicemonitor)
for namespace and selector checks.

### Credentials and truststores

Use `kafka.existingSecret` for SASL credentials instead of putting JAAS text in release
values. Mount private truststores or CA material with `extraVolumes` and
`extraVolumeMounts`, then set `kafka.sslTruststoreLocation` to the mounted path. Inject
passwords and settings without first-class chart values through `extraEnv` secret
references or `extraEnvFrom`. The
[chart README](https://github.com/themoah/klag/blob/main/charts/klag/README.md)
documents the expected Secret keys and a truststore mount example.

### Probes and resources

The chart sends liveness checks to `/healthz` and readiness checks to `/readyz`.
`/healthz` only confirms that the process runs. `/readyz` uses the Kafka health
monitors' cached state: it is ready when **any** configured cluster is up, so one
unreachable cluster does not take the Prometheus scrape target down. The JSON body
keeps a top-level `kafka` field (`connected` when any cluster is up) and a
`clusters` array with per-cluster `kafka` status and `name` when the cluster is
named. Increasing the probe `timeoutSeconds` does not give an in-flight Kafka check
more time. Keep the probe roles separate. For slow startup, increase
`readinessProbe.initialDelaySeconds` or `readinessProbe.failureThreshold`.
Tune `kafka.requestTimeoutMs` and `app.healthCheckIntervalMs` when Kafka requests or
health-state refreshes need different timing.

The defaults request `100m` CPU and `256Mi` memory and limit the container to `500m` CPU
and `512Mi` memory. Treat them as a starting point. Measure collection duration and
memory for your group and partition count, then raise requests or limits before reducing
`metrics.intervalMs`; overlapping demand can otherwise cause skipped collection ticks.

### Multiple Kafka clusters

One process can scrape several Kafka clusters. Set `kafka.clusters` with a unique
`name` and `bootstrapServers` per entry. Shared SASL/SSL still come from
`kafka.existingSecret` (or `kafka.saslJaasConfig`) and apply to every cluster.
Distinct per-cluster credentials are not supported. Do not put JAAS, SSL passwords,
or other secrets in `clusters[].properties`: Helm renders `kafka.clusters` as the
plaintext `KAFKA_CLUSTERS` env value.

```yaml
kafka:
  clusters:
    - name: msk-a
      bootstrapServers: "b-1.a.example:9092"
    - name: msk-b
      bootstrapServers: "b-1.b.example:9092"
```

Each series is tagged with `cluster_name`. Prometheus Operator target `relabelings`
(for example a scrape-level `cluster` label) apply to the **whole** pod scrape and
cannot split those series; use `cluster_name` in PromQL (the bundled Grafana
dashboard has a Cluster variable). A single cluster can set `kafka.clusterName`
instead of `kafka.clusters`. Without a name, Kafka series omit `cluster_name` and
that dashboard filter matches nothing — set `kafka.clusterName` if you use it.

## Permissions

Klag needs only read-only Kafka access. See [ACL Permissions](/kafka/acl-permissions/).
On Kubernetes-managed Kafka, [Strimzi](/deployment/strimzi/) is fully supported.
