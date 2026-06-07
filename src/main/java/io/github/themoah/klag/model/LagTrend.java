package io.github.themoah.klag.model;

/**
 * Coarse, basic lag trend for a consumer-group/topic, derived from lag velocity.
 *
 * <p>Surfaced to the MCP layer so agents get a direction (is lag getting worse or better?)
 * without having to interpret the raw velocity number.
 *
 * @param topic the topic the trend is for
 * @param direction whether lag is growing, shrinking, or stable
 * @param velocity the underlying lag velocity in messages/second (positive = falling behind)
 */
public record LagTrend(String topic, Direction direction, double velocity) {

  /** Direction of a lag trend over the recent velocity window. */
  public enum Direction {
    /** Lag is increasing (consumer falling behind). */
    GROWING,
    /** Lag is decreasing (consumer catching up). */
    SHRINKING,
    /** Lag is effectively flat within the deadband. */
    STABLE
  }
}
