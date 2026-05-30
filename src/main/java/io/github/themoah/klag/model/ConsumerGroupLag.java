package io.github.themoah.klag.model;

import java.util.List;

/**
 * Aggregated lag metrics for a consumer group.
 */
public record ConsumerGroupLag(
  String consumerGroup,
  long totalLag,
  long maxLag,
  long minLag,
  List<PartitionLag> partitions
) {

  /**
   * Lag details for a single partition.
   *
   * <p>{@code logEndOffset} is the true end-of-log (LATEST) offset and is the basis for
   * {@code lag}, throughput, and retention. {@code maxTimestampOffset} is the offset of the
   * record carrying {@code logEndTimestamp} (the MAX_TIMESTAMP record); it can be less than
   * {@code logEndOffset} and is the correct end anchor for time-based interpolation.
   */
  public record PartitionLag(
    String topic,
    int partition,
    long logEndOffset,
    long logStartOffset,
    long logEndTimestamp,
    long maxTimestampOffset,
    long logStartTimestamp,
    long committedOffset,
    long lag
  ) {
    /**
     * Convenience factory where the max-timestamp anchor coincides with the true end offset.
     */
    public static PartitionLag of(
      String topic,
      int partition,
      long logEndOffset,
      long logStartOffset,
      long logEndTimestamp,
      long logStartTimestamp,
      long committedOffset
    ) {
      return of(topic, partition, logEndOffset, logStartOffset, logEndTimestamp,
        logEndOffset, logStartTimestamp, committedOffset);
    }

    public static PartitionLag of(
      String topic,
      int partition,
      long logEndOffset,
      long logStartOffset,
      long logEndTimestamp,
      long maxTimestampOffset,
      long logStartTimestamp,
      long committedOffset
    ) {
      long lag = Math.max(0, logEndOffset - committedOffset);
      return new PartitionLag(topic, partition, logEndOffset, logStartOffset,
        logEndTimestamp, maxTimestampOffset, logStartTimestamp, committedOffset, lag);
    }
  }

  /**
   * Creates ConsumerGroupLag from a list of partition lags.
   */
  public static ConsumerGroupLag fromPartitions(String consumerGroup, List<PartitionLag> partitions) {
    if (partitions.isEmpty()) {
      return new ConsumerGroupLag(consumerGroup, 0, 0, 0, partitions);
    }

    long totalLag = partitions.stream().mapToLong(PartitionLag::lag).sum();
    long maxLag = partitions.stream().mapToLong(PartitionLag::lag).max().orElse(0);
    long minLag = partitions.stream().mapToLong(PartitionLag::lag).min().orElse(0);

    return new ConsumerGroupLag(consumerGroup, totalLag, maxLag, minLag, partitions);
  }
}
