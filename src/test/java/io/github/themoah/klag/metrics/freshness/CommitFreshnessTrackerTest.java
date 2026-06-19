package io.github.themoah.klag.metrics.freshness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommitFreshnessTrackerTest {

  @Test
  void noBaselineUntilFirstRecord() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    assertTrue(t.stalenessSeconds("g", "orders", 1000).isEmpty());
  }

  @Test
  void freshAfterFirstObservation() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    t.record("g", "orders", 100, 1000);
    assertEquals(0, t.stalenessSeconds("g", "orders", 1000).getAsLong());
  }

  @Test
  void stalenessGrowsWhileOffsetFrozen() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    t.record("g", "orders", 100, 0);
    t.record("g", "orders", 100, 60_000);   // same offset 60s later
    assertEquals(60, t.stalenessSeconds("g", "orders", 60_000).getAsLong());
  }

  @Test
  void advanceResetsStaleness() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    t.record("g", "orders", 100, 0);
    t.record("g", "orders", 150, 60_000);   // offset advanced
    assertEquals(0, t.stalenessSeconds("g", "orders", 60_000).getAsLong());
  }

  @Test
  void rewindCountsAsActivity() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    t.record("g", "orders", 100, 0);
    t.record("g", "orders", 50, 60_000);    // offset reset/rewound — still a commit
    assertEquals(0, t.stalenessSeconds("g", "orders", 60_000).getAsLong());
  }

  @Test
  void cleanupDropsAbsentKeys() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    t.record("g", "orders", 100, 0);
    t.cleanupStale(Set.of(LagVelocityTracker.makeKey("g", "payments")));
    assertTrue(t.stalenessSeconds("g", "orders", 0).isEmpty());
  }

  @Test
  void cleanupKeepsActiveKeys() {
    CommitFreshnessTracker t = new CommitFreshnessTracker();
    t.record("g", "orders", 100, 0);
    t.cleanupStale(Set.of(LagVelocityTracker.makeKey("g", "orders")));
    assertFalse(t.stalenessSeconds("g", "orders", 0).isEmpty());
  }
}
