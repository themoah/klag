package io.github.themoah.klag.metrics.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.TopicOffsetSnapshot;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TopicLagHistory with linear regression.
 */
public class TopicLagHistoryTest {

  @Test
  void insufficientData_noVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Only 2 snapshots (below MIN_SAMPLES=3)
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 50, 50));
    history.addSnapshot(new TopicOffsetSnapshot(2000, 150, 80, 70));

    assertFalse(history.hasEnoughData());
    assertNull(history.calculateVelocity());
  }

  @Test
  void threeSamples_calculatesVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Perfect linear trend: lag growing by 20 msg/s
    // t=0ms: lag=50
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 50, 50));
    // t=1000ms: lag=70 (grew by 20)
    history.addSnapshot(new TopicOffsetSnapshot(2000, 150, 80, 70));
    // t=2000ms: lag=90 (grew by 20)
    history.addSnapshot(new TopicOffsetSnapshot(3000, 200, 110, 90));

    assertTrue(history.hasEnoughData());
    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals("group1", velocity.consumerGroup());
    assertEquals("topic1", velocity.topic());
    assertEquals(20.0, velocity.velocity(), 0.1);  // 20 msg/s linear growth
    assertEquals(2000, velocity.windowDurationMs());
    assertEquals(3, velocity.sampleCount());
  }

  @Test
  void consumerCatchingUp_negativeVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Lag decreasing (consumer catching up)
    // t=0ms: lag=500
    history.addSnapshot(new TopicOffsetSnapshot(1000, 1000, 500, 500));
    // t=1000ms: lag=450 (decreased by 50)
    history.addSnapshot(new TopicOffsetSnapshot(2000, 1100, 650, 450));
    // t=2000ms: lag=400 (decreased by 50)
    history.addSnapshot(new TopicOffsetSnapshot(3000, 1200, 800, 400));

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(-50.0, velocity.velocity(), 0.1);  // Catching up at 50 msg/s
  }

  @Test
  void equilibrium_zeroVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Lag staying constant (equilibrium)
    history.addSnapshot(new TopicOffsetSnapshot(1000, 1000, 900, 100));
    history.addSnapshot(new TopicOffsetSnapshot(2000, 1100, 1000, 100));
    history.addSnapshot(new TopicOffsetSnapshot(3000, 1200, 1100, 100));

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(0.0, velocity.velocity(), 0.1);  // Zero velocity = equilibrium
  }

  @Test
  void zeroTimeDelta_noVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // All samples at same timestamp (invalid)
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 50, 50));
    history.addSnapshot(new TopicOffsetSnapshot(1000, 150, 80, 70));
    history.addSnapshot(new TopicOffsetSnapshot(1000, 200, 110, 90));

    assertNull(history.calculateVelocity());  // Can't calculate with zero time variance
  }

  @Test
  void multipleSamples_usesLinearRegression() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Perfect linear trend over 4 samples: lag increasing by 10 msg/s
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 90, 10));   // t=0s, lag=10
    history.addSnapshot(new TopicOffsetSnapshot(2000, 110, 90, 20));   // t=1s, lag=20
    history.addSnapshot(new TopicOffsetSnapshot(3000, 120, 90, 30));   // t=2s, lag=30
    history.addSnapshot(new TopicOffsetSnapshot(4000, 130, 90, 40));   // t=3s, lag=40

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(10.0, velocity.velocity(), 0.1);  // Perfect 10 msg/s slope
    assertEquals(3000, velocity.windowDurationMs());
    assertEquals(4, velocity.sampleCount());
  }

  @Test
  void noProducerActivity_negativeVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Consumer processing backlog, no new messages
    history.addSnapshot(new TopicOffsetSnapshot(1000, 1000, 800, 200));
    history.addSnapshot(new TopicOffsetSnapshot(2000, 1000, 850, 150));
    history.addSnapshot(new TopicOffsetSnapshot(3000, 1000, 900, 100));

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(-50.0, velocity.velocity(), 0.1);  // Lag decreasing by 50 msg/s
  }

  @Test
  void highProducerRate_positiveVelocity() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Producer far outpacing consumer
    history.addSnapshot(new TopicOffsetSnapshot(1000, 1000, 900, 100));
    history.addSnapshot(new TopicOffsetSnapshot(2000, 1500, 1000, 500));  // Lag grew by 400
    history.addSnapshot(new TopicOffsetSnapshot(3000, 2000, 1100, 900));  // Lag grew by 400

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(400.0, velocity.velocity(), 1.0);  // Falling behind at 400 msg/s
  }

  @Test
  void linearRegression_perfectTrend() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Perfect linear trend: lag increasing by 10 msg/s
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 90, 10));   // t=0s, lag=10
    history.addSnapshot(new TopicOffsetSnapshot(2000, 110, 90, 20));   // t=1s, lag=20
    history.addSnapshot(new TopicOffsetSnapshot(3000, 120, 90, 30));   // t=2s, lag=30
    history.addSnapshot(new TopicOffsetSnapshot(4000, 130, 90, 40));   // t=3s, lag=40

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(10.0, velocity.velocity(), 0.01);  // Exact 10 msg/s slope
  }

  @Test
  void linearRegression_smoothsOutliers() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Trend with outlier: overall trend is ~10 msg/s
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 90, 10));   // t=0s, lag=10
    history.addSnapshot(new TopicOffsetSnapshot(2000, 110, 90, 25));   // t=1s, lag=25 (outlier spike)
    history.addSnapshot(new TopicOffsetSnapshot(3000, 120, 90, 30));   // t=2s, lag=30

    LagVelocity velocity = history.calculateVelocity();

    // Linear regression should smooth the outlier
    // Best fit line through (0,10), (1000,25), (2000,30) has slope ~10 msg/s
    assertNotNull(velocity);
    assertTrue(Math.abs(velocity.velocity() - 10.0) < 3.0);  // Within tolerance
  }

  @Test
  void linearRegression_smoothsNoise() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 20, 3);

    // Noisy data around a trend of 5 msg/s
    history.addSnapshot(new TopicOffsetSnapshot(1000, 100, 90, 10));   // t=0s, lag=10
    history.addSnapshot(new TopicOffsetSnapshot(2000, 110, 90, 17));   // t=1s, lag=17 (noise)
    history.addSnapshot(new TopicOffsetSnapshot(3000, 120, 90, 18));   // t=2s, lag=18 (noise)
    history.addSnapshot(new TopicOffsetSnapshot(4000, 130, 90, 25));   // t=3s, lag=25
    history.addSnapshot(new TopicOffsetSnapshot(5000, 140, 90, 32));   // t=4s, lag=32 (noise)

    LagVelocity velocity = history.calculateVelocity();

    // Best fit should be around 5-6 msg/s despite noise
    assertNotNull(velocity);
    assertTrue(velocity.velocity() >= 4.0 && velocity.velocity() <= 7.0);
  }

  @Test
  void bufferSizeLimit_evictsOldest() {
    TopicLagHistory history = new TopicLagHistory("group1", "topic1", 5, 3);  // Small buffer

    // Add 6 samples (exceeds buffer size of 5)
    for (int i = 0; i < 6; i++) {
      history.addSnapshot(new TopicOffsetSnapshot(1000 + i * 1000, 100 + i * 10, 90, 10 + i * 10));
    }

    LagVelocity velocity = history.calculateVelocity();

    assertNotNull(velocity);
    assertEquals(5, velocity.sampleCount());  // Only 5 samples retained (oldest evicted)
  }
}
