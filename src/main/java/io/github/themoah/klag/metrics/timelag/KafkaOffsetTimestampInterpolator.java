package io.github.themoah.klag.metrics.timelag;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import java.util.OptionalLong;

/**
 * Estimates committed-offset message time from Kafka {@code listOffsets} anchor points
 * ({@code logStartOffset/logStartTimestamp} and {@code logEndOffset/logEndTimestamp}).
 */
public final class KafkaOffsetTimestampInterpolator {

  private KafkaOffsetTimestampInterpolator() {}

  /**
   * Returns true when partition log boundaries have usable Kafka message timestamps
   * and the committed offset lies within the log span (or at log end).
   */
  public static boolean hasValidAnchors(PartitionLag partition) {
    if (partition.logStartTimestamp() <= 0 || partition.logEndTimestamp() <= 0) {
      return false;
    }
    if (partition.logEndTimestamp() < partition.logStartTimestamp()) {
      return false;
    }
    if (partition.logEndOffset() <= partition.logStartOffset()) {
      return false;
    }
    if (partition.committedOffset() < partition.logStartOffset()) {
      return false;
    }
    return partition.committedOffset() <= partition.logEndOffset();
  }

  /**
   * Interpolates the Kafka message timestamp for {@code committedOffset} between log boundaries.
   *
   * @return estimated message timestamp, or empty when anchors are invalid
   */
  public static OptionalLong interpolateCommittedTimestamp(PartitionLag partition) {
    if (!hasValidAnchors(partition)) {
      return OptionalLong.empty();
    }

    if (partition.committedOffset() >= partition.logEndOffset()) {
      return OptionalLong.of(partition.logEndTimestamp());
    }

    return OptionalLong.of(linearInterpolate(
      partition.logStartOffset(), partition.logStartTimestamp(),
      partition.logEndOffset(), partition.logEndTimestamp(),
      partition.committedOffset()));
  }

  /**
   * Estimates lag in milliseconds as {@code currentTime - committedMessageTimestamp}.
   */
  public static OptionalLong estimateLagMs(PartitionLag partition, long currentTime) {
    OptionalLong ts = interpolateCommittedTimestamp(partition);
    if (ts.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Math.max(0, currentTime - ts.getAsLong()));
  }

  static long linearInterpolate(long o1, long t1, long o2, long t2, long targetOffset) {
    if (o1 == o2) {
      return t1;
    }
    double slope = (double) (t2 - t1) / (o2 - o1);
    return Math.round(t1 + slope * (targetOffset - o1));
  }
}
