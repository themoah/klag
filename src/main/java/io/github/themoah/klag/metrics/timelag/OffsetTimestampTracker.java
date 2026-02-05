package io.github.themoah.klag.metrics.timelag;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages offset/timestamp histories for all partitions.
 * Used to interpolate timestamps for committed offsets to calculate lag in milliseconds.
 *
 * <p>Follows the pattern of {@link io.github.themoah.klag.metrics.velocity.LagVelocityTracker}
 * for thread-safe partition tracking with cleanup support.
 */
public class OffsetTimestampTracker {

  private static final Logger log = LoggerFactory.getLogger(OffsetTimestampTracker.class);

  private final Map<String, PartitionOffsetHistory> histories = new ConcurrentHashMap<>();
  private final int bufferSize;
  private final long staleThresholdMs;

  /**
   * Creates a new tracker with the specified buffer size and stale threshold.
   *
   * @param bufferSize number of offset/timestamp points to retain per partition
   * @param staleThresholdMs time in ms before a partition with no progress is considered stale
   */
  public OffsetTimestampTracker(int bufferSize, long staleThresholdMs) {
    this.bufferSize = bufferSize;
    this.staleThresholdMs = staleThresholdMs;
    log.info("OffsetTimestampTracker initialized with bufferSize={}, staleThresholdMs={}",
      bufferSize, staleThresholdMs);
  }

  /**
   * Records the current log end offset for a partition.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @param logEndOffset the current log end offset
   */
  public void recordOffset(String topic, int partition, long logEndOffset) {
    String key = makeKey(topic, partition);
    histories.computeIfAbsent(key, k -> new PartitionOffsetHistory(topic, partition, bufferSize))
      .addPoint(logEndOffset);
    log.trace("Recorded offset for {}:{}: {}", topic, partition, logEndOffset);
  }

  /**
   * Records an offset with a specific timestamp (used for testing).
   */
  void recordOffset(String topic, int partition, long logEndOffset, long timestamp) {
    String key = makeKey(topic, partition);
    histories.computeIfAbsent(key, k -> new PartitionOffsetHistory(topic, partition, bufferSize))
      .addPoint(logEndOffset, timestamp);
  }

  /**
   * Gets the interpolated timestamp for a committed offset on a partition.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @param committedOffset the offset to interpolate timestamp for
   * @return the estimated timestamp, or empty if insufficient data
   */
  public OptionalLong getInterpolatedTimestamp(String topic, int partition, long committedOffset) {
    String key = makeKey(topic, partition);
    PartitionOffsetHistory history = histories.get(key);
    if (history == null) {
      return OptionalLong.empty();
    }
    return history.interpolateTimestamp(committedOffset);
  }

  /**
   * Checks if the producer for a partition appears stale (no offset progress).
   *
   * @param topic the topic name
   * @param partition the partition number
   * @return true if producer is stale
   */
  public boolean isProducerStale(String topic, int partition) {
    String key = makeKey(topic, partition);
    PartitionOffsetHistory history = histories.get(key);
    if (history == null) {
      return false;
    }
    return history.isProducerStale(System.currentTimeMillis(), staleThresholdMs);
  }

  /**
   * Checks if we have enough data for interpolation on a partition.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @return true if interpolation is available
   */
  public boolean hasInterpolationData(String topic, int partition) {
    String key = makeKey(topic, partition);
    PartitionOffsetHistory history = histories.get(key);
    return history != null && history.hasEnoughData();
  }

  /**
   * Cleans up partition histories that are no longer being tracked.
   * Follows two-phase deletion pattern similar to MicrometerReporter.
   *
   * @param activeKeys set of currently active "topic:partition" keys
   */
  public void cleanupStalePartitions(Set<String> activeKeys) {
    int sizeBefore = histories.size();
    histories.keySet().retainAll(activeKeys);
    int removed = sizeBefore - histories.size();
    if (removed > 0) {
      log.debug("Cleaned up {} stale partition histories", removed);
    }
  }

  /**
   * Returns the latest recorded offset for a partition, if available.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @return the latest offset, or empty if no history exists
   */
  public OptionalLong getLatestOffset(String topic, int partition) {
    String key = makeKey(topic, partition);
    PartitionOffsetHistory history = histories.get(key);
    if (history == null) {
      return OptionalLong.empty();
    }
    return history.getLatestOffset();
  }

  /**
   * Returns the number of tracked partitions.
   */
  public int getTrackedPartitionCount() {
    return histories.size();
  }

  private String makeKey(String topic, int partition) {
    return topic + ":" + partition;
  }
}
