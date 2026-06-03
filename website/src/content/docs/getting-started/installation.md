---
title: Installation
description: Install Klag via the Helm chart, Docker, or a Docker environment file — including SASL authentication examples.
---

import { Tabs, TabItem } from '@astrojs/starlight/components';

Klag ships as a Docker image and a Helm chart. Pick whichever matches your platform.

## Helm chart

The chart is published to a Helm repository served from GitHub Pages and indexed on
[Artifact Hub](https://artifacthub.io/packages/helm/klag/klag).

```bash
helm repo add klag https://themoah.github.io/klag
helm repo update
helm search repo klag   # find the latest version

helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

Pin a specific version with `--version`, e.g.
`helm install klag klag/klag --version 0.1.12 ...`.

<Tabs>
  <TabItem label="SASL authentication">

```bash
helm install klag klag/klag \
  --set kafka.bootstrapServers="kafka:9092" \
  --set kafka.securityProtocol="SASL_SSL" \
  --set kafka.saslMechanism="PLAIN" \
  --set kafka.saslJaasConfig="org.apache.kafka.common.security.plain.PlainLoginModule required username='user' password='pass';"
```

  </TabItem>
  <TabItem label="Local checkout (dev)">

```bash
helm install klag ./charts/klag \
  --set kafka.bootstrapServers="kafka-broker:9092"
```

  </TabItem>
</Tabs>

See the [chart README](https://github.com/themoah/klag/blob/main/charts/klag/README.md)
for full configuration options, and [Kubernetes deployment](/deployment/kubernetes/) for
production guidance.

## Docker with an environment file

```bash
docker run --env-file .env themoah/klag:latest
```

A sample `.env`:

```dotenv
# Kafka connection
KAFKA_BOOTSTRAP_SERVERS=instance.gcp.confluent.cloud:9092
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=PLAIN
KAFKA_SASL_JAAS_CONFIG="org.apache.kafka.common.security.plain.PlainLoginModule required username=${SASL_USERNAME} password=${SASL_PASSWORD};"

# Metrics
METRICS_REPORTER=prometheus
METRICS_INTERVAL_MS=30000
METRICS_GROUP_FILTER=*
METRICS_GROUP_EXCLUDE=

# Optional: JVM metrics
METRICS_JVM_ENABLED=true
```

## Next steps

- [Configuration Reference](/configuration/reference/) — all environment variables.
- [ACL Permissions](/kafka/acl-permissions/) — the read-only grants Klag needs.
- [Build from Source](/development/build/) — for contributors.
