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
| `replicaCount` | Number of replicas | `1` |
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
| `kafka.existingSecret` | Name of existing secret for Kafka credentials | `""` |
| `kafka.secretKeys.jaasConfig` | Key in secret for JAAS config | `jaas-config` |

### Metrics Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `metrics.reporter` | Metrics backend (none, prometheus, datadog, otlp) | `prometheus` |
| `metrics.intervalMs` | Metrics collection interval (ms) | `60000` |
| `metrics.groupFilter` | Glob pattern for consumer groups | `*` |
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
| `podSecurityContext` | Pod security context | `{}` |
| `securityContext` | Container security context | `{}` |

### ServiceAccount Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `serviceAccount.create` | Create ServiceAccount | `true` |
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
