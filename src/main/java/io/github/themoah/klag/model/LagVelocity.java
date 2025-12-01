package io.github.themoah.klag.model;

/**
 * Calculated lag velocity for a consumer group and topic.
 */
public record LagVelocity(
  String consumerGroup,
  String topic,
  double velocity,        // messages per second (positive = falling behind)
  long windowDurationMs,  // time window used for calculation
  int sampleCount         // number of snapshots used
) {}
