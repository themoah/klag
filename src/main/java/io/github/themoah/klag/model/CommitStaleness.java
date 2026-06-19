package io.github.themoah.klag.model;

/**
 * Time since klag last observed the committed offset advance for a consumer group and topic.
 *
 * <p>Kafka's admin API exposes no commit timestamp, so freshness is inferred: staleness is measured
 * from the last collection cycle in which the committed offset changed, not from the actual commit
 * time. The clock starts at klag startup and resets on restart. Only meaningful when lag &gt; 0.
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param stalenessSeconds seconds since the committed offset last advanced
 * @param lagMessages the current lag in messages (the gate: only reported when &gt; 0)
 */
public record CommitStaleness(
  String consumerGroup,
  String topic,
  long stalenessSeconds,
  long lagMessages
) {}
