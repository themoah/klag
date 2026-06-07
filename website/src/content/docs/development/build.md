---
title: Build from Source
description: Build and test Klag from source with Gradle and Java 21, including Helm chart tests and end-to-end suites with real Kafka.
---

Klag is a Java 21 / Vert.x project built with Gradle.

## Requirements

- **Java 21** (use SDKMAN if needed: `sdk use java 21.0.9-tem`).
- Use `./gradlew` locally; CI uses `gradle` directly (no wrapper JAR committed).

## Common tasks

```bash
./gradlew compileJava   # Compile
./gradlew test          # Run tests
./gradlew assemble      # Package (fat JAR)
./gradlew run           # Run with hot-reload
```

## Helm chart template tests

Fast and offline. Lints the chart and renders every values permutation:

```bash
./scripts/test-helm-chart.sh
```

## End-to-end tests (k3d + real Kafka)

The canonical integration test spins up a disposable [k3d](https://k3d.io) cluster,
deploys a real single-node KRaft Kafka, builds the Klag image from the local
`Dockerfile`, installs the Helm chart, generates real lag, and asserts Klag exposes it
via `/metrics`. Nothing is mocked.

```bash
./scripts/e2e-test.sh                 # Full e2e + cleanup
./scripts/e2e-test.sh --auto-install  # Install k3d/kubectl/helm via Homebrew
./scripts/e2e-test.sh --skip-cleanup  # Keep the cluster for inspection
KLAG_IMAGE=themoah/klag:0.1.12 ./scripts/e2e-test.sh   # Test a published image
KAFKA_IMAGE=apache/kafka:3.7.0 ./scripts/e2e-test.sh   # Test an older broker
./scripts/e2e-test.sh --cleanup       # Delete the test cluster
```

## Strimzi compatibility tests

See [Strimzi](/deployment/strimzi/):

```bash
./scripts/e2e-strimzi-test.sh
KAFKA_VERSION=4.1.0 ./scripts/e2e-strimzi-test.sh
./scripts/e2e-strimzi-matrix.sh 4.1.0 4.2.0
```

Prerequisites: Docker, k3d, helm, kubectl (`--auto-install` installs via Homebrew). Both
e2e suites run in CI on every PR (`.github/workflows/e2e.yml`).

## Native image

See [Native Image](/deployment/native-image/) for the GraalVM build.
