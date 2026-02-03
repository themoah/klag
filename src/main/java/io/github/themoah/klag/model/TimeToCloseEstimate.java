package io.github.themoah.klag.model;

/**
 * Estimated time to close lag for a consumer group and topic.
 * Only applicable when consumer is catching up (velocity < 0).
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param lagMessages the current lag in messages
 * @param velocity the current velocity (messages per second, negative = catching up)
 * @param estimatedTimeToCloseSeconds estimated seconds until lag reaches zero
 * @param sampleCount number of samples used for velocity calculation
 */
public record TimeToCloseEstimate(
  String consumerGroup,
  String topic,
  long lagMessages,
  double velocity,
  long estimatedTimeToCloseSeconds,
  int sampleCount
) {}
