---
title: Kubernetes (Helm)
description: Deploy Klag on Kubernetes with the official Helm chart, including SASL authentication and ServiceMonitor support.
---

The official Helm chart is the recommended way to run Klag on Kubernetes. It is
published to a Helm repository served from GitHub Pages and indexed on
[Artifact Hub](https://artifacthub.io/packages/helm/klag/klag).

## Install

```bash
helm repo add klag https://themoah.github.io/klag
helm repo update
helm search repo klag   # find the latest version

helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

Pin a version with `--version`, e.g. `--version 0.1.12`.

## With SASL authentication

```bash
helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.saslJaasConfig="org.apache.kafka.common.security.plain.PlainLoginModule required username='user' password='pass';"
```

## From a local checkout (development)

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

## Configuration

The chart exposes Kafka connection, metrics reporter, resource limits, and (optionally) a
`ServiceMonitor` for the Prometheus Operator. See the
[chart README](https://github.com/themoah/klag/blob/main/charts/klag/README.md) for the
full list of values, and the [Configuration Reference](/configuration/reference/) for the
underlying environment variables.

## Permissions

Klag needs only read-only Kafka access — see [ACL Permissions](/kafka/acl-permissions/).
On Kubernetes-managed Kafka, [Strimzi](/deployment/strimzi/) is fully supported.
