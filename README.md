# Klag  [![license-badge][]][license]

[license]:             https://github.com/themoah/klag/blob/main/LICENSE
[license-badge]:       https://img.shields.io/badge/License-Apache%202.0-blue.svg

Klag is a service for monitoring Kafka consumer behaviour and lag.
Inspired by [kafka lag exporter](https://github.com/seglo/kafka-lag-exporter) that was archived in 2024.
Simple, lightweight and extendable lag exporter built with vert.x and micrometer.

Not only it reports lag metrics, but also:
* Tracks consumer groups:
  * State changes (Stable, Rebalancing, Stale, Dead, Empty).
  * Stale or deleted groups are deleted from reporting.

Supported sinks:
* Prometheus endpoint.
* Datadog.
* (planned) Prometheus push gateway.
* (planned) OTel.
* (planned) statsD.
* (planned) Google stackdriver.

Helm chart - WIP.

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

