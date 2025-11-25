package io.github.themoah.klag.kafka;

import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.vertx.core.Future;
import java.util.List;
import java.util.Set;

/**
 * Service interface for Kafka administrative operations.
 * All methods return Vert.x Futures for async, non-blocking execution.
 */
public interface KafkaClientService {

  /**
   * Lists all topics in the Kafka cluster.
   *
   * @return Future containing set of topic names
   */
  Future<Set<String>> listTopics();

  /**
   * Gets partition information for a specific topic.
   *
   * @param topic the topic name
   * @return Future containing list of partition info for the topic
   */
  Future<List<PartitionInfo>> listPartitions(String topic);

  /**
   * Gets the log end offsets (latest offsets) for all partitions of a topic.
   *
   * @param topic the topic name
   * @return Future containing list of partition offsets
   */
  Future<List<PartitionOffsets>> getLogEndOffsets(String topic);

  /**
   * Gets the committed offsets for a consumer group.
   *
   * @param groupId the consumer group ID
   * @return Future containing the consumer group offsets
   */
  Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId);

  /**
   * Describes the Kafka cluster (lightweight health check).
   *
   * @return Future containing cluster ID
   */
  Future<String> describeCluster();

  /**
   * Closes the underlying Kafka admin client and releases resources.
   *
   * @return Future that completes when the client is closed
   */
  Future<Void> close();
}
