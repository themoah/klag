package io.github.themoah.klag.model;

/**
 * Offset information for a topic partition, including timestamps.
 *
 * <p>{@code logEndOffset} is the true end-of-log (LATEST) offset used for lag, throughput, and
 * retention. {@code maxTimestampOffset} is the offset of the record carrying {@code logEndTimestamp}
 * (the MAX_TIMESTAMP record), used as the end anchor for time-based interpolation.
 */
public record PartitionOffsets(
  String topic,
  int partition,
  long logEndOffset,
  long logStartOffset,
  long logEndTimestamp,
  long maxTimestampOffset,
  long logStartTimestamp
) {}
