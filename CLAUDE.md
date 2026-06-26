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

./scripts/test-helm-chart.sh   # Helm chart template tests (offline)
./scripts/e2e-test.sh          # Full e2e: k3d + real Kafka + chart + lag asserts
./scripts/e2e-strimzi-test.sh  # e2e against Strimzi-operator-managed Kafka (KRaft)
./scripts/e2e-strimzi-matrix.sh # Strimzi e2e across supported Kafka versions
```

### GraalVM Native Image (startup/memory optimized)

Requires a GraalVM JDK 21 (LTS) with `native-image` (e.g. `sdk install java 21.0.2-graalce`).
Run Gradle with that JDK as `JAVA_HOME`/`GRAALVM_HOME`.

```bash
gradle nativeCompile           # -> build/native/nativeCompile/klag (standalone binary)
docker build -f Dockerfile.native -t klag:native .   # distroless runtime image
scripts/benchmark-startup.sh native - build/native/nativeCompile/klag  # startup/RSS bench
```

Native config lives in `build.gradle.kts` (`graalvmNative` block) plus reachability
hints in `src/main/resources/META-INF/native-image/`. Reflection metadata for Netty,
kafka-clients, logback and micrometer comes from the GraalVM Reachability Metadata
Repository (auto-enabled). Entry point is `KlagLauncher` (direct `new MainVerticle()`,
no reflective Vert.x launcher).

**Measured (macOS arm64, prometheus reporter, Kafka up):** native ≈ 70-100 ms startup /
44 MB RSS vs JVM 21 ≈ 470-520 ms / 119 MB. JVM 25 (LTS) showed no startup/memory gain
over 21 for this workload (slightly higher RSS), so the runtime stays on JDK 21.

## Architecture

Vert.x reactive framework with `Future<T>`-based async API.

```
src/main/java/io/github/themoah/klag/
├── MainVerticle.java          # Entry point, HTTP router, lifecycle
├── config/AppConfig.java      # HTTP_PORT, KAFKA_HEALTH_CHECK_INTERVAL_MS
├── health/                    # KafkaHealthMonitor, HealthCheckHandler, HealthStatus, VersionHandler
├── kafka/                     # KafkaClientService[Impl], KafkaClientConfig
├── metrics/                   # MetricsCollector, MicrometerReporter, PrometheusHandler
│   ├── velocity/              # LagVelocityTracker, TopicLagHistory
│   ├── hotpartition/          # HotPartitionDetector, HotPartitionConfig, StatisticalUtils
│   └── timelag/               # TimeLagEstimator, TimeLagConfig, OffsetTimestampTracker, PartitionOffsetHistory
└── model/                     # Records: ConsumerGroupLag, ConsumerGroupState, PartitionOffsets, LagVelocity, etc.
```

## HTTP Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/healthz` | Liveness probe (always 200) |
| `/readyz` | Readiness probe (200 if Kafka UP, 503 if DOWN) |
| `/metrics` | Prometheus scrape endpoint (if enabled) |
| `/version` | Build information |
| `/mcp` | MCP endpoint for AI agents (JSON-RPC over POST; if `MCP_ENABLED=true`) |

## Environment Variables

**App:** `HTTP_PORT` (8888), `KAFKA_HEALTH_CHECK_INTERVAL_MS` (30000), `VERTX_USE_VIRTUAL_THREADS` (false), `KLAG_CONFIG_FILE` (path to external `application.properties`, optional)

Any `Env`-backed variable resolves in order (first non-blank wins): env var `NAME` → JVM property `-DNAME` → dotted `-Dname.dotted` (e.g. `HTTP_PORT` → `-Dhttp.port`). This lets jar/native users configure via `-D`; env keeps precedence. See `config/Env.java#resolve`.

**Kafka:** `KAFKA_BOOTSTRAP_SERVERS` (localhost:9092), `KAFKA_REQUEST_TIMEOUT_MS` (30000), `KAFKA_CHUNK_COUNT` (1), `KAFKA_CHUNK_DELAY_MS` (0). Any other `KAFKA_X_Y_Z` env var maps to `kafka.x.y.z` and is forwarded to the AdminClient. Config precedence: classpath `application.properties` < external file at `KLAG_CONFIG_FILE` < `KAFKA_*` env vars.

