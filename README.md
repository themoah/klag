# Klag  [![license-badge][]][license]

[license]:             https://github.com/themoah/klag/blob/main/LICENSE
[license-badge]:       https://img.shields.io/badge/License-Apache%202.0-blue.svg

Klag is a service for monitoring Kafka consumer behaviour and lag.
Inspired by [kafka lag exporter](https://github.com/seglo/kafka-lag-exporter) that was archived in 2024.
Simple, lightweight and extendable lag exporter built with vert.x and micrometer.

* Lag velocity (track how fast lag is growing or decreasing).
* Tracks consumer groups:
  * State changes (Stable, Rebalancing, Stale, Dead, Empty).
  * Stale or deleted groups are deleted from reporting.

Supported sinks:
* Prometheus endpoint.
* Datadog.
* Otel/OLTP
* (planned) Prometheus push gateway.
* (planned) statsD.
* (planned) Google stackdriver.

`docker run --env-file .env themoah/klag`
Helm chart - WIP.

sample `.env` file
```dotenv
# kafka configuration
KAFKA_BOOTSTRAP_SERVERS=instance.gcp.confluent.cloud:9092
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=PLAIN
KAFKA_SASL_JAAS_CONFIG="org.apache.kafka.common.security.plain.PlainLoginModule required username=${SASL_USERNAME} password=${SASL_PASSWORD};"

# metrics reporter
METRICS_REPORTER=prometheus
METRICS_INTERVAL_MS=30000
METRICS_GROUP_FILTER=*

# jvm metrics
METRICS_JVM_ENABLED=true
```
## Building

To launch your tests:
```bash
./gradlew clean test
```

To package your application:
```bash
./gradlew clean assemble
```

To run your application:
```bash
./gradlew clean run
```

## Configuration

Configure the application using `src/main/resources/application.properties`:

```properties
kafka.bootstrap.servers=localhost:9092
kafka.request.timeout.ms=30000
```

Or use environment variables:
- `KAFKA_BOOTSTRAP_SERVERS` - Kafka bootstrap servers (default: `localhost:9092`)
- `KAFKA_REQUEST_TIMEOUT_MS` - Request timeout in milliseconds (default: `30000`)


[![vert.x](https://img.shields.io/badge/vert.x-4.5.22-purple.svg)](https://vertx.io)

Some parts of the code were written by Claude
<img src="https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/dark/claude-color.png" width="56" height="56" alt="Claude">

