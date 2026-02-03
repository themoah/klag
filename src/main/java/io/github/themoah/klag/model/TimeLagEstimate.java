package io.github.themoah.klag.model;

/**
 * Estimated lag time in milliseconds for a consumer group and topic.
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param lagMessages the current lag in messages
 * @param velocity the current velocity (messages per second)
 * @param estimatedTimeLagMs estimated time to process current lag in milliseconds
 * @param sampleCount number of samples used for velocity calculation
 */
public record TimeLagEstimate(
  String consumerGroup,
  String topic,
  long lagMessages,
  double velocity,
  long estimatedTimeLagMs,
  int sampleCount
) {}
