package io.github.themoah.klag.model;

import java.util.List;

/**
 * Information about a topic partition.
 */
public record PartitionInfo(
  String topic,
  int partition,
  int leader,
  List<Integer> replicas,
  List<Integer> inSyncReplicas
) {}
