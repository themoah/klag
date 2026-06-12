# Klag Helm Chart

A Helm chart for deploying [Klag](https://github.com/themoah/klag) - Kafka Lag Exporter built with Vert.x.

## Prerequisites

- Kubernetes 1.19+
- Helm 3.0+
- Kafka cluster accessible from the Kubernetes cluster

## Installation

### Basic Installation

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

### With Kafka SASL Authentication

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.saslJaasConfig="org.apache.kafka.common.security.plain.PlainLoginModule required username='user' password='pass';"
```

### Using Existing Secrets (Recommended for Production)

```bash
# Create Kafka credentials secret
kubectl create secret generic kafka-creds \
  --from-literal=jaas-config="org.apache.kafka.common.security.plain.PlainLoginModule required username='user' password='pass';"

# Install with existing secret reference
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.existingSecret="kafka-creds"
```

### With SASL_SSL and Private Truststore

When the brokers present certs signed by an internal CA, mount the truststore via
`extraVolumes` / `extraVolumeMounts` and point `kafka.sslTruststoreLocation` at it.

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9093" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.existingSecret="kafka-creds" \
  --set kafka.sslTruststoreLocation="/etc/shared-certificates/kafka/client.truststore.jks" \
  --set-json 'extraVolumes=[{"name":"shared-certificates","persistentVolumeClaim":{"claimName":"shared-certificates-volume-claim"}}]' \
  --set-json 'extraVolumeMounts=[{"name":"shared-certificates","mountPath":"/etc/shared-certificates","readOnly":true}]'
```

If the truststore is password-protected, set `kafka.sslTruststorePassword`. The chart
stores it in the chart-managed Kafka Secret and injects it as `KAFKA_SSL_TRUSTSTORE_PASSWORD`
(mapped by klag to `ssl.truststore.password`). With `kafka.existingSecret`, put the password
under the `truststore-password` key (configurable via `kafka.secretKeys.truststorePassword`).

### With OTLP Metrics (Grafana Cloud)

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="otlp" \
  --set metrics.otlp.endpoint="https://otlp-gateway-prod-us-east-0.grafana.net/otlp" \
  --set metrics.otlp.headers="Authorization=Basic <base64-credentials>" \
  --set metrics.otlp.serviceName="klag-production"
```

### With Datadog Metrics

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="datadog" \
  --set metrics.datadog.apiKey="<your-api-key>" \
  --set metrics.datadog.appKey="<your-app-key>"
```

### With Prometheus ServiceMonitor

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="prometheus" \
  --set serviceMonitor.enabled=true
```

## Configuration

### General Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas. Klag has no leader election: each replica reports the same metrics, so >1 double-reports with push reporters and duplicates series with Prometheus | `1` |
| `image.repository` | Image repository | `themoah/klag` |
| `image.tag` | Image tag (defaults to chart appVersion) | `""` |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |
| `nameOverride` | Override the chart name | `""` |
| `fullnameOverride` | Override the full release name | `""` |

### Kafka Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `kafka.bootstrapServers` | Kafka bootstrap servers (comma-separated) | `localhost:9092` |
| `kafka.requestTimeoutMs` | Admin client request timeout | `30000` |
| `kafka.securityProtocol` | Security protocol (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL) | `""` |
| `kafka.saslMechanism` | SASL mechanism (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512) | `""` |
| `kafka.saslJaasConfig` | JAAS configuration string | `""` |
| `kafka.sslTruststoreLocation` | Path inside the container to the SSL truststore (emits `KAFKA_SSL_TRUSTSTORE_LOCATION`) | `""` |
| `kafka.sslTruststorePassword` | Truststore password, stored in the Kafka Secret and injected as `KAFKA_SSL_TRUSTSTORE_PASSWORD` | `""` |
| `kafka.existingSecret` | Name of existing secret for Kafka credentials | `""` |
| `kafka.secretKeys.jaasConfig` | Key in secret for JAAS config | `jaas-config` |
| `kafka.secretKeys.truststorePassword` | Key in secret for the truststore password | `truststore-password` |

Any other `KAFKA_*` env var set on the pod is automatically picked up by klag and mapped to the equivalent Kafka client property (e.g. `KAFKA_SSL_TRUSTSTORE_PASSWORD` → `ssl.truststore.password`).

Set `KLAG_CONFIG_FILE` to the path of an external `application.properties` (typically mounted from a ConfigMap or Secret via `extraVolumes` / `extraVolumeMounts`) to layer file-based config between the classpath defaults and `KAFKA_*` env vars. Precedence: classpath `application.properties` < `KLAG_CONFIG_FILE` < `KAFKA_*` env vars.

### Metrics Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `metrics.reporter` | Metrics backend (none, prometheus, datadog, otlp) | `prometheus` |
| `metrics.intervalMs` | Metrics collection interval (ms) | `60000` |
| `metrics.groupFilter` | Comma-separated glob patterns for consumer groups to include | `*` |
| `metrics.groupExclude` | Comma-separated glob patterns to exclude (evaluated after `groupFilter`) | `""` |
| `metrics.jvmEnabled` | Enable JVM metrics | `false` |

### OTLP Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `metrics.otlp.endpoint` | OTLP endpoint URL | `""` |
| `metrics.otlp.headers` | Authentication headers (key1=value1,key2=value2) | `""` |
| `metrics.otlp.serviceName` | Service name for OTEL_SERVICE_NAME | `klag` |
| `metrics.otlp.resourceAttributes` | Additional resource attributes | `""` |
| `metrics.otlp.existingSecret` | Existing secret for OTLP headers | `""` |
| `metrics.otlp.secretKeys.headers` | Key in secret for headers | `otlp-headers` |

### Datadog Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `metrics.datadog.apiKey` | Datadog API key | `""` |
| `metrics.datadog.appKey` | Datadog application key | `""` |
| `metrics.datadog.site` | Datadog site | `datadoghq.com` |
| `metrics.datadog.existingSecret` | Existing secret for Datadog credentials | `""` |
| `metrics.datadog.secretKeys.apiKey` | Key in secret for API key | `api-key` |
| `metrics.datadog.secretKeys.appKey` | Key in secret for app key | `app-key` |

### Application Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `app.healthCheckIntervalMs` | Kafka health check interval (ms) | `30000` |
| `app.useVirtualThreads` | Enable Java 21 virtual threads | `false` |

### MCP Configuration (AI agent endpoint)

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.enabled` | Expose the read-only `/mcp` endpoint (sets `MCP_ENABLED`) | `false` |
| `mcp.path` | HTTP path of the MCP endpoint | `/mcp` |
| `mcp.authToken` | Bearer token, stored in a chart-managed Secret and injected as `MCP_AUTH_TOKEN`. Empty = unauthenticated (klag logs a warning) | `""` |
| `mcp.existingSecret` | Existing secret holding the MCP token | `""` |
| `mcp.secretKeys.authToken` | Key in secret for the token | `mcp-auth-token` |

### Logging Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `logging.level` | Root logger level | `INFO` |
| `logging.levelKlag` | Klag module logger level | `""` |
| `logging.levelKafka` | Kafka client logger level | `""` |
| `logging.levelHealth` | Health check logger level | `""` |
| `logging.levelMetrics` | Metrics collector logger level | `""` |

### Service Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `service.type` | Service type | `ClusterIP` |
| `service.port` | Service port | `8888` |

### ServiceMonitor Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `serviceMonitor.enabled` | Enable ServiceMonitor for Prometheus Operator | `false` |
| `serviceMonitor.namespace` | Namespace for ServiceMonitor | `""` |
| `serviceMonitor.interval` | Scrape interval | `30s` |
| `serviceMonitor.scrapeTimeout` | Scrape timeout | `10s` |
| `serviceMonitor.labels` | Additional labels | `{}` |

### Pod Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `resources.limits.cpu` | CPU limit | `500m` |
| `resources.limits.memory` | Memory limit | `512Mi` |
| `resources.requests.cpu` | CPU request | `100m` |
| `resources.requests.memory` | Memory request | `256Mi` |
| `nodeSelector` | Node selector | `{}` |
| `tolerations` | Tolerations | `[]` |
| `affinity` | Affinity rules | `{}` |
| `podAnnotations` | Pod annotations | `{}` |
| `podSecurityContext` | Pod security context | non-root (UID 65532) + `RuntimeDefault` seccomp |
| `securityContext` | Container security context | no privilege escalation, read-only root FS, all capabilities dropped |
| `extraVolumes` | Additional pod-level volumes (e.g., truststore PVC, certs Secret) | `[]` |
| `extraVolumeMounts` | Additional container volume mounts; pair with `extraVolumes` | `[]` |
| `extraEnv` | Additional environment variables (verbatim `EnvVar` entries) for settings without first-class values (`HOT_PARTITION_*`, `TIME_LAG_*`, `KAFKA_CHUNK_*`, ...) | `[]` |
| `extraEnvFrom` | Additional `envFrom` sources (ConfigMap/Secret refs) | `[]` |

The pod always mounts an `emptyDir` at `/tmp` so the JVM and Netty work with the
default `readOnlyRootFilesystem: true`. Chart-managed Secrets are checksummed into a pod
annotation, so credential changes roll the Deployment automatically.

### NetworkPolicy Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `networkPolicy.enabled` | Create a NetworkPolicy restricting ingress to the http port | `false` |
| `networkPolicy.ingressFrom` | Allowed peers (`NetworkPolicyPeer` entries); empty = any peer, http port only | `[]` |

### ServiceAccount Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `serviceAccount.create` | Create ServiceAccount | `true` |
| `serviceAccount.automount` | Mount the Kubernetes API token (klag never calls the API) | `false` |
| `serviceAccount.name` | ServiceAccount name | `""` |
| `serviceAccount.annotations` | ServiceAccount annotations | `{}` |

## Health Endpoints

| Endpoint | Description |
|----------|-------------|
| `/healthz` | Liveness probe - always returns 200 if running |
| `/readyz` | Readiness probe - returns 200 if Kafka connected, 503 otherwise |
| `/metrics` | Prometheus metrics endpoint (when `metrics.reporter=prometheus`) |

## Metrics Exposed

- `klag.consumer.lag[.sum/.max/.min]` - Consumer lag per partition and aggregated
- `klag.consumer.lag.velocity` - Lag velocity (rate of change)
- `klag.consumer.group.state` - Consumer group state changes
- `klag.consumer.committed_offset` - Committed offset per partition
- `klag.topic.partitions` - Partition count per topic
- `klag.partition.log_end_offset` - Latest offset per partition
- `klag.partition.log_start_offset` - Earliest offset per partition

All metrics include tags: `consumer_group`, `topic`, `partition` (when applicable).

## Uninstalling

```bash
helm uninstall klag
```
