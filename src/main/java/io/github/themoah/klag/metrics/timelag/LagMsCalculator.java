package io.github.themoah.klag.metrics.timelag;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import java.util.OptionalLong;

/**
 * Computes per-partition lag in milliseconds using Kafka timestamps when available,
 * otherwise bounded poll-time interpolation history.
 */
public final class LagMsCalculator {

  private LagMsCalculator() {}

  /**
   * Estimates lag in milliseconds for a partition.
   *
   * @param partition partition lag snapshot
   * @param pollHistory poll-time offset history (may be null when time lag disabled)
   * @param currentTime current wall-clock time in milliseconds
   * @return lag in ms, or empty when no reliable estimate is available
   */
  public static OptionalLong estimatePartitionLagMs(
    PartitionLag partition,
    OffsetTimestampTracker pollHistory,
    long currentTime) {

    if (partition.lag() <= 0) {
      return OptionalLong.of(0);
    }

    var kafkaLagMs = KafkaOffsetTimestampInterpolator.estimateLagMs(partition, currentTime);
    if (kafkaLagMs.isPresent()) {
      return kafkaLagMs;
    }

    if (pollHistory == null) {
      return OptionalLong.empty();
    }

    if (!pollHistory.hasInterpolationData(partition.topic(), partition.partition())) {
      return OptionalLong.empty();
    }

    var oldestOffset = pollHistory.getOldestOffset(partition.topic(), partition.partition());
    if (oldestOffset.isEmpty() || partition.committedOffset() < oldestOffset.getAsLong()) {
      return OptionalLong.empty();
    }

    OptionalLong ts = pollHistory.getInterpolatedTimestamp(
        partition.topic(), partition.partition(), partition.committedOffset());
    if (ts.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Math.max(0, currentTime - ts.getAsLong()));
  }
}
