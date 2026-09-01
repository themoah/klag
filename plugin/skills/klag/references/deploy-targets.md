# Deployment targets

Pick by what the user already has. Detection commands are read-only — run them without asking.

| Signal | Target |
|---|---|
| no Kafka at all, wants to see it work | Demo compose stack |
| `docker info` works, has own Kafka | Single container |
| `kubectl` + `helm` present | Helm release |
| ArgoCD or Flux CRDs present | GitOps manifest (write file, do not apply) |
| no docker, has Java 21 | Fat jar |

Detection:

```bash
docker info >/dev/null 2>&1 && echo docker-ok
kubectl config current-context 2>/dev/null
helm version --short 2>/dev/null
kubectl get crd applications.argoproj.io -o name 2>/dev/null                       # ArgoCD
kubectl get crd helmreleases.helm.toolkit.fluxcd.io -o name 2>/dev/null            # Flux
kubectl get crd kafkas.kafka.strimzi.io -o name 2>/dev/null                        # Strimzi
kubectl get crd servicemonitors.monitoring.coreos.com -o name 2>/dev/null          # Prometheus Operator
java -version 2>&1 | head -1
```

## 1. Demo compose stack (zero-Kafka POC)

The klag repo's `docker-compose.yaml` brings up KRaft Kafka, klag, a producer and three
deliberately misbehaving consumers (`slow-consumer`, `no-commit-consumer`,
`delayed-commit-consumer`), so lag appears within a minute. Requires a checkout of
`https://github.com/themoah/klag`; it builds the klag image locally.

```bash
docker compose up -d
curl -s localhost:8888/readyz
curl -s localhost:8888/metrics | grep -c '^klag_consumer_lag'
```

Tear down with `docker compose down -v`.

## 2. Single container against the user's Kafka

```bash
docker run -d --name klag -p 8888:8888 \
  -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \
  -e METRICS_REPORTER=prometheus \
  themoah/klag:latest
```

`themoah/klag:native` is the GraalVM build (~70-100 ms start, ~44 MB RSS). Same tags on
`ghcr.io/themoah/klag`. `latest` and `native` are mutable — fine for a POC, but for anything
longer-lived pin the release tag (`themoah/klag:0.2.14`, chart `image.tag`) so a later pull
cannot change the running code. Add `-e MCP_ENABLED=true -e MCP_AUTH_TOKEN=...` to expose `/mcp`.

If Kafka runs in another compose network, join it (`--network <net>`) and use the internal
listener address, not `localhost`.

## 3. Helm

```bash
helm repo add klag https://themoah.github.io/klag
helm repo update
helm upgrade --install klag klag/klag -n kafka-monitoring --create-namespace \
  -f klag-values.yaml
```

Minimum `klag-values.yaml`:

```yaml
kafka:
  bootstrapServers: "my-cluster-kafka-bootstrap.kafka:9092"
metrics:
  reporter: prometheus
```

Values worth setting on a real install:

| Value | Why |
|---|---|
| `metrics.groupFilter` / `metrics.groupExclude` | glob include/exclude; a group is monitored iff it matches an include AND no exclude |
| `serviceMonitor.enabled: true` | only if the Prometheus Operator CRD exists |
| `mcp.enabled: true` + `mcp.existingSecret` | expose `/mcp` for agents |
| `kafka.existingSecret` | JAAS config + truststore password, never inline |
| `resources` | defaults: 500m/512Mi limits, 100m/256Mi requests |
| `kafka.clusters` | several Kafka clusters in one process (`KAFKA_CLUSTERS`); unique `name` + `bootstrapServers` per entry. Shared creds via `kafka.existingSecret`; do not put secrets in `clusters[].properties`. The chart omits `KAFKA_CLUSTER_NAME` even if `kafka.clusterName` is also set. |

`replicaCount` stays at 1 — there is no leader election, so >1 double-reports on push sinks
(Datadog/OTLP). Full values reference: `https://klag.dev/deployment/kubernetes/`.

Verify:

```bash
kubectl -n kafka-monitoring rollout status deploy/klag
kubectl -n kafka-monitoring port-forward svc/klag 18888:8888 &
curl -s localhost:18888/readyz
curl -s localhost:18888/metrics | grep '^klag_consumer_lag{' | head
```

## 4. GitOps (ArgoCD / Flux)

Write the manifest into the user's config repo and stop. Do not apply it.

ArgoCD:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: klag
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://themoah.github.io/klag
    chart: klag
    # Resolve the current chart version first: `helm search repo klag/klag --versions | head -3`.
    # Pin an exact version, or a range like `0.3.*` if the user wants patch updates picked up.
    targetRevision: <chart-version>
    helm:
      valuesObject:
        kafka:
          bootstrapServers: "my-cluster-kafka-bootstrap.kafka:9092"
        metrics:
          reporter: prometheus
  destination:
    server: https://kubernetes.default.svc
    namespace: kafka-monitoring
  syncPolicy:
    automated: { prune: true, selfHeal: true }
    syncOptions: [CreateNamespace=true]
```

Flux: a `HelmRepository` pointing at `https://themoah.github.io/klag` plus a `HelmRelease`
with `chart.spec.chart: klag`. Secrets stay out of the committed values — reference an
existing Secret.

## 5. Fat jar / native binary

Fat jar from the latest GitHub release (`klag-<version>-fat.jar`), Java 21 required:

```bash
KAFKA_BOOTSTRAP_SERVERS=broker:9092 METRICS_REPORTER=prometheus java -jar klag-*-fat.jar
```

`-D` works too, including on the native binary: `-Dkafka.bootstrap.servers=broker:9092`.
No standalone native binary is published — the native build ships as the `:native` image
tag, or build it from source (`gradle nativeCompile`, GraalVM JDK 21).
