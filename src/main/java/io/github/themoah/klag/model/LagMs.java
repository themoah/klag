package io.github.themoah.klag.model;

/**
 * Lag in milliseconds for a consumer group and topic.
 * Calculated from actual Kafka message timestamps.
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param lagMessages the current lag in messages
 * @param lagMs the lag in milliseconds (based on message timestamps)
 */
public record LagMs(
  String consumerGroup,
  String topic,
  long lagMessages,
  long lagMs
) {}
