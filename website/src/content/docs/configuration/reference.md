---
title: Configuration Reference
description: Complete reference of every Klag environment variable across app, Kafka, metrics, hot partitions, time-based lag, MCP, OTLP, and logging.
---

Most Klag settings are environment variables. Kafka AdminClient properties additionally
support an optional `application.properties` file:

1. `application.properties` on the classpath, if you add one. Klag does **not** bundle
   this file.
2. An external file selected by `KLAG_CONFIG_FILE`.
3. `KAFKA_*` environment variables (highest precedence).

The properties files configure `kafka.*` keys; they are not a general configuration
source for every setting on this page.

Only settings read through Klag's `Env` helper support JVM system properties. Those keys
resolve in this order: environment variable `NAME` → `-DNAME` → dotted
`-Dname.dotted` (for example, `HTTP_PORT` → `-Dhttp.port=8881`). They are:

- `HTTP_PORT`, `KAFKA_HEALTH_CHECK_INTERVAL_MS`
- `KAFKA_CLUSTER_NAME`, `KAFKA_CLUSTERS`
- `KAFKA_CHUNK_COUNT`, `KAFKA_CHUNK_DELAY_MS`
- `METRICS_REPORTER`, `METRICS_INTERVAL_MS`, `METRICS_GROUP_FILTER`,
  `METRICS_GROUP_EXCLUDE`, `METRICS_JVM_ENABLED`,
  `CONSUMER_MEMBER_LABELS_ENABLED`, `LAG_TREND_DEADBAND_MSG_PER_SEC`
- all `HOT_PARTITION_*` and `TIME_LAG_*` settings listed below
- `COMMIT_FRESHNESS_ENABLED`, `ISR_ENABLED`, `DATA_SKEW_ENABLED`, `DATA_SKEW_MIN_PARTITIONS`

Kafka forwarding, `KLAG_CONFIG_FILE`, Vert.x, MCP, and reporter-specific integration
settings such as `DD_*`, `OTLP_*`, and `OTEL_*` read environment variables directly and
do not use that `-D` resolution chain. Logging is a separate exception: Logback can
resolve exact-name JVM properties such as
`-DLOG_LEVEL=DEBUG`, but it does not provide `Env`-style dotted aliases such as
`-Dlog.level`.

## Application

| Variable | Default | Description |
|---|---|---|
| `HTTP_PORT` | `8888` | HTTP server port. |
| `KAFKA_HEALTH_CHECK_INTERVAL_MS` | `30000` | Health-check interval. |
| `VERTX_USE_VIRTUAL_THREADS` | `true` | Use virtual threads for verticle deployment. Set `false` for the event-loop model. Environment only. |
| `KLAG_CONFIG_FILE` | _(unset)_ | Path to an external `application.properties` file containing `kafka.*` properties. Environment only. |

## Kafka

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker addresses for the default (single) cluster. |
| `KAFKA_CLUSTER_NAME` | _(unset)_ | Optional `cluster_name` tag on Kafka series. Ignored when `KAFKA_CLUSTERS` is set. |
| `KAFKA_CLUSTERS` | _(unset)_ | JSON array of clusters scraped in one process. Each object: `name`, `bootstrapServers`, optional `requestTimeoutMs`, `groupFilter`, `groupExclude`, `properties`. SASL/SSL from `KAFKA_*` still apply as defaults. Helm: do not put credentials in `properties` (plaintext Deployment env); use `kafka.existingSecret` for shared creds. Distinct per-cluster credentials are not supported. Not forwarded to the AdminClient. |
| `KAFKA_REQUEST_TIMEOUT_MS` | `30000` | Request timeout. |
| `KAFKA_CHUNK_COUNT` | `1` | Split offset requests into N batches. |
| `KAFKA_CHUNK_DELAY_MS` | `0` | Delay (ms) between batches. |

Any `KAFKA_X_Y_Z` environment variable is mapped to `kafka.x.y.z` and forwarded to the
Kafka AdminClient. For example, `KAFKA_SECURITY_PROTOCOL` becomes
`kafka.security.protocol`. This generic forwarding is environment-only; in a properties
file, use the `kafka.*` key directly. `KAFKA_CLUSTERS` and `KAFKA_CLUSTER_NAME` are Klag
process settings and are not forwarded as AdminClient properties.

