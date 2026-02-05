package io.github.themoah.klag.metrics.timelag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PartitionOffsetHistory interpolation logic.
 */
public class PartitionOffsetHistoryTest {

  @Test
  void hasEnoughData_returnsTrue_afterTwoPoints() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    assertFalse(history.hasEnoughData(), "Should not have enough data with 0 points");

    history.addPoint(100, 1000);
    assertFalse(history.hasEnoughData(), "Should not have enough data with 1 point");

    history.addPoint(200, 2000);
    assertTrue(history.hasEnoughData(), "Should have enough data with 2 points");
  }

  @Test
  void interpolateTimestamp_returnsEmpty_withInsufficientData() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);

    OptionalLong result = history.interpolateTimestamp(150);
    assertTrue(result.isEmpty(), "Should return empty when insufficient data");
  }

  @Test
  void interpolateTimestamp_returnsCurrentTime_whenCaughtUp() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);
    history.addPoint(200, 2000);

    long before = System.currentTimeMillis();
    OptionalLong result = history.interpolateTimestamp(200);
    long after = System.currentTimeMillis();

    assertTrue(result.isPresent());
    assertTrue(result.getAsLong() >= before && result.getAsLong() <= after,
      "Caught up consumer should get current time");
  }

  @Test
  void interpolateTimestamp_returnsCurrentTime_whenAheadOfLatest() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);
    history.addPoint(200, 2000);

    long before = System.currentTimeMillis();
    OptionalLong result = history.interpolateTimestamp(250);
    long after = System.currentTimeMillis();

    assertTrue(result.isPresent());
    assertTrue(result.getAsLong() >= before && result.getAsLong() <= after,
      "Ahead of latest should get current time");
  }

  @Test
  void interpolateTimestamp_interpolatesCorrectly_betweenTwoPoints() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    // Record: offset 100 at time 1000, offset 200 at time 2000
    history.addPoint(100, 1000);
    history.addPoint(200, 2000);

    // Target offset 150 should interpolate to time 1500
    OptionalLong result = history.interpolateTimestamp(150);

    assertTrue(result.isPresent());
    assertEquals(1500, result.getAsLong(), "Should interpolate to midpoint timestamp");
  }

  @Test
  void interpolateTimestamp_interpolatesCorrectly_atQuarterPoint() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    // Record: offset 0 at time 0, offset 100 at time 1000
    history.addPoint(0, 0);
    history.addPoint(100, 1000);

    // Target offset 25 should interpolate to time 250
    OptionalLong result = history.interpolateTimestamp(25);

    assertTrue(result.isPresent());
    assertEquals(250, result.getAsLong());
  }

  @Test
  void interpolateTimestamp_interpolatesCorrectly_withThreePoints() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    // Record: offset 0 at time 0, offset 100 at time 1000, offset 300 at time 3000
    history.addPoint(0, 0);
    history.addPoint(100, 1000);
    history.addPoint(300, 3000);

    // Target offset 50 should interpolate between first two points: time 500
    OptionalLong result1 = history.interpolateTimestamp(50);
    assertTrue(result1.isPresent());
    assertEquals(500, result1.getAsLong());

    // Target offset 200 should interpolate between second and third points: time 2000
    OptionalLong result2 = history.interpolateTimestamp(200);
    assertTrue(result2.isPresent());
    assertEquals(2000, result2.getAsLong());
  }

  @Test
  void interpolateTimestamp_extrapolatesBackward_whenOffsetOlderThanHistory() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    // Record: offset 100 at time 1000, offset 200 at time 2000
    history.addPoint(100, 1000);
    history.addPoint(200, 2000);

    // Target offset 50 should extrapolate backward: time 500
    // Using slope from first two points: (2000-1000)/(200-100) = 10 ms/offset
    // t = 1000 + (50-100) * 10 = 1000 - 500 = 500
    OptionalLong result = history.interpolateTimestamp(50);

    assertTrue(result.isPresent());
    assertEquals(500, result.getAsLong());
  }

  @Test
  void interpolateTimestamp_extrapolatesBackward_toNegativeTimestamp() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    // Record: offset 100 at time 1000, offset 200 at time 2000
    history.addPoint(100, 1000);
    history.addPoint(200, 2000);

    // Target offset 0 should extrapolate to: 1000 + (0-100) * 10 = 0
    OptionalLong result = history.interpolateTimestamp(0);

    assertTrue(result.isPresent());
    assertEquals(0, result.getAsLong());
  }

  @Test
  void bufferSize_evictsOldestPoints() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 3);

    history.addPoint(100, 1000);
    history.addPoint(200, 2000);
    history.addPoint(300, 3000);
    assertEquals(3, history.size());

    // Adding 4th point should evict the oldest
    history.addPoint(400, 4000);
    assertEquals(3, history.size());

    // Now the oldest point is (200, 2000)
    // Extrapolating to offset 100 should use points (200,2000) and (300,3000)
    // slope = (3000-2000)/(300-200) = 10
    // t = 2000 + (100-200) * 10 = 2000 - 1000 = 1000
    OptionalLong result = history.interpolateTimestamp(100);
    assertTrue(result.isPresent());
    assertEquals(1000, result.getAsLong());
  }

  @Test
  void isProducerStale_returnsFalse_withInsufficientData() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);

    assertFalse(history.isProducerStale(5000, 3000));
  }

  @Test
  void isProducerStale_returnsFalse_whenOffsetsProgress() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);
    history.addPoint(200, 2000);

    assertFalse(history.isProducerStale(5000, 3000));
  }

  @Test
  void isProducerStale_returnsTrue_whenNoProgressForThreshold() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);
    history.addPoint(100, 2000);  // Same offset

    // 5000ms current time, 2000ms when no progress started, threshold 3000ms
    // Duration without progress = 5000 - 1000 = 4000ms > 3000ms threshold
    assertTrue(history.isProducerStale(5000, 3000));
  }

  @Test
  void isProducerStale_returnsFalse_whenNoProgressBelowThreshold() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);
    history.addPoint(100, 2000);  // Same offset

    // Duration without progress = 3000 - 1000 = 2000ms < 3000ms threshold
    assertFalse(history.isProducerStale(3000, 3000));
  }

  @Test
  void getLatestOffset_returnsEmpty_whenNoPoints() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    assertTrue(history.getLatestOffset().isEmpty());
  }

  @Test
  void getLatestOffset_returnsLatestOffset() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    history.addPoint(100, 1000);
    assertEquals(100, history.getLatestOffset().orElse(-1));

    history.addPoint(200, 2000);
    assertEquals(200, history.getLatestOffset().orElse(-1));
  }

  @Test
  void interpolateTimestamp_handlesZeroOffsetRange() {
    PartitionOffsetHistory history = new PartitionOffsetHistory("topic", 0, 10);

    // Two points with same offset (should not happen normally, but handle gracefully)
    history.addPoint(100, 1000);
    history.addPoint(100, 2000);

    // When target offset equals latest offset, consumer is caught up -> returns current time
    long before = System.currentTimeMillis();
    OptionalLong result = history.interpolateTimestamp(100);
    long after = System.currentTimeMillis();

    assertTrue(result.isPresent());
    assertTrue(result.getAsLong() >= before && result.getAsLong() <= after,
      "Caught up consumer should get current time");
  }
}
