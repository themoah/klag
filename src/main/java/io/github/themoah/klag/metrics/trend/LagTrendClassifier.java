package io.github.themoah.klag.metrics.trend;

import io.github.themoah.klag.model.LagTrend;
import io.github.themoah.klag.model.LagTrend.Direction;
import io.github.themoah.klag.model.LagVelocity;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic classification of lag velocity into a basic {@link Direction} trend.
 *
 * <p>Side-effect free so it is fully unit-testable. A symmetric deadband around zero keeps
 * negligible drift from being reported as a trend.
 */
public final class LagTrendClassifier {

  private LagTrendClassifier() {}

  /**
   * Classifies a single velocity.
   *
   * @param velocity lag velocity in messages/second (positive = falling behind)
   * @param deadband magnitude (msg/s) treated as STABLE; sign is ignored
   * @return GROWING above the band, SHRINKING below the negative band, else STABLE
   */
  public static Direction classify(double velocity, double deadband) {
    double band = Math.abs(deadband);
    if (Math.abs(velocity) <= band) {
      return Direction.STABLE;
    }
    return velocity > 0 ? Direction.GROWING : Direction.SHRINKING;
  }

  /**
   * Maps a list of per-topic velocities to per-topic trends.
   *
   * @param velocities the velocities for one group
   * @param deadband STABLE band magnitude in msg/s
   * @return one {@link LagTrend} per input velocity, in input order
   */
  public static List<LagTrend> perTopic(List<LagVelocity> velocities, double deadband) {
    List<LagTrend> trends = new ArrayList<>(velocities.size());
    for (LagVelocity v : velocities) {
      trends.add(new LagTrend(v.topic(), classify(v.velocity(), deadband), v.velocity()));
    }
    return trends;
  }

  /**
   * Rolls per-topic trends up to a single group-level direction.
   *
   * <p>A group with no lag is STABLE regardless of velocity noise. Otherwise GROWING wins if any
   * topic is growing (worst case dominates), then SHRINKING if any topic is recovering, else STABLE.
   *
   * @param trends per-topic trends for the group
   * @param totalLag the group's total lag across all partitions
   * @return the group-level trend direction
   */
  public static Direction overall(List<LagTrend> trends, long totalLag) {
    if (totalLag <= 0) {
      return Direction.STABLE;
    }
    if (trends.stream().anyMatch(t -> t.direction() == Direction.GROWING)) {
      return Direction.GROWING;
    }
    if (trends.stream().anyMatch(t -> t.direction() == Direction.SHRINKING)) {
      return Direction.SHRINKING;
    }
    return Direction.STABLE;
  }
}
