package io.github.themoah.klag.metrics.freshness;

import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks how long ago each consumer-group/topic last advanced its committed offset.
 *
 * <p>Kafka exposes no commit timestamp, so freshness is inferred by observing the committed offset
 * each collection cycle and remembering when it last changed. Per key we keep two longs: the last
 * observed committed-offset sum (across the topic's partitions) and the wall-clock time it last
 * moved. Any change — including a rewind/reset — counts as activity. The clock starts at klag
 * startup and resets on restart; staleness is measured from the first observed cycle, not the actual
 * commit time.
 */
public class CommitFreshnessTracker {
  private static final Logger log = LoggerFactory.getLogger(CommitFreshnessTracker.class);

  private record CommitState(long lastCommittedSum, long lastAdvanceMillis) {}

  private final Map<String, CommitState> states = new ConcurrentHashMap<>();

  /**
   * Records the current committed-offset sum for a group/topic. Updates the last-advance timestamp
   * only when the sum changes (first observation establishes the baseline).
   */
  public void record(String consumerGroup, String topic, long committedSum, long nowMillis) {
    String key = LagVelocityTracker.makeKey(consumerGroup, topic);
    states.compute(key, (k, prev) -> {
      if (prev == null || prev.lastCommittedSum() != committedSum) {
        return new CommitState(committedSum, nowMillis);
      }
      return prev;
    });
  }

  /**
   * Seconds since the committed offset last advanced for this group/topic, or empty if no baseline
   * has been recorded yet.
   */
  public OptionalLong stalenessSeconds(String consumerGroup, String topic, long nowMillis) {
    CommitState state = states.get(LagVelocityTracker.makeKey(consumerGroup, topic));
    if (state == null) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Math.max(0, (nowMillis - state.lastAdvanceMillis()) / 1000));
  }

  /**
   * Removes tracked keys no longer present, matching the two-phase cleanup of the other trackers.
   */
  public void cleanupStale(Set<String> activeKeys) {
    int before = states.size();
    states.keySet().retainAll(activeKeys);
    int removed = before - states.size();
    if (removed > 0) {
      log.debug("Cleaned up {} stale commit-freshness entries", removed);
    }
  }
}