**Metrics:** `METRICS_REPORTER` (none/prometheus/datadog/otlp), `METRICS_INTERVAL_MS` (60000), `METRICS_GROUP_FILTER` (comma-separated glob patterns, default `*`), `METRICS_GROUP_EXCLUDE` (comma-separated glob patterns, default empty), `METRICS_JVM_ENABLED` (false), `CONSUMER_MEMBER_LABELS_ENABLED` (true — tag `klag.consumer.lag` and `klag.consumer.committed_offset` with `member_host`/`consumer_id`/`client_id` for the owning consumer instance; kafka-lag-exporter parity. Empty-string values for unowned partitions; set `false` to drop the labels and cut cardinality), `LAG_TREND_DEADBAND_MSG_PER_SEC` (1.0 — STABLE band for the MCP basic lag-trend classifier; |velocity| within the band is STABLE). A group is monitored iff it matches any include segment AND no exclude segment.

**Hot Partition Detection:**
- `HOT_PARTITION_ENABLED` (true) - Enable/disable hot partition detection
- `HOT_PARTITION_SIGMA_MULTIPLIER` (2.0) - Standard deviations for outlier threshold
- `HOT_PARTITION_MIN_PARTITIONS` (3) - Minimum partitions per topic for detection
- `HOT_PARTITION_MIN_SAMPLES` (3) - Minimum samples for throughput calculation
- `HOT_PARTITION_BUFFER_SIZE` (20) - Samples to retain per partition

**Time-Based Lag Estimation:**
- `TIME_LAG_ENABLED` (true) - Enable/disable time-based lag estimation
- `TIME_LAG_MIN_MESSAGES` (100) - Minimum lag messages required for time-to-close estimates
- `TIME_LAG_INTERPOLATION_BUFFER_SIZE` (60) - Number of offset/timestamp points per partition for interpolation
- `TIME_LAG_STALE_PRODUCER_THRESHOLD_MS` (180000) - Time in ms before a producer with no offset progress is considered stale

**Commit Freshness:**
- `COMMIT_FRESHNESS_ENABLED` (true) - Track time since each group+topic last advanced its committed offset. Kafka exposes no commit timestamp, so freshness is *inferred*: klag timestamps when the observed committed offset changes. The clock starts at klag startup and resets on restart (it measures time since klag last *observed* a commit, not the absolute commit time). Staleness is only reported while lag > 0. The MCP `diagnose` stuck-consumer flag fires at 300s (constant; alert on the raw gauge at any threshold).

**MCP (AI agent access):**
- `MCP_ENABLED` (false) - Expose the `/mcp` endpoint for AI agents (SRE/dev). Opt-in; zero impact when off.
- `MCP_AUTH_TOKEN` (empty) - When set, requires `Authorization: Bearer <token>`. Empty = open (logged warning).
- `MCP_PATH` (/mcp) - HTTP path of the MCP endpoint.

MCP is read-only and served from an in-memory snapshot the metrics collector publishes after each
cycle — it never queries Kafka or touches the collection flow. Transport: Streamable HTTP (JSON-RPC
2.0 over POST; GET returns 405). Tools: `list_consumer_groups`, `get_consumer_group_lag`,
`find_lagging_groups`, `diagnose` (composite severity assessment). Requires `METRICS_REPORTER` set
(snapshot is only populated when metrics collection runs).

