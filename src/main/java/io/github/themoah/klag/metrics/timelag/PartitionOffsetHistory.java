package io.github.themoah.klag.metrics.timelag;

import io.github.themoah.klag.model.OffsetTimestampPoint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-partition interpolation table for converting message offsets to timestamps.
 * Records (logEndOffset, systemTimestamp) pairs at each poll interval.
 *
 * <p>This enables interpolation/extrapolation to estimate when a given offset
 * was produced, which is used to calculate lag in milliseconds without relying
 * on Kafka message timestamps (which are often unavailable).
 */
public class PartitionOffsetHistory {

  private static final Logger log = LoggerFactory.getLogger(PartitionOffsetHistory.class);
  private static final int MIN_POINTS_FOR_INTERPOLATION = 2;

  private final String topic;
  private final int partition;
  private final ArrayDeque<OffsetTimestampPoint> points;
  private final int maxSize;
  private boolean hasLoggedDataReady = false;

  public PartitionOffsetHistory(String topic, int partition, int bufferSize) {
    this.topic = topic;
    this.partition = partition;
    this.points = new ArrayDeque<>(bufferSize);
    this.maxSize = bufferSize;
  }

  /**
   * Records the current log end offset with the current system time.
   *
   * @param offset the current log end offset
   */
  public void addPoint(long offset) {
    addPoint(offset, System.currentTimeMillis());
  }

  /**
   * Records an offset with a specific timestamp (used for testing).
   *
   * @param offset the log end offset
   * @param timestamp the system timestamp
   */
  void addPoint(long offset, long timestamp) {
    if (points.size() >= maxSize) {
      points.removeFirst();  // Evict oldest
    }
    points.addLast(new OffsetTimestampPoint(offset, timestamp));

    // Log when we first have enough data for interpolation
    if (!hasLoggedDataReady && points.size() >= MIN_POINTS_FOR_INTERPOLATION) {
      log.info("Collected {} samples for {}:{} - interpolation now available",
        MIN_POINTS_FOR_INTERPOLATION, topic, partition);
      hasLoggedDataReady = true;
    }
  }

  /**
   * Interpolates/extrapolates a timestamp for the given offset.
   *
   * @param targetOffset the committed offset to find timestamp for
   * @return estimated timestamp, or empty if insufficient data
   */
  public OptionalLong interpolateTimestamp(long targetOffset) {
    if (!hasEnoughData()) {
      return OptionalLong.empty();
    }

    List<OffsetTimestampPoint> pointsList = new ArrayList<>(points);
    OffsetTimestampPoint oldest = pointsList.get(0);
    OffsetTimestampPoint latest = pointsList.get(pointsList.size() - 1);

    // Consumer is caught up or ahead
    if (targetOffset >= latest.offset()) {
      return OptionalLong.of(System.currentTimeMillis());
    }

    // Find bracketing points for interpolation
    OffsetTimestampPoint lower = null;
    OffsetTimestampPoint upper = null;

    for (int i = 0; i < pointsList.size() - 1; i++) {
      OffsetTimestampPoint p1 = pointsList.get(i);
      OffsetTimestampPoint p2 = pointsList.get(i + 1);

      if (p1.offset() <= targetOffset && targetOffset <= p2.offset()) {
        lower = p1;
        upper = p2;
        break;
      }
    }

    if (lower != null && upper != null) {
      // Interpolate between two points
      return OptionalLong.of(linearInterpolate(lower, upper, targetOffset));
    }

    // Target offset is older than our oldest point - extrapolate backward
    if (targetOffset < oldest.offset() && pointsList.size() >= 2) {
      OffsetTimestampPoint p1 = pointsList.get(0);
      OffsetTimestampPoint p2 = pointsList.get(1);
      return OptionalLong.of(linearInterpolate(p1, p2, targetOffset));
    }

    return OptionalLong.empty();
  }

  /**
   * Linear interpolation/extrapolation between two points.
   */
  private long linearInterpolate(OffsetTimestampPoint p1, OffsetTimestampPoint p2, long targetOffset) {
    // Avoid division by zero if offsets are same
    if (p1.offset() == p2.offset()) {
      return p1.timestamp();
    }

    // t = t1 + (targetOffset - o1) * (t2 - t1) / (o2 - o1)
    double slope = (double) (p2.timestamp() - p1.timestamp()) / (p2.offset() - p1.offset());
    return Math.round(p1.timestamp() + slope * (targetOffset - p1.offset()));
  }

  /**
   * Returns true if we have enough data points for interpolation.
   */
  public boolean hasEnoughData() {
    return points.size() >= MIN_POINTS_FOR_INTERPOLATION;
  }

  /**
   * Returns true if the producer appears stale (no offset progress for threshold duration).
   *
   * @param currentTime current system time
   * @param thresholdMs time threshold in milliseconds
   * @return true if producer is stale
   */
  public boolean isProducerStale(long currentTime, long thresholdMs) {
    if (points.size() < 2) {
      return false;
    }

    // Check if the latest two points have same offset
    List<OffsetTimestampPoint> pointsList = new ArrayList<>(points);
    OffsetTimestampPoint latest = pointsList.get(pointsList.size() - 1);
    OffsetTimestampPoint previous = pointsList.get(pointsList.size() - 2);

    if (latest.offset() == previous.offset()) {
      // No progress - check how long
      return (currentTime - previous.timestamp()) >= thresholdMs;
    }

    return false;
  }

  /**
   * Returns the number of recorded points.
   */
  public int size() {
    return points.size();
  }

  /**
   * Returns the latest recorded offset, or empty if no points exist.
   */
  public OptionalLong getLatestOffset() {
    if (points.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(points.getLast().offset());
  }
}
