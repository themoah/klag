---
title: ACL Permissions
description: The read-only Kafka ACLs Klag needs (DESCRIBE on cluster, topics, and groups) for self-managed Kafka and Confluent Cloud.
---

import { Tabs, TabItem } from '@astrojs/starlight/components';

Klag requires **read-only** access. It uses only the Kafka Admin Client API with
`DESCRIBE` permissions — no write or alter access is ever needed.

## Required permissions

| Resource | Name | Permission | Operations |
|---|---|---|---|
| CLUSTER | kafka-cluster | DESCRIBE | Health check, list consumer groups. |
| TOPIC | `*` or prefixed | DESCRIBE | Get partition info and offsets. |
| GROUP | `*` or prefixed | DESCRIBE | Get group state and committed offsets. |

:::note
Cluster `DESCRIBE` is **always** required, even when using
[group filtering](/configuration/group-filtering/), because `listConsumerGroups()`
queries all groups before app-level filtering happens.
:::

## Self-managed Kafka

<Tabs>
  <TabItem label="All groups & topics">

```bash
# Cluster permissions (required)
kafka-acls --bootstrap-server <broker> \
  --add --allow-principal User:<klag-user> \
  --operation Describe --cluster

# All topics
kafka-acls --bootstrap-server <broker> \
  --add --allow-principal User:<klag-user> \
  --operation Describe --topic '*'

# All consumer groups
kafka-acls --bootstrap-server <broker> \
  --add --allow-principal User:<klag-user> \
  --operation Describe --group '*'
```

  </TabItem>
  <TabItem label="Prefixed only">

```bash
# Cluster permissions (required for listConsumerGroups)
kafka-acls --bootstrap-server <broker> \
  --add --allow-principal User:<klag-user> \
  --operation Describe --cluster

# Topics with prefix
kafka-acls --bootstrap-server <broker> \
  --add --allow-principal User:<klag-user> \
  --operation Describe --topic 'myapp-' \
  --resource-pattern-type prefixed

# Consumer groups with prefix
kafka-acls --bootstrap-server <broker> \
  --add --allow-principal User:<klag-user> \
  --operation Describe --group 'myapp-' \
  --resource-pattern-type prefixed
```

  </TabItem>
</Tabs>

## Confluent Cloud

Create a service account with the following ACLs using the
[Confluent CLI](https://docs.confluent.io/confluent-cli/current/overview.html).

<Tabs>
  <TabItem label="All groups & topics">

```bash
CLUSTER_ID=<your-cluster-id>
SERVICE_ACCOUNT=<service-account-id>

# Cluster permissions
confluent kafka acl create --allow --service-account $SERVICE_ACCOUNT \
  --operations describe --cluster-scope

# All topics
confluent kafka acl create --allow --service-account $SERVICE_ACCOUNT \
  --operations describe --topic '*'

# All consumer groups
confluent kafka acl create --allow --service-account $SERVICE_ACCOUNT \
  --operations describe --consumer-group '*'
```

  </TabItem>
  <TabItem label="Prefixed only">

```bash
CLUSTER_ID=<your-cluster-id>
SERVICE_ACCOUNT=<service-account-id>

# Cluster permissions (required for listConsumerGroups)
confluent kafka acl create --allow --service-account $SERVICE_ACCOUNT \
  --operations describe --cluster-scope

# Topics with prefix
confluent kafka acl create --allow --service-account $SERVICE_ACCOUNT \
  --operations describe --topic 'myapp-' --prefix

# Consumer groups with prefix
confluent kafka acl create --allow --service-account $SERVICE_ACCOUNT \
  --operations describe --consumer-group 'myapp-' --prefix
```

  </TabItem>
</Tabs>
