package io.github.themoah.klag.metrics.timelag;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import java.util.OptionalLong;

/**
 * Estimates committed-offset message time from Kafka {@code listOffsets} anchor points.
 *
 * <p>Anchors are {@code (logStartOffset, logStartTimestamp)} and
 * {@code (maxTimestampOffset, logEndTimestamp)}. The end anchor uses {@code maxTimestampOffset}
 * (the offset of the MAX_TIMESTAMP record), NOT {@code logEndOffset} (the true end-of-log),
 * because only the former is the offset that actually carries {@code logEndTimestamp}. The two
 * can differ; conflating them would skip interpolation or skew the timestamp.
 */
public final class KafkaOffsetTimestampInterpolator {

  private KafkaOffsetTimestampInterpolator() {}

  /**
   * Returns true when partition log boundaries have usable Kafka message timestamps
   * and the committed offset lies within the log span (or at/after the timestamp anchor).
   */
  public static boolean hasValidAnchors(PartitionLag partition) {
    if (partition.logStartTimestamp() <= 0 || partition.logEndTimestamp() <= 0) {
      return false;
    }
    if (partition.logEndTimestamp() < partition.logStartTimestamp()) {
      return false;
    }
    if (partition.maxTimestampOffset() <= partition.logStartOffset()) {
      return false;
    }
    if (partition.committedOffset() < partition.logStartOffset()) {
      return false;
    }
    // Committed may sit between the max-timestamp record and the true end of the log;
    // bound it by the true end offset, not the timestamp anchor.
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

    // At or beyond the highest-timestamp record: the newest known timestamp applies.
    if (partition.committedOffset() >= partition.maxTimestampOffset()) {
      return OptionalLong.of(partition.logEndTimestamp());
    }

    return OptionalLong.of(linearInterpolate(
      partition.logStartOffset(), partition.logStartTimestamp(),
      partition.maxTimestampOffset(), partition.logEndTimestamp(),
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
