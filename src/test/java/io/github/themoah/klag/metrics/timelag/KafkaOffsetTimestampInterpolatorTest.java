package io.github.themoah.klag.metrics.timelag;

import static org.junit.jupiter.api.Assertions.*;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

public class KafkaOffsetTimestampInterpolatorTest {

  private static PartitionLag partition(
    long logStartOffset, long logStartTs,
    long logEndOffset, long logEndTs,
    long committedOffset) {
    return PartitionLag.of("topic", 0, logEndOffset, logStartOffset, logEndTs, logStartTs, committedOffset);
  }

  @Test
  void hasValidAnchors_returnsTrue_forNormalPartition() {
    assertTrue(KafkaOffsetTimestampInterpolator.hasValidAnchors(
      partition(0, 1_000_000L, 10_000, 8_200_000L, 2_000)));
  }

  @Test
  void hasValidAnchors_returnsFalse_whenTimestampsInvalid() {
    assertFalse(KafkaOffsetTimestampInterpolator.hasValidAnchors(
      partition(0, 0, 10_000, 8_200_000L, 2_000)));
    assertFalse(KafkaOffsetTimestampInterpolator.hasValidAnchors(
      partition(0, 1_000_000L, 10_000, -1, 2_000)));
  }

  @Test
  void hasValidAnchors_returnsFalse_whenOffsetSpanDegenerate() {
    assertFalse(KafkaOffsetTimestampInterpolator.hasValidAnchors(
      partition(100, 1_000_000L, 100, 1_000_000L, 100)));
  }

  @Test
  void hasValidAnchors_returnsFalse_whenCommittedBeforeLogStart() {
    assertFalse(KafkaOffsetTimestampInterpolator.hasValidAnchors(
      partition(100, 1_000_000L, 10_000, 8_200_000L, 50)));
  }

  @Test
  void hasValidAnchors_returnsFalse_whenCommittedAfterLogEnd() {
    assertFalse(KafkaOffsetTimestampInterpolator.hasValidAnchors(
      partition(0, 1_000_000L, 10_000, 8_200_000L, 10_001)));
  }

  @Test
  void estimateLagMs_reportsBeyondThirtyMinutes() {
    long twoHoursMs = 2 * 60 * 60 * 1000L;
    long logStartTs = 1_000_000L;
    long logEndTs = logStartTs + twoHoursMs;
    long currentTime = logEndTs + 60_000L;
    // committed near log start → message ~2h old
    PartitionLag p = partition(0, logStartTs, 10_000, logEndTs, 100);

    OptionalLong lagMs = KafkaOffsetTimestampInterpolator.estimateLagMs(p, currentTime);

    assertTrue(lagMs.isPresent());
    assertTrue(lagMs.getAsLong() > 1_800_000L,
      "lag_ms should exceed 30 minutes when Kafka timestamps span 2 hours");
  }

  @Test
  void interpolateCommittedTimestamp_interpolatesMidpoint() {
    PartitionLag p = partition(0, 1_000L, 1000, 11_000L, 500);

    OptionalLong ts = KafkaOffsetTimestampInterpolator.interpolateCommittedTimestamp(p);

    assertTrue(ts.isPresent());
    assertEquals(6_000L, ts.getAsLong());
  }

  @Test
  void estimateLagMs_returnsZero_whenCaughtUpAtLogEnd() {
    long logEndTs = 8_000_000L;
    long currentTime = logEndTs + 5_000L;
    PartitionLag p = partition(0, 1_000_000L, 10_000, logEndTs, 10_000);

    OptionalLong lagMs = KafkaOffsetTimestampInterpolator.estimateLagMs(p, currentTime);

    assertTrue(lagMs.isPresent());
    assertEquals(5_000L, lagMs.getAsLong());
  }

  @Test
  void estimateLagMs_returnsEmpty_whenAnchorsInvalid() {
    PartitionLag p = partition(0, 0, 10_000, 8_200_000L, 2_000);

    assertTrue(KafkaOffsetTimestampInterpolator.estimateLagMs(p, System.currentTimeMillis()).isEmpty());
  }
}
