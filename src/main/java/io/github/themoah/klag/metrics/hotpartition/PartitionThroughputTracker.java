package io.github.themoah.klag.metrics.hotpartition;

import io.github.themoah.klag.model.PartitionThroughputSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks throughput history for all partitions across all topics.
 * Used to calculate the rate of log_end_offset growth for hot partition detection.
 */
public class PartitionThroughputTracker {

  private static final Logger log = LoggerFactory.getLogger(PartitionThroughputTracker.class);

  private final Map<String, PartitionThroughputHistory> histories = new ConcurrentHashMap<>();
  private final int bufferSize;
  private final int minSamples;

  public PartitionThroughputTracker(int bufferSize, int minSamples) {
    this.bufferSize = bufferSize;
    this.minSamples = minSamples;
  }

  /**
   * Records a throughput snapshot for a partition.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @param logEndOffset the current log end offset
   */
  public void recordSnapshot(String topic, int partition, long logEndOffset) {
    String key = makeKey(topic, partition);
    PartitionThroughputSnapshot snapshot = new PartitionThroughputSnapshot(
      System.currentTimeMillis(),
      logEndOffset
    );

    histories.computeIfAbsent(key, k ->
      new PartitionThroughputHistory(topic, partition, bufferSize, minSamples)
    ).addSnapshot(snapshot);

    log.trace("Recorded throughput snapshot for {}:{} - offset={}", topic, partition, logEndOffset);
  }

  /**
   * Calculates current throughput rates for all partitions that have enough data.
   *
   * @return map of "topic:partition" key to throughput (messages/second)
   */
  public Map<String, Double> calculateAllThroughputs() {
    Map<String, Double> result = new HashMap<>();

    for (Map.Entry<String, PartitionThroughputHistory> entry : histories.entrySet()) {
      Double throughput = entry.getValue().calculateThroughput();
      if (throughput != null && throughput >= 0) {
        result.put(entry.getKey(), throughput);
      }
    }

    return result;
  }

  /**
   * Gets all throughput rates for a specific topic.
   *
   * @param topic the topic name
   * @return map of partition number to throughput (only partitions with sufficient data)
   */
  public Map<Integer, Double> getThroughputsForTopic(String topic) {
    Map<Integer, Double> result = new HashMap<>();

    for (Map.Entry<String, PartitionThroughputHistory> entry : histories.entrySet()) {
      PartitionThroughputHistory history = entry.getValue();
      if (history.topic().equals(topic)) {
        Double throughput = history.calculateThroughput();
        if (throughput != null && throughput >= 0) {
          result.put(history.partition(), throughput);
        }
      }
    }

    return result;
  }

  /**
   * Removes stale partition histories that are no longer being tracked.
   *
   * @param activeKeys set of "topic:partition" keys that are currently active
   */
  public void cleanupStalePartitions(Set<String> activeKeys) {
    int sizeBefore = histories.size();
    histories.keySet().retainAll(activeKeys);
    int removed = sizeBefore - histories.size();
    if (removed > 0) {
      log.debug("Cleaned up {} stale partition throughput histories", removed);
    }
  }

  /**
   * Creates a key for the partition history map.
   *
   * @param topic the topic name
   * @param partition the partition number
   * @return the key in format "topic:partition"
   */
  public static String makeKey(String topic, int partition) {
    return topic + ":" + partition;
  }
}
