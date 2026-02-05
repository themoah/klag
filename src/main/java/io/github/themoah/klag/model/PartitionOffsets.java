package io.github.themoah.klag.model;

/**
 * Offset information for a topic partition, including timestamps.
 */
public record PartitionOffsets(
  String topic,
  int partition,
  long logEndOffset,
  long logStartOffset,
  long logEndTimestamp,
  long logStartTimestamp
) {}
