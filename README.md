# Klag

[![vert.x](https://img.shields.io/badge/vert.x-4.5.22-purple.svg)](https://vertx.io)

Kafka Lag Exporter - A Vert.x application for monitoring Kafka consumer lag.

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

## Help

- [Vert.x Documentation](https://vertx.io/docs/)
- [Vert.x Stack Overflow](https://stackoverflow.com/questions/tagged/vert.x?sort=newest&pageSize=15)
- [Vert.x User Group](https://groups.google.com/forum/?fromgroups#!forum/vertx)
- [Vert.x Discord](https://discord.gg/6ry7aqPWXy)
