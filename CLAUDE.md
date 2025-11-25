# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Klag is a Kafka Lag Exporter built with Vert.x 4.5.22. It monitors Kafka consumer lag using a reactive, non-blocking architecture.

## Build Commands

```bash
# Requires Java 17 - use SDKMAN if needed: sdk use java 17.0.15-tem

./gradlew compileJava          # Compile
./gradlew test                 # Run tests
./gradlew assemble             # Package (creates fat JAR)
./gradlew run                  # Run with hot-reload
```

## Architecture

**Framework**: Vert.x (reactive, event-driven) with Future-based async API

**Key Components**:
- `MainVerticle` - Application entry point, deployed by Vert.x Launcher
- `kafka/KafkaClientService` - Interface for Kafka admin operations (list topics, partitions, offsets, consumer group offsets)
- `kafka/KafkaClientServiceImpl` - Implementation using Vert.x KafkaAdminClient
- `kafka/KafkaClientConfig` - Configuration with builder pattern, supports classpath properties, external files, and environment variables
- `model/` - Java records for data transfer (PartitionInfo, PartitionOffsets, ConsumerGroupOffsets)

**Configuration Loading Priority**:
1. `KafkaClientConfig.fromClasspath()` - loads `application.properties` from classpath
2. `KafkaClientConfig.fromFile(Path)` - loads from external file
3. `KafkaClientConfig.fromEnvironment()` - uses `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_REQUEST_TIMEOUT_MS` env vars

## Code Style

- All async operations return `io.vertx.core.Future<T>`
- Use Java 17 records for DTOs
- SLF4J logging with Logback
- Configuration properties prefixed with `kafka.*`
- Periodically review claude.md and update when required