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

## Helm Chart

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

With SASL authentication:
```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.saslJaasConfig="org.apache.kafka.common.security.plain.PlainLoginModule required username='user' password='pass';"
```

See [charts/klag/README.md](charts/klag/README.md) for full configuration options.

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

## Development

### Testing Helm Chart

Run the Helm chart test suite:
```bash
./scripts/test-helm-chart.sh
```

### Local Kubernetes Testing (macOS)

Test the Helm chart on a local kind cluster:
```bash
# Run full test (creates cluster, installs chart, validates, cleans up)
./scripts/local-k8s-test.sh

# Auto-install missing dependencies (kind, helm, kubectl) via Homebrew
./scripts/local-k8s-test.sh --auto-install

# Keep cluster running after test for manual inspection
./scripts/local-k8s-test.sh --skip-cleanup

# Cleanup only (delete the kind cluster)
./scripts/local-k8s-test.sh --cleanup
```

Prerequisites: Docker Desktop, kind, helm, kubectl (use `--auto-install` to install via Homebrew).

## Contributing

1. Fork the repository
2. Create a feature branch
3. Run tests before submitting:
   ```bash
   ./gradlew test                    # Java tests
   ./scripts/test-helm-chart.sh      # Helm chart tests
   ```
4. Submit a pull request

[![vert.x](https://img.shields.io/badge/vert.x-4.5.22-purple.svg)](https://vertx.io)

Some parts of the code were written by Claude
<img src="https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/dark/claude-color.png" width="56" height="56" alt="Claude">

