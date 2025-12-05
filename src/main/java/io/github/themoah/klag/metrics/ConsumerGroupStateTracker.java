package io.github.themoah.klag.metrics;

import io.github.themoah.klag.model.ConsumerGroupState;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks consumer group state changes and reports change values.
 *
 * <p>When state changes: adds +1 to the previous reported value.
 * When state remains unchanged: returns 0.
 *
 * <p>Example sequence for group X:
 * <pre>
 * STABLE → 0 (first observation)
 * STABLE → 0 (no change)
 * PREPARING_REBALANCE → 1 (prev 0 + 1)
 * COMPLETING_REBALANCE → 2 (prev 1 + 1)
 * COMPLETING_REBALANCE → 0 (no change)
 * STABLE → 1 (prev 0 + 1)
 * STABLE → 0 (no change)
 * </pre>
 */
public class ConsumerGroupStateTracker {

  private final Map<String, TrackedState> previousStates = new ConcurrentHashMap<>();

  /**
   * Internal record to track state and last reported value.
   */
  record TrackedState(ConsumerGroupState.State state, long lastReportedValue) {}

  /**
   * Records the current state for a consumer group and returns the metric value.
   *
   * @param groupId the consumer group ID
   * @param currentState the current state of the consumer group
   * @return 0 if state unchanged or first observation, otherwise previous value + 1
   */
  public long recordState(String groupId, ConsumerGroupState.State currentState) {
    TrackedState prev = previousStates.get(groupId);

    if (prev == null) {
      // First observation - report 0
      previousStates.put(groupId, new TrackedState(currentState, 0));
      return 0;
    }

    if (prev.state() == currentState) {
      // No change - report 0 and update lastReportedValue to 0
      previousStates.put(groupId, new TrackedState(currentState, 0));
      return 0;
    }

    // State changed - add +1 to previous reported value
    long newValue = prev.lastReportedValue() + 1;
    previousStates.put(groupId, new TrackedState(currentState, newValue));
    return newValue;
  }

  /**
   * Removes tracking data for consumer groups that are no longer active.
   *
   * @param activeGroupIds set of currently active consumer group IDs
   */
  public void cleanup(Set<String> activeGroupIds) {
    previousStates.keySet().retainAll(activeGroupIds);
  }

  /**
   * Returns the number of tracked consumer groups (for testing).
   */
  int trackedGroupCount() {
    return previousStates.size();
  }
}
