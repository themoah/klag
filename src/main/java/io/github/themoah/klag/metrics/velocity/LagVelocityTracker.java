package io.github.themoah.klag.metrics.velocity;

import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.TopicOffsetSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks lag velocity across all consumer groups and topics.
 *
 * TODO: Consider persisting snapshots to RocksDB for:
 *   - Survival across restarts
 *   - Longer historical windows
 *   - Cold-start scenarios
 * TODO: Consider S3/blob storage for archival analytics
 */
public class LagVelocityTracker {
  private static final Logger log = LoggerFactory.getLogger(LagVelocityTracker.class);
  private static final int BUFFER_SIZE = 20;  // Store 20 samples for ~20-interval window
  private static final int MIN_SAMPLES = 3;   // Need at least 3 points for reliable regression

  private final Map<String, TopicLagHistory> histories = new ConcurrentHashMap<>();

  /**
   * Records a snapshot for a consumer group and topic.
   */
  public void recordSnapshot(
      String consumerGroup,
      String topic,
      long logEndOffset,
      long committedOffset,
      long lag
  ) {
    String key = makeKey(consumerGroup, topic);
    TopicOffsetSnapshot snapshot = new TopicOffsetSnapshot(
      System.currentTimeMillis(),
      logEndOffset,
      committedOffset,
      lag
    );

    histories.computeIfAbsent(key, k ->
      new TopicLagHistory(consumerGroup, topic, BUFFER_SIZE, MIN_SAMPLES)
    ).addSnapshot(snapshot);

    log.trace("Recorded snapshot for {}:{} - lag={}", consumerGroup, topic, lag);
  }

  /**
   * Calculates velocities for all tracked consumer-group/topic pairs.
   */
  public List<LagVelocity> calculateVelocities() {
    List<LagVelocity> velocities = new ArrayList<>();

    for (TopicLagHistory history : histories.values()) {
      LagVelocity velocity = history.calculateVelocity();
      if (velocity != null) {
        velocities.add(velocity);
        if (log.isDebugEnabled()) {
          log.debug("Calculated velocity for {}:{}: {} msg/s ({} samples)",
            velocity.consumerGroup(), velocity.topic(),
            String.format("%.2f", velocity.velocity()), velocity.sampleCount());
        }
      }
    }

    return velocities;
  }

  /**
   * Removes stale topic histories that are no longer being tracked.
   * Follows two-phase deletion pattern similar to MicrometerReporter.
   */
  public void cleanupStaleTopics(Set<String> activeKeys) {
    int before = histories.size();
    histories.keySet().retainAll(activeKeys);

    int removed = before - histories.size();
    if (removed > 0) {
      log.debug("Cleaned up {} stale topic histories", removed);
    }
  }

  /**
   * Builds the history key for a consumer-group/topic pair. NUL is illegal in topic names
   * and the topic comes last, so the key decomposes unambiguously even though group IDs
   * may contain any character (a plain ":" separator collides for group IDs containing ":").
   */
  public static String makeKey(String consumerGroup, String topic) {
    return consumerGroup + '\u0000' + topic;
  }
}
