package io.github.themoah.klag.model;

import java.util.Map;

/**
 * Committed offsets for a consumer group.
 */
public record ConsumerGroupOffsets(
  String groupId,
  Map<TopicPartitionKey, Long> offsets
) {

  /**
   * Key identifying a topic-partition pair.
   */
  public record TopicPartitionKey(String topic, int partition) {}
}
