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

  // Variant where the true end offset (LATEST) differs from the max-timestamp offset.
  private static PartitionLag partition(
    long logStartOffset, long logStartTs,
    long logEndOffset, long logEndTs, long maxTimestampOffset,
    long committedOffset) {
    return PartitionLag.of("topic", 0, logEndOffset, logStartOffset, logEndTs,
      maxTimestampOffset, logStartTs, committedOffset);
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

  // Regression: LATEST (true end) > MAX_TIMESTAMP offset. Committed sits between the
  // max-timestamp record and the true end; interpolation must NOT be skipped, and the
  // timestamp anchor must come from maxTimestampOffset/logEndTimestamp (not logEndOffset).
  @Test
  void hasValidAnchors_returnsTrue_whenLatestExceedsMaxTimestampOffset() {
    // logStart=0, maxTimestampOffset=1000, true logEnd=1200, committed=1100 (past max-ts record)
    PartitionLag p = partition(0, 1_000L, 1200, 11_000L, 1000, 1100);
    assertTrue(KafkaOffsetTimestampInterpolator.hasValidAnchors(p));
  }

  @Test
  void interpolate_clampsToLogEndTimestamp_whenCommittedPastMaxTimestampOffset() {
    PartitionLag p = partition(0, 1_000L, 1200, 11_000L, 1000, 1100);

    OptionalLong ts = KafkaOffsetTimestampInterpolator.interpolateCommittedTimestamp(p);

    assertTrue(ts.isPresent());
    assertEquals(11_000L, ts.getAsLong(),
      "committed beyond max-timestamp record should clamp to logEndTimestamp");
  }

  @Test
  void interpolate_usesMaxTimestampOffsetSlope_notLogEndOffset() {
    // Span: offset 0..1000 maps to ts 1000..11000 (slope 10ms/offset). True end is 5000.
    // committed=500 → 1000 + 500*10 = 6000. If the (wrong) logEndOffset=5000 were used as
    // the anchor, slope would be 2ms/offset → 2000, a very different result.
    PartitionLag p = partition(0, 1_000L, 5000, 11_000L, 1000, 500);

    OptionalLong ts = KafkaOffsetTimestampInterpolator.interpolateCommittedTimestamp(p);

    assertTrue(ts.isPresent());
    assertEquals(6_000L, ts.getAsLong());
  }

  @Test
  void estimateLagMs_notSkipped_whenLatestExceedsMaxTimestampOffset() {
    long logStartTs = 1_000_000L;
    long logEndTs = logStartTs + 2 * 60 * 60 * 1000L; // 2h span
    long currentTime = logEndTs + 60_000L;
    // committed near log start → ~2h old; true end (10_000) well past max-ts offset (8_000)
    PartitionLag p = partition(0, logStartTs, 10_000, logEndTs, 8_000, 100);

    OptionalLong lagMs = KafkaOffsetTimestampInterpolator.estimateLagMs(p, currentTime);

    assertTrue(lagMs.isPresent(), "must not silently skip when LATEST != MAX_TIMESTAMP offset");
    assertTrue(lagMs.getAsLong() > 1_800_000L);
  }
}