Each group snapshot also carries a **basic lag trend** (`growing`/`shrinking`/`stable`, per-topic +
`overallTrend` rollup, derived from lag velocity via `LAG_TREND_DEADBAND_MSG_PER_SEC`) and a rolling
**state-change history** (last 10 `from→to` transitions). `get_consumer_group_lag` returns `trends`,
`overallTrend`, and `recentTransitions`; `list_consumer_groups`/`find_lagging_groups` include
`overallTrend`; `diagnose` flags frequent state changes (rebalance storm / flapping) and **stuck
consumers** (lag > 0 but committed offset frozen). `get_consumer_group_lag`/`find_lagging_groups`
also expose `commitStalenessSeconds` (max across the group's lagging topics; -1 when none).
See `docs/superpowers/specs/2026-06-01-mcp-support-design.md`.

**Logging:** `LOG_LEVEL`, `LOG_LEVEL_KLAG` (falls back to `LOG_LEVEL`, then `INFO`), `LOG_LEVEL_KAFKA`, `LOG_LEVEL_HEALTH`, `LOG_LEVEL_METRICS`, `LOG_LEVEL_KAFKA_CLIENT` (Apache kafka-clients, default `INFO`), `LOG_LEVEL_KAFKA_LIST_OFFSETS_HANDLER` (Apache `ListOffsetsHandler`, default `ERROR` — silences the redundant per-scrape MAX_TIMESTAMP WARN on Kafka <3.0; raise to `WARN`/`DEBUG` when investigating other listOffsets issues)


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
- `klag.consumer.lag.velocity` - Lag velocity (messages/second × 100)

**Hot Partition Metrics (conditional - only reported when outliers exist):**
- `klag.hot_partition.lag` - Partition lag when statistically high (outlier)
- `klag.hot_partition` - Partition throughput × 100 when statistically high (outlier)

**Time-Based Lag Metrics:**
- `klag.consumer.lag.ms` - Lag in milliseconds (`lag_ms = currentTime - committedMessageTimestamp`). **Primary:** linear interpolation between Kafka `listOffsets` log start/end timestamps and offsets. **Fallback:** poll-time `(logEndOffset, systemTime)` history when Kafka timestamps are invalid (e.g. `logStartTimestamp=0`); requires 2+ poll intervals and does not extrapolate beyond the oldest retained sample (`TIME_LAG_INTERPOLATION_BUFFER_SIZE`).
- `klag.consumer.lag.time_to_close_seconds` - Estimated seconds until lag reaches zero (only when catching up and lag > threshold)

**Data Loss Prevention (DLP) Metrics:**
- `klag.consumer.lag.retention_percent` - Percentage of retention window consumed by lag (value × 100 for precision); enables alerting before data loss. Formula: `(lag / (logEndOffset - logStartOffset)) * 100`. Value of 100% means data loss has occurred (consumer behind logStartOffset). Excludes empty partitions.

**Commit Freshness Metrics:**
- `klag.consumer.commit.staleness_seconds` - Seconds since the committed offset last advanced for a group+topic. Only reported while lag > 0 (a frozen-but-idle consumer is not stuck). High/rising = a wedged consumer with pending work that lag alone misses. Inferred — Kafka exposes no commit timestamp, so this measures time since klag *observed* a commit and resets on klag restart.

Note: `klag.hot_partition` only has `topic` and `partition` tags (throughput is partition-level, independent of consumers)
Note: Time-based lag metrics only have `consumer_group` and `topic` tags (per-topic granularity)
Note: DLP metrics only have `consumer_group` and `topic` tags (per-topic granularity)
Note: Commit freshness metric only has `consumer_group` and `topic` tags (per-topic granularity)
Note: when `CONSUMER_MEMBER_LABELS_ENABLED=true` (default), `klag.consumer.lag` (per-partition) and `klag.consumer.committed_offset` also carry `member_host`/`consumer_id`/`client_id` for the owning consumer instance (empty strings when unowned). Partition-level `klag.partition.log_*_offset` stay member-agnostic. Members rotate on rebalance, so these series churn; the two-phase stale-gauge cleanup retires old owners within 1–2 intervals.
Note: `klag.consumer.group.state` carries the state as a *tag*; on a state change the old-state series survives 1–2 collection intervals (two-phase stale-gauge cleanup), so both states export during that window. Key alerts on the most recent sample rather than series existence.

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
- Hot Partition Detection (count, table, time series)
- Time-Based Lag Estimation (max time lag, groups catching up, time lag chart, time-to-close chart)
- Commit Staleness by Consumer Group (seconds since last observed commit, while lagging)
- Data Loss Prevention (max retention risk, at-risk topics count, retention percent chart, at-risk table)
- JVM Memory Usage (heap/non-heap)
- JVM GC Pause Time
- JVM Thread States (stacked)
- Process CPU Usage
- JVM Memory Allocation Rate
- JVM Loaded Classes

WHEN ADDING A NEW METRIC ALWAYS UPDATE GRAFANA DASHBOARD !

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
