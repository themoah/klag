---
title: Strimzi
description: Klag works out of the box with Kafka clusters managed by the Strimzi operator on Kubernetes (KRaft).
---

Klag works with Kafka clusters managed by the [Strimzi](https://strimzi.io) operator —
the common production way to run Kafka on Kubernetes.

## Connecting

No special chart configuration is needed. Point Klag at the Strimzi bootstrap service:

```bash
helm install klag klag/klag \
  --set kafka.bootstrapServers=my-cluster-kafka-bootstrap:9092
```

The bootstrap service is named `<cluster>-kafka-bootstrap` in the namespace where your
Strimzi `Kafka` resource lives.

## Verified versions

A dedicated end-to-end suite installs the Strimzi operator, provisions a real KRaft Kafka
cluster via Strimzi CRDs, points the chart at the `*-kafka-bootstrap` service, generates
real lag, and asserts Klag scrapes it. It is verified against Strimzi-managed Kafka
**4.1.0** and **4.2.0** (the versions the current Strimzi operator supports).

```bash
# Single version
./scripts/e2e-strimzi-test.sh

# Specific Kafka version
KAFKA_VERSION=4.1.0 ./scripts/e2e-strimzi-test.sh

# Matrix across supported versions
./scripts/e2e-strimzi-matrix.sh 4.1.0 4.2.0
```

Both e2e suites run in CI on every PR. See [Build from Source](/development/build/) for
the full test workflow.
