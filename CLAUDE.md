# CLAUDE.md

## Project Overview

Klag is a Kafka Lag Exporter built with Vert.x 4.5.22. Monitors consumer lag and group states with Prometheus/Datadog metrics.

## Build Commands

```bash
# Requires Java 17 - use SDKMAN if needed: sdk use java 17.0.15-tem
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

**App:** `HTTP_PORT` (8888), `KAFKA_HEALTH_CHECK_INTERVAL_MS` (30000)

**Kafka:** `KAFKA_BOOTSTRAP_SERVERS` (localhost:9092), `KAFKA_REQUEST_TIMEOUT_MS` (30000)

**Metrics:** `METRICS_REPORTER` (none/prometheus/datadog), `METRICS_INTERVAL_MS` (60000), `METRICS_GROUP_FILTER` (glob pattern), `METRICS_JVM_ENABLED` (false)

**Logging:** `LOG_LEVEL`, `LOG_LEVEL_KLAG`, `LOG_LEVEL_KAFKA`, `LOG_LEVEL_HEALTH`, `LOG_LEVEL_METRICS`

## Metrics Exposed

- `klag.consumer.lag[.sum/.max/.min]` - Consumer lag (per partition and aggregated)
- `klag.partition.log_end_offset`, `klag.partition.log_start_offset`
- `klag.consumer.committed_offset`, `klag.consumer.group.state`
- `klag.topic.partitions` - Partition count per topic

Tags: `consumer_group`, `topic`, `partition`

## Code Style

- Async ops return `Future<T>`, Java 17 records for DTOs, SLF4J+Logback logging
- Config priority: classpath → external file → env vars
