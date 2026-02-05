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
   */
  public record PartitionLag(
    String topic,
    int partition,
    long logEndOffset,
    long logStartOffset,
    long logEndTimestamp,
    long logStartTimestamp,
    long committedOffset,
    long lag
  ) {
    public static PartitionLag of(
      String topic,
      int partition,
      long logEndOffset,
      long logStartOffset,
      long logEndTimestamp,
      long logStartTimestamp,
      long committedOffset
    ) {
      long lag = Math.max(0, logEndOffset - committedOffset);
      return new PartitionLag(topic, partition, logEndOffset, logStartOffset,
        logEndTimestamp, logStartTimestamp, committedOffset, lag);
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
