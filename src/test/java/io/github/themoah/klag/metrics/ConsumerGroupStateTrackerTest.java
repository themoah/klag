package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.StateTransition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ConsumerGroupStateTracker.
 */
public class ConsumerGroupStateTrackerTest {

  @Test
  void firstObservation_returnsZero() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    long result = tracker.recordState("group1", ConsumerGroupState.State.STABLE);

    assertEquals(0, result);
  }

  @Test
  void sameState_returnsZero() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    tracker.recordState("group1", ConsumerGroupState.State.STABLE);
    long result = tracker.recordState("group1", ConsumerGroupState.State.STABLE);

    assertEquals(0, result);
  }

  @Test
  void stateChange_returnsOne() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    tracker.recordState("group1", ConsumerGroupState.State.STABLE);
    long result = tracker.recordState("group1", ConsumerGroupState.State.PREPARING_REBALANCE);

    assertEquals(1, result);
  }

  @Test
  void multipleStateChanges_incrementsFromPreviousValue() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    // First observation
    assertEquals(0, tracker.recordState("group1", ConsumerGroupState.State.STABLE));

    // First change: STABLE -> PREPARING_REBALANCE (prev 0 + 1 = 1)
    assertEquals(1, tracker.recordState("group1", ConsumerGroupState.State.PREPARING_REBALANCE));

    // Second change: PREPARING_REBALANCE -> COMPLETING_REBALANCE (prev 1 + 1 = 2)
    assertEquals(2, tracker.recordState("group1", ConsumerGroupState.State.COMPLETING_REBALANCE));

    // No change - resets to 0
    assertEquals(0, tracker.recordState("group1", ConsumerGroupState.State.COMPLETING_REBALANCE));

    // Change after 0: COMPLETING_REBALANCE -> STABLE (prev 0 + 1 = 1)
    assertEquals(1, tracker.recordState("group1", ConsumerGroupState.State.STABLE));

    // No change
    assertEquals(0, tracker.recordState("group1", ConsumerGroupState.State.STABLE));
  }

  @Test
  void exampleSequence_matchesExpectedBehavior() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    // From the requirements:
    // group X was STABLE - reported value 0
    assertEquals(0, tracker.recordState("groupX", ConsumerGroupState.State.STABLE));

    // on next check it's also stable - reported value 0
    assertEquals(0, tracker.recordState("groupX", ConsumerGroupState.State.STABLE));

    // next check it's PREPARING_REBALANCE => 1 (prev 0 + 1)
    assertEquals(1, tracker.recordState("groupX", ConsumerGroupState.State.PREPARING_REBALANCE));

    // next check it's COMPLETING_REBALANCE => 2 (prev 1 + 1)
    assertEquals(2, tracker.recordState("groupX", ConsumerGroupState.State.COMPLETING_REBALANCE));

    // next check it's COMPLETING_REBALANCE => 0 (no change)
    assertEquals(0, tracker.recordState("groupX", ConsumerGroupState.State.COMPLETING_REBALANCE));

    // next check it's STABLE => 1 (prev 0 + 1, starts fresh after 0)
    assertEquals(1, tracker.recordState("groupX", ConsumerGroupState.State.STABLE));

    // next check it's STABLE => 0
    assertEquals(0, tracker.recordState("groupX", ConsumerGroupState.State.STABLE));
  }

  @Test
  void multipleGroups_trackedIndependently() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    // Group 1 starts STABLE
    assertEquals(0, tracker.recordState("group1", ConsumerGroupState.State.STABLE));

    // Group 2 starts PREPARING_REBALANCE
    assertEquals(0, tracker.recordState("group2", ConsumerGroupState.State.PREPARING_REBALANCE));

    // Group 1 changes to PREPARING_REBALANCE (prev 0 + 1 = 1)
    assertEquals(1, tracker.recordState("group1", ConsumerGroupState.State.PREPARING_REBALANCE));

    // Group 2 stays PREPARING_REBALANCE (no change = 0)
    assertEquals(0, tracker.recordState("group2", ConsumerGroupState.State.PREPARING_REBALANCE));

    // Group 2 changes to STABLE (prev 0 + 1 = 1)
    assertEquals(1, tracker.recordState("group2", ConsumerGroupState.State.STABLE));

    // Group 1 changes again (prev 1 + 1 = 2, no 0 in between)
    assertEquals(2, tracker.recordState("group1", ConsumerGroupState.State.STABLE));
  }

  @Test
  void cleanup_removesStaleGroups() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    // Track multiple groups
    tracker.recordState("group1", ConsumerGroupState.State.STABLE);
    tracker.recordState("group2", ConsumerGroupState.State.STABLE);
    tracker.recordState("group3", ConsumerGroupState.State.STABLE);

    assertEquals(3, tracker.trackedGroupCount());

    // Cleanup, keeping only group1 and group2
    tracker.cleanup(Set.of("group1", "group2"));

    assertEquals(2, tracker.trackedGroupCount());

    // Group3 should be treated as new (first observation)
    assertEquals(0, tracker.recordState("group3", ConsumerGroupState.State.PREPARING_REBALANCE));
  }

  @Test
  void cleanup_withEmptySet_removesAllGroups() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    tracker.recordState("group1", ConsumerGroupState.State.STABLE);
    tracker.recordState("group2", ConsumerGroupState.State.STABLE);

    tracker.cleanup(Set.of());

    assertEquals(0, tracker.trackedGroupCount());
  }

  @Test
  void recentTransitions_emptyForUnknownGroup() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();
    assertTrue(tracker.recentTransitions("nope").isEmpty());
  }

  @Test
  void recentTransitions_emptyOnFirstAndUnchangedObservations() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();
    tracker.recordState("g", State.STABLE);
    tracker.recordState("g", State.STABLE);
    assertTrue(tracker.recentTransitions("g").isEmpty());
  }

  @Test
  void recentTransitions_recordsChangesOldestFirst() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();
    tracker.recordState("g", State.STABLE);
    tracker.recordState("g", State.PREPARING_REBALANCE);
    tracker.recordState("g", State.COMPLETING_REBALANCE);

    List<StateTransition> history = tracker.recentTransitions("g");
    assertEquals(2, history.size());
    assertEquals(State.STABLE, history.get(0).from());
    assertEquals(State.PREPARING_REBALANCE, history.get(0).to());
    assertEquals(State.PREPARING_REBALANCE, history.get(1).from());
    assertEquals(State.COMPLETING_REBALANCE, history.get(1).to());
    assertTrue(history.get(1).timestampMs() >= history.get(0).timestampMs());
  }

  @Test
  void recentTransitions_respectsBoundedCapFifo() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();
    // Alternate states to force a change every observation; far more than HISTORY_SIZE.
    State[] cycle = {State.STABLE, State.EMPTY};
    for (int i = 0; i < 30; i++) {
      tracker.recordState("g", cycle[i % 2]);
    }
    List<StateTransition> history = tracker.recentTransitions("g");
    assertEquals(ConsumerGroupStateTracker.HISTORY_SIZE, history.size());
  }

  @Test
  void cleanup_prunesTransitionHistory() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();
    tracker.recordState("g", State.STABLE);
    tracker.recordState("g", State.EMPTY);
    assertEquals(1, tracker.recentTransitions("g").size());

    tracker.cleanup(Set.of());
    assertTrue(tracker.recentTransitions("g").isEmpty());
  }

  @Test
  void allStates_canBeTracked() {
    ConsumerGroupStateTracker tracker = new ConsumerGroupStateTracker();

    // Test all state values can be tracked (consecutive changes: 0, +1, +1, +1, +1, +1)
    assertEquals(0, tracker.recordState("group", ConsumerGroupState.State.UNKNOWN));
    assertEquals(1, tracker.recordState("group", ConsumerGroupState.State.PREPARING_REBALANCE));
    assertEquals(2, tracker.recordState("group", ConsumerGroupState.State.COMPLETING_REBALANCE));
    assertEquals(3, tracker.recordState("group", ConsumerGroupState.State.STABLE));
    assertEquals(4, tracker.recordState("group", ConsumerGroupState.State.DEAD));
    assertEquals(5, tracker.recordState("group", ConsumerGroupState.State.EMPTY));
  }
}
