package io.github.themoah.klag.metrics.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.LagVelocity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for LagVelocityTracker.
 */
public class LagVelocityTrackerTest {

  @Test
  void noSnapshots_noVelocities() {
    LagVelocityTracker tracker = new LagVelocityTracker();

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertTrue(velocities.isEmpty());
  }

  @Test
  void singleSnapshot_noVelocity() {
    LagVelocityTracker tracker = new LagVelocityTracker();

    tracker.recordSnapshot("group1", "topic1", 100, 50, 50);

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertTrue(velocities.isEmpty());
  }

  @Test
  void twoSnapshots_noVelocity() {
    LagVelocityTracker tracker = new LagVelocityTracker();

    // With MIN_SAMPLES=3, two snapshots are insufficient
    tracker.recordSnapshot("group1", "topic1", 100, 50, 50);
    tracker.recordSnapshot("group1", "topic1", 200, 100, 100);

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertTrue(velocities.isEmpty());
  }

  @Test
  void threeSnapshots_calculatesVelocity() throws InterruptedException {
    LagVelocityTracker tracker = new LagVelocityTracker();

    tracker.recordSnapshot("group1", "topic1", 100, 50, 50);
    Thread.sleep(100); // Wait to ensure different timestamps
    tracker.recordSnapshot("group1", "topic1", 200, 100, 100);
    Thread.sleep(100);
    tracker.recordSnapshot("group1", "topic1", 300, 150, 150);

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertEquals(1, velocities.size());
    LagVelocity velocity = velocities.get(0);
    assertEquals("group1", velocity.consumerGroup());
    assertEquals("topic1", velocity.topic());
    assertTrue(velocity.velocity() > 0); // Lag is growing
  }

  @Test
  void multipleConsumerGroups() throws InterruptedException {
    LagVelocityTracker tracker = new LagVelocityTracker();

    // Group 1 - need 3 snapshots
    tracker.recordSnapshot("group1", "topic1", 100, 50, 50);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic1", 150, 80, 70);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic1", 200, 110, 90);

    // Group 2 - need 3 snapshots
    tracker.recordSnapshot("group2", "topic1", 200, 150, 50);
    Thread.sleep(50);
    tracker.recordSnapshot("group2", "topic1", 250, 220, 30);
    Thread.sleep(50);
    tracker.recordSnapshot("group2", "topic1", 300, 280, 20);

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertEquals(2, velocities.size());

    // Verify both groups are present
    assertTrue(velocities.stream().anyMatch(v -> v.consumerGroup().equals("group1")));
    assertTrue(velocities.stream().anyMatch(v -> v.consumerGroup().equals("group2")));
  }

  @Test
  void multipleTopics() throws InterruptedException {
    LagVelocityTracker tracker = new LagVelocityTracker();

    // Topic 1 - need 3 snapshots
    tracker.recordSnapshot("group1", "topic1", 100, 50, 50);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic1", 150, 80, 70);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic1", 200, 110, 90);

    // Topic 2 - need 3 snapshots
    tracker.recordSnapshot("group1", "topic2", 200, 150, 50);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic2", 250, 220, 30);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic2", 300, 280, 20);

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertEquals(2, velocities.size());

    // Verify both topics are present
    assertTrue(velocities.stream().anyMatch(v -> v.topic().equals("topic1")));
    assertTrue(velocities.stream().anyMatch(v -> v.topic().equals("topic2")));
  }

  @Test
  void cleanupStaleTopics() throws InterruptedException {
    LagVelocityTracker tracker = new LagVelocityTracker();

    // Add snapshots for two consumer-group/topic pairs (3 snapshots each)
    tracker.recordSnapshot("group1", "topic1", 100, 50, 50);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic1", 150, 80, 70);
    Thread.sleep(50);
    tracker.recordSnapshot("group1", "topic1", 200, 110, 90);

    tracker.recordSnapshot("group2", "topic2", 200, 150, 50);
    Thread.sleep(50);
    tracker.recordSnapshot("group2", "topic2", 250, 220, 30);
    Thread.sleep(50);
    tracker.recordSnapshot("group2", "topic2", 300, 280, 20);

    // Verify we have 2 velocities before cleanup
    assertEquals(2, tracker.calculateVelocities().size());

    // Cleanup, keeping only group1:topic1
    tracker.cleanupStaleTopics(Set.of("group1:topic1"));

    // After cleanup, only group1:topic1 should remain
    List<LagVelocity> velocities = tracker.calculateVelocities();
    assertEquals(1, velocities.size());
    assertEquals("group1", velocities.get(0).consumerGroup());
    assertEquals("topic1", velocities.get(0).topic());
  }

  @Test
  void separateHistoriesPerConsumerGroupTopicPair() throws InterruptedException {
    LagVelocityTracker tracker = new LagVelocityTracker();

    // Same topic, different groups - need 3 snapshots each
    tracker.recordSnapshot("group1", "events", 1000, 500, 500);
    tracker.recordSnapshot("group2", "events", 1000, 800, 200);

    Thread.sleep(100);

    tracker.recordSnapshot("group1", "events", 1500, 700, 800); // Falling behind
    tracker.recordSnapshot("group2", "events", 1200, 1100, 100); // Catching up

    Thread.sleep(100);

    tracker.recordSnapshot("group1", "events", 2000, 900, 1100); // Still falling behind
    tracker.recordSnapshot("group2", "events", 1400, 1350, 50); // Still catching up

    List<LagVelocity> velocities = tracker.calculateVelocities();

    assertEquals(2, velocities.size());

    LagVelocity group1Velocity = velocities.stream()
      .filter(v -> v.consumerGroup().equals("group1"))
      .findFirst()
      .orElseThrow();

    LagVelocity group2Velocity = velocities.stream()
      .filter(v -> v.consumerGroup().equals("group2"))
      .findFirst()
      .orElseThrow();

    // Group 1 should be falling behind (positive velocity)
    assertTrue(group1Velocity.velocity() > 0);

    // Group 2 should be catching up (negative velocity)
    assertTrue(group2Velocity.velocity() < 0);
  }
}
