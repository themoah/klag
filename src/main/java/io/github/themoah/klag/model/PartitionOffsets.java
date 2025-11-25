package io.github.themoah.klag.model;

/**
 * Offset information for a topic partition.
 */
public record PartitionOffsets(
  String topic,
  int partition,
  long logEndOffset,
  long logStartOffset
) {}
