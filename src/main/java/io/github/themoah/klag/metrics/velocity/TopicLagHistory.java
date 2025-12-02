package io.github.themoah.klag.metrics.velocity;

import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.TopicOffsetSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages historical snapshots and velocity calculation for a single consumer-group/topic pair.
 */
public class TopicLagHistory {
  private static final Logger log = LoggerFactory.getLogger(TopicLagHistory.class);

  private final String consumerGroup;
  private final String topic;
  private final ArrayDeque<TopicOffsetSnapshot> snapshots;
  private final int maxSize;
  private final int minSamples;
  private boolean hasLoggedDataReady = false;

  public TopicLagHistory(String consumerGroup, String topic, int bufferSize, int minSamples) {
    this.consumerGroup = consumerGroup;
    this.topic = topic;
    this.snapshots = new ArrayDeque<>(bufferSize);
    this.maxSize = bufferSize;
    this.minSamples = minSamples;
  }

  public void addSnapshot(TopicOffsetSnapshot snapshot) {
    if (snapshots.size() >= maxSize) {
      snapshots.removeFirst();  // Evict oldest
    }
    snapshots.addLast(snapshot);

    // Log when we first collect enough data for velocity calculation
    if (!hasLoggedDataReady && snapshots.size() >= minSamples) {
      log.info("Collected {} samples for {}:{} - velocity calculation now available",
        minSamples, consumerGroup, topic);
      hasLoggedDataReady = true;
    }
  }

  /**
   * Calculates velocity using linear regression on (timestamp, lag) points.
   * Returns null if insufficient data.
   */
  public LagVelocity calculateVelocity() {
    if (snapshots.size() < minSamples) {
      return null;
    }

    List<TopicOffsetSnapshot> samples = new ArrayList<>(snapshots);

    // ============================================================================
    // LINEAR REGRESSION: Ordinary Least Squares (OLS)
    // ============================================================================
    // Fits a line (y = mx + b) through (timestamp, lag) points to find slope (m).
    // The slope represents the rate of lag change (velocity in messages/second).
    //
    // Algorithm:
    //   Given n points: (t₁, lag₁), (t₂, lag₂), ..., (tₙ, lagₙ)
    //
    //   1. Calculate means: mean_t = Σtᵢ/n, mean_lag = Σlagᵢ/n
    //   2. Calculate slope: m = Σ((tᵢ - mean_t) × (lagᵢ - mean_lag)) / Σ((tᵢ - mean_t)²)
    //
    // Expanded form (used below for efficiency):
    //   m = (Σ(tᵢ × lagᵢ) - n × mean_t × mean_lag) / (Σ(tᵢ²) - n × mean_t²)
    //
    // Interpretation:
    //   - Positive slope: lag increasing (consumer falling behind)
    //   - Negative slope: lag decreasing (consumer catching up)
    //   - Zero slope: equilibrium (consumer keeping pace)
    // ============================================================================

    long n = samples.size();
    double sumT = 0, sumLag = 0, sumTLag = 0, sumTSquared = 0;

    long firstTimestamp = samples.get(0).timestamp();

    for (TopicOffsetSnapshot sample : samples) {
      // Normalize timestamps to avoid overflow (use milliseconds since first sample)
      double t = (sample.timestamp() - firstTimestamp);
      double lag = sample.lag();

      sumT += t;
      sumLag += lag;
      sumTLag += t * lag;
      sumTSquared += t * t;
    }

    double meanT = sumT / n;
    double meanLag = sumLag / n;

    // Calculate slope using OLS formula (velocity in messages per millisecond)
    double numerator = sumTLag - n * meanT * meanLag;
    double denominator = sumTSquared - n * meanT * meanT;

    if (Math.abs(denominator) < 1e-10) {
      return null;  // All samples at same time, can't calculate slope
    }

    double velocityPerMs = numerator / denominator;
    double velocityPerSecond = velocityPerMs * 1000.0;

    long windowDurationMs = samples.get(samples.size() - 1).timestamp() - samples.get(0).timestamp();

    return new LagVelocity(
      consumerGroup,
      topic,
      velocityPerSecond,
      windowDurationMs,
      samples.size()
    );
  }

  public boolean hasEnoughData() {
    return snapshots.size() >= minSamples;
  }
}
