package io.github.themoah.klag.metrics.timelag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OffsetTimestampTracker partition management.
 */
public class OffsetTimestampTrackerTest {

  @Test
  void recordOffset_createsHistoryForNewPartition() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    tracker.recordOffset("topic1", 0, 100, 1000);

    assertEquals(1, tracker.getTrackedPartitionCount());
    assertTrue(tracker.getLatestOffset("topic1", 0).isPresent());
    assertEquals(100, tracker.getLatestOffset("topic1", 0).getAsLong());
  }

  @Test
  void recordOffset_tracksMultiplePartitions() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    tracker.recordOffset("topic1", 0, 100, 1000);
    tracker.recordOffset("topic1", 1, 200, 1000);
    tracker.recordOffset("topic2", 0, 300, 1000);

    assertEquals(3, tracker.getTrackedPartitionCount());
  }

  @Test
  void hasInterpolationData_returnsFalse_withInsufficientData() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    assertFalse(tracker.hasInterpolationData("topic1", 0));

    tracker.recordOffset("topic1", 0, 100, 1000);
    assertFalse(tracker.hasInterpolationData("topic1", 0));
  }

  @Test
  void hasInterpolationData_returnsTrue_withTwoPoints() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    tracker.recordOffset("topic1", 0, 100, 1000);
    tracker.recordOffset("topic1", 0, 200, 2000);

    assertTrue(tracker.hasInterpolationData("topic1", 0));
  }

  @Test
  void getInterpolatedTimestamp_returnsEmpty_forUnknownPartition() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    OptionalLong result = tracker.getInterpolatedTimestamp("unknown", 0, 100);
    assertTrue(result.isEmpty());
  }

  @Test
  void getInterpolatedTimestamp_interpolatesCorrectly() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    tracker.recordOffset("topic1", 0, 100, 1000);
    tracker.recordOffset("topic1", 0, 200, 2000);

    OptionalLong result = tracker.getInterpolatedTimestamp("topic1", 0, 150);
    assertTrue(result.isPresent());
    assertEquals(1500, result.getAsLong());
  }

  @Test
  void isProducerStale_returnsFalse_forUnknownPartition() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    assertFalse(tracker.isProducerStale("unknown", 0));
  }

  @Test
  void cleanupStalePartitions_removesUntracked() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    tracker.recordOffset("topic1", 0, 100, 1000);
    tracker.recordOffset("topic1", 1, 200, 1000);
    tracker.recordOffset("topic2", 0, 300, 1000);

    assertEquals(3, tracker.getTrackedPartitionCount());

    // Cleanup, keeping only topic1:0
    tracker.cleanupStalePartitions(Set.of("topic1:0"));

    assertEquals(1, tracker.getTrackedPartitionCount());
    assertTrue(tracker.getLatestOffset("topic1", 0).isPresent());
    assertTrue(tracker.getLatestOffset("topic1", 1).isEmpty());
    assertTrue(tracker.getLatestOffset("topic2", 0).isEmpty());
  }

  @Test
  void cleanupStalePartitions_keepsAllActive() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    tracker.recordOffset("topic1", 0, 100, 1000);
    tracker.recordOffset("topic1", 1, 200, 1000);

    tracker.cleanupStalePartitions(Set.of("topic1:0", "topic1:1"));

    assertEquals(2, tracker.getTrackedPartitionCount());
  }

  @Test
  void multiplePartitions_independentHistories() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    // Partition 0: offset 100->200 in time 1000->2000
    tracker.recordOffset("topic1", 0, 100, 1000);
    tracker.recordOffset("topic1", 0, 200, 2000);

    // Partition 1: offset 500->600 in time 1000->3000 (different rate)
    tracker.recordOffset("topic1", 1, 500, 1000);
    tracker.recordOffset("topic1", 1, 600, 3000);

    // Partition 0: midpoint offset 150 -> time 1500
    OptionalLong result0 = tracker.getInterpolatedTimestamp("topic1", 0, 150);
    assertTrue(result0.isPresent());
    assertEquals(1500, result0.getAsLong());

    // Partition 1: midpoint offset 550 -> time 2000
    OptionalLong result1 = tracker.getInterpolatedTimestamp("topic1", 1, 550);
    assertTrue(result1.isPresent());
    assertEquals(2000, result1.getAsLong());
  }

  @Test
  void getLatestOffset_returnsEmpty_forUnknownPartition() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(10, 180000);

    assertTrue(tracker.getLatestOffset("unknown", 0).isEmpty());
  }
}
