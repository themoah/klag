package io.github.themoah.klag.model;

/**
 * Point-in-time snapshot of a partition's log end offset.
 * Used for calculating throughput rate over time.
 *
 * @param timestamp the time when the snapshot was taken (milliseconds)
 * @param logEndOffset the log end offset at this point in time
 */
public record PartitionThroughputSnapshot(
  long timestamp,
  long logEndOffset
) {}