For SASL/SSL, common settings include `KAFKA_SECURITY_PROTOCOL`,
`KAFKA_SASL_MECHANISM`, and `KAFKA_SASL_JAAS_CONFIG`. See
[Installation](/getting-started/installation/) and
[ACL Permissions](/kafka/acl-permissions/).

## Metrics

| Variable | Default | Description |
|---|---|---|
| `METRICS_REPORTER` | `none` | `none`, `prometheus`, `datadog`, or `otlp`. |
| `METRICS_INTERVAL_MS` | `60000` | How often to collect metrics. |
| `METRICS_GROUP_FILTER` | `*` | Comma-separated glob include patterns. |
| `METRICS_GROUP_EXCLUDE` | _(empty)_ | Comma-separated glob exclude patterns. |
| `METRICS_JVM_ENABLED` | `false` | Export JVM metrics. |
| `CONSUMER_MEMBER_LABELS_ENABLED` | `true` | Tag consumer-owned per-partition lag metrics with `member_host` / `consumer_id` / `client_id` (kafka-lag-exporter parity). Set `false` to drop them and reduce cardinality. Cheaper than Prometheus `labeldrop` of the same names, which still scrapes the series first. |
| `LAG_TREND_DEADBAND_MSG_PER_SEC` | `1.0` | STABLE band for the MCP lag-trend classifier. |
| `COMMIT_FRESHNESS_ENABLED` | `true` | Track inferred time since a lagging group/topic's committed-offset sum last changed. |
| `ISR_ENABLED` | `true` | Detect and report under-replicated partitions. |
| `DATA_SKEW_ENABLED` | `false` | Score retained-size imbalance across a topic's partitions (`klag.topic.size_skew`). Opt-in. |
| `DATA_SKEW_MIN_PARTITIONS` | `2` | Min partitions per topic before a size-skew score is emitted. |

Every setting in this table supports an environment variable, an exact-name JVM
property, and a dotted JVM property. For example, the reporter can be selected with
`METRICS_REPORTER=prometheus`, `-DMETRICS_REPORTER=prometheus`, or
`-Dmetrics.reporter=prometheus`, in that precedence order.

A group is monitored **iff** it matches any include segment **and** no exclude segment.
See [Group Filtering](/configuration/group-filtering/).

Commit freshness observes the sum of committed offsets across a group/topic's
partitions. Any change, including a rewind, resets its clock. Caught-up periods remove
the tracking baseline; it is established again when lag resumes. Restarting Klag also
resets observation.

See [Metrics Overview](/metrics/overview/) for commit-staleness semantics,
[ISR Monitoring](/metrics/isr/) for the under-replicated-partition metric, and
[Topic Data Skew](/metrics/data-skew/) for the opt-in size-skew score.

## Hot partition detection

| Variable | Default | Description |
|---|---|---|
| `HOT_PARTITION_ENABLED` | `true` | Enable hot-partition detection. |
| `HOT_PARTITION_SIGMA_MULTIPLIER` | `2.0` | Std-devs for the outlier threshold. |
| `HOT_PARTITION_MIN_PARTITIONS` | `3` | Min partitions per topic for detection. |
| `HOT_PARTITION_MIN_SAMPLES` | `3` | Min samples for throughput calc. |
| `HOT_PARTITION_BUFFER_SIZE` | `20` | Samples retained per partition. |

## Time-based lag estimation

| Variable | Default | Description |
|---|---|---|
| `TIME_LAG_ENABLED` | `true` | Enable time-based lag estimation. |
| `TIME_LAG_MIN_MESSAGES` | `100` | Min lag messages for time-to-close estimates. |
| `TIME_LAG_INTERPOLATION_BUFFER_SIZE` | `60` | Offset/timestamp points per partition. |
| `TIME_LAG_STALE_PRODUCER_THRESHOLD_MS` | `180000` | Time before a producer is considered stale. |

## MCP (AI agent access)

| Variable | Default | Description |
|---|---|---|
| `MCP_ENABLED` | `false` | Expose the `/mcp` endpoint (opt-in). |
| `MCP_AUTH_TOKEN` | _(empty)_ | Require `Authorization: Bearer <token>` when set. |
| `MCP_PATH` | `/mcp` | HTTP path of the MCP endpoint. |

