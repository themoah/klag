package io.github.themoah.klag.model;

/**
 * Lag in milliseconds for a consumer group and topic (max across partitions).
 * Uses Kafka log start/end timestamps when available; otherwise bounded poll-time estimation.
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param lagMessages the current lag in messages
 * @param lagMs the lag in milliseconds
 */
public record LagMs(
  String consumerGroup,
  String topic,
  long lagMessages,
  long lagMs
) {}
