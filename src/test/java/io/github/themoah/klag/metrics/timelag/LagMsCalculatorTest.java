package io.github.themoah.klag.metrics.timelag;

import static org.junit.jupiter.api.Assertions.*;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

public class LagMsCalculatorTest {

  private static PartitionLag partition(
    long logStartOffset, long logStartTs,
    long logEndOffset, long logEndTs,
    long committedOffset) {
    return PartitionLag.of("topic", 0, logEndOffset, logStartOffset, logEndTs, logStartTs, committedOffset);
  }

  @Test
  void estimatePartitionLagMs_usesKafkaPath_beyondThirtyMinutes() {
    long twoHoursMs = 2 * 60 * 60 * 1000L;
    long logStartTs = 1_000_000L;
    long logEndTs = logStartTs + twoHoursMs;
    long currentTime = logEndTs + 30_000L;
    PartitionLag p = partition(0, logStartTs, 10_000, logEndTs, 100);

    OptionalLong lagMs = LagMsCalculator.estimatePartitionLagMs(p, null, currentTime);

    assertTrue(lagMs.isPresent());
    assertTrue(lagMs.getAsLong() > 1_800_000L);
  }

  @Test
  void estimatePartitionLagMs_omitsWhenFallbackCommittedBeforeOldestSample() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(60, 180_000);
    long now = 10_000_000L;
    tracker.recordOffset("topic", 0, 9000, now - 30_000);
    tracker.recordOffset("topic", 0, 9500, now - 15_000);

    PartitionLag p = partition(0, 0, 10_000, 0, 100);

    OptionalLong lagMs = LagMsCalculator.estimatePartitionLagMs(p, tracker, now);

    assertTrue(lagMs.isEmpty(), "Invalid Kafka timestamps and committed before poll history");
  }

  @Test
  void estimatePartitionLagMs_usesFallbackWhenKafkaTimestampsMissing() {
    OffsetTimestampTracker tracker = new OffsetTimestampTracker(60, 180_000);
    long t0 = 1_000_000L;
    tracker.recordOffset("topic", 0, 1000, t0);
    tracker.recordOffset("topic", 0, 2000, t0 + 60_000);

    PartitionLag p = partition(0, 0, 2000, 0, 1500);
    long currentTime = t0 + 90_000;

    OptionalLong lagMs = LagMsCalculator.estimatePartitionLagMs(p, tracker, currentTime);

    assertTrue(lagMs.isPresent());
    assertEquals(60_000L, lagMs.getAsLong());
  }

  @Test
  void estimatePartitionLagMs_returnsZeroWhenCaughtUp() {
    PartitionLag p = partition(0, 1_000_000L, 10_000, 8_000_000L, 10_000);

    OptionalLong lagMs = LagMsCalculator.estimatePartitionLagMs(p, null, 8_100_000L);

    assertTrue(lagMs.isPresent());
    assertEquals(0, lagMs.getAsLong());
  }
}