See [MCP Endpoint](/ai/mcp/) for details.

## Datadog (when `METRICS_REPORTER=datadog`)

| Variable | Default | Description |
|---|---|---|
| `DD_API_KEY` | _(unset; required)_ | Datadog API key used for metric submission. |
| `DD_APP_KEY` | _(unset; optional)_ | Datadog application key used for metadata operations. |
| `DD_SITE` | `datadoghq.com` | Datadog site, such as `datadoghq.eu`. Defaults to `datadoghq.com`. |

These are environment-only. See [Datadog](/integrations/datadog/).

## OTLP (when `METRICS_REPORTER=otlp`)

**Standard OpenTelemetry variables:**

| Variable | Description |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Base endpoint (e.g. `http://localhost:4318`). |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | Metrics-specific endpoint (overrides base). |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (`key1=value1,key2=value2`). |
| `OTEL_EXPORTER_OTLP_METRICS_HEADERS` | Metrics-specific headers. |
| `OTEL_METRIC_EXPORT_INTERVAL` | Export interval (ms), default `60000`. |
| `OTEL_SERVICE_NAME` | Service name, default `klag`. |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes. |
| `OTEL_EXPORTER_OTLP_CERTIFICATE` | Path to a PEM CA bundle the exporter additionally trusts (for an HTTPS collector with an internally-signed cert). Added on top of the JVM default trust, never replacing it. |

**Custom variables** — each takes precedence over its `OTEL_*` equivalent:

| Variable | Overrides | Description |
|---|---|---|
| `OTLP_ENDPOINT` | `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` | Full metrics endpoint URL (e.g. `http://localhost:4318/v1/metrics`). |
| `OTLP_STEP_MS` | `OTEL_METRIC_EXPORT_INTERVAL` | Export interval in ms (default `60000`). |
| `OTLP_HEADERS` | `OTEL_EXPORTER_OTLP_METRICS_HEADERS`, `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (`key1=value1,key2=value2`). |
| `OTLP_RESOURCE_ATTRIBUTES` | `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes (`key1=value1,key2=value2`). |
| `OTLP_CA_CERT_PATH` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | Path to a PEM CA bundle the exporter additionally trusts, for an HTTPS collector with an internally-signed cert. Added on top of the JVM default trust, never replacing it. |

Transport is HTTP/protobuf (port 4318); both `http://` and `https://` endpoints are
supported — for an internally-signed HTTPS collector, point `OTLP_CA_CERT_PATH` (or
`OTEL_EXPORTER_OTLP_CERTIFICATE`) at the CA bundle. Temporality is cumulative.
See [OTLP & Grafana Cloud](/integrations/otlp-grafana/).

## Logging

Logging settings are interpreted directly by Logback. Set them as environment variables
or exact-name JVM properties (for example, `-DLOG_LEVEL=DEBUG`). Dotted `Env` aliases
such as `-Dlog.level` are not supported:

| Variable | Default | Logger |
|---|---|---|
| `LOG_LEVEL` | `INFO` | Root logger. |
| `LOG_LEVEL_KLAG` | `LOG_LEVEL`, then `INFO` | All Klag packages. |
| `LOG_LEVEL_KAFKA` | `INFO` | Klag's Kafka package. |
| `LOG_LEVEL_HEALTH` | `INFO` | Klag's health package. |
| `LOG_LEVEL_METRICS` | `INFO` | Klag's metrics package. |
| `LOG_LEVEL_VERTX` | `WARN` | Vert.x framework (`io.vertx`). |
| `LOG_LEVEL_KAFKA_CLIENT` | `INFO` | Apache Kafka client (`org.apache.kafka`). |
| `LOG_LEVEL_KAFKA_LIST_OFFSETS_HANDLER` | `ERROR` | Kafka `ListOffsetsHandler`; use `WARN` or `DEBUG` when investigating list-offset requests. |
| `LOG_LEVEL_NETTY_BOOTSTRAP` | `ERROR` | Netty `ServerBootstrap`. |

The broader `io.netty` logger is fixed at `WARN`; only `ServerBootstrap` has a
dedicated environment override.
