# CLAUDE.md

## Project Overview

Klag is a Kafka Lag Exporter built with Vert.x 4.5.22. Monitors consumer lag and group states with Prometheus/Datadog/OTLP metrics.

## Build Commands

```bash
# Requires Java 21 - use SDKMAN if needed: sdk use java 21.0.9-tem
# Local: use ./gradlew | CI: uses gradle directly (no wrapper JAR committed)

./gradlew compileJava          # Compile
./gradlew test                 # Run tests
./gradlew assemble             # Package (creates fat JAR)
./gradlew run                  # Run with hot-reload
```

## Architecture

Vert.x reactive framework with `Future<T>`-based async API.

```
src/main/java/io/github/themoah/klag/
├── MainVerticle.java          # Entry point, HTTP router, lifecycle
├── config/AppConfig.java      # HTTP_PORT, KAFKA_HEALTH_CHECK_INTERVAL_MS
├── health/                    # KafkaHealthMonitor, HealthCheckHandler, HealthStatus
├── kafka/                     # KafkaClientService[Impl], KafkaClientConfig
├── metrics/                   # MetricsCollector, MicrometerReporter, PrometheusHandler
│   └── velocity/              # LagVelocityTracker, TopicLagHistory
└── model/                     # Records: ConsumerGroupLag, ConsumerGroupState, PartitionOffsets, LagVelocity, etc.
```

## HTTP Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/healthz` | Liveness probe (always 200) |
| `/readyz` | Readiness probe (200 if Kafka UP, 503 if DOWN) |
| `/metrics` | Prometheus scrape endpoint (if enabled) |

## Environment Variables

**App:** `HTTP_PORT` (8888), `KAFKA_HEALTH_CHECK_INTERVAL_MS` (30000), `VERTX_USE_VIRTUAL_THREADS` (false)

**Kafka:** `KAFKA_BOOTSTRAP_SERVERS` (localhost:9092), `KAFKA_REQUEST_TIMEOUT_MS` (30000)

**Metrics:** `METRICS_REPORTER` (none/prometheus/datadog/otlp), `METRICS_INTERVAL_MS` (60000), `METRICS_GROUP_FILTER` (glob pattern), `METRICS_JVM_ENABLED` (false)

**Logging:** `LOG_LEVEL`, `LOG_LEVEL_KLAG`, `LOG_LEVEL_KAFKA`, `LOG_LEVEL_HEALTH`, `LOG_LEVEL_METRICS`

**OTLP Configuration (when METRICS_REPORTER=otlp):**

*Standard OpenTelemetry Variables:*
- `OTEL_EXPORTER_OTLP_ENDPOINT` - Base endpoint (e.g., http://localhost:4318)
- `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` - Metrics-specific endpoint (overrides base)
- `OTEL_EXPORTER_OTLP_HEADERS` - Authentication headers (format: key1=value1,key2=value2)
- `OTEL_EXPORTER_OTLP_METRICS_HEADERS` - Metrics-specific headers (overrides general)
- `OTEL_METRIC_EXPORT_INTERVAL` - Export interval in milliseconds (default: 60000)
- `OTEL_SERVICE_NAME` - Service name for resource attributes (default: klag)
- `OTEL_RESOURCE_ATTRIBUTES` - Additional resource attributes (format: key1=value1,key2=value2)

*Custom Variables (override OTEL_* vars):*
- `OTLP_ENDPOINT` - Direct endpoint URL (default: http://localhost:4318/v1/metrics)
- `OTLP_STEP_MS` - Export interval in milliseconds (default: 60000)
- `OTLP_HEADERS` - Authentication headers (format: key1=value1,key2=value2)
- `OTLP_RESOURCE_ATTRIBUTES` - Resource attributes (format: key1=value1,key2=value2)

*Note:* Protocol is HTTP only (port 4318). Aggregation temporality is cumulative.

*Example for Grafana Cloud:*
```bash
METRICS_REPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-prod-us-east-0.grafana.net/otlp
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64-encoded-credentials>
OTEL_SERVICE_NAME=klag-production
```

*Example for Local OpenTelemetry Collector:*
```bash
METRICS_REPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_SERVICE_NAME=klag-dev
OTEL_RESOURCE_ATTRIBUTES=environment=development,cluster=local
```

## Metrics Exposed

- `klag.consumer.lag[.sum/.max/.min]` - Consumer lag (per partition and aggregated)
- `klag.partition.log_end_offset`, `klag.partition.log_start_offset`
- `klag.consumer.committed_offset`, `klag.consumer.group.state`
- `klag.topic.partitions` - Partition count per topic

Tags: `consumer_group`, `topic`, `partition`

## Grafana Dashboard

A pre-built comprehensive Grafana dashboard is available in `dashboard/demo-dashboard.json`.

**Dashboard Features:**
- **Consumer Lag Overview** - Real-time lag monitoring by consumer group with color-coded thresholds
- **Lag Velocity Tracking** - Identifies if lag is growing or shrinking over time
- **Consumer Group Health** - State monitoring with visual alerts for unhealthy states
- **Partition & Offset Details** - Topic throughput and per-partition lag visualization
- **Template Variables** - Filter by consumer group and topic dynamically
- **Auto-refresh** - Updates every 1 minute by default

**Panels Included:**
- Current Lag by Consumer Group (time series)
- Max Lag stat with thresholds
- Active consumer groups count
- Lag velocity trends
- Consumer group state table
- Lag distribution bar gauge
- Partition count by topic
- Topic throughput (log end offset rate)
- Top 10 partition offset gaps
- JVM Memory Usage (heap/non-heap)
- JVM GC Pause Time
- JVM Thread States (stacked)
- Process CPU Usage
- JVM Memory Allocation Rate
- JVM Loaded Classes

**Import to Grafana Cloud:**
1. Navigate to Grafana → Dashboards → Import
2. Upload `dashboard/demo-dashboard.json`
3. Select your OTLP/Prometheus-compatible data source
4. Customize refresh interval and time range as needed

**Requirements:**
- Klag running with `METRICS_REPORTER=otlp` (or `prometheus`)
- Metrics flowing to Grafana Cloud or Prometheus-compatible backend
- Data source configured in Grafana with PromQL support

## Code Style

- Async ops return `Future<T>`, Java 21 records for DTOs, SLF4J+Logback logging
- Config priority: classpath → external file → env vars
- Bump up the version in @build.gradle.kts after each minor or major change.
