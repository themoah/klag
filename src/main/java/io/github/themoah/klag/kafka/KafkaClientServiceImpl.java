package io.github.themoah.klag.kafka;

import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.kafka.admin.KafkaAdminClient;
import io.vertx.kafka.admin.ListOffsetsResultInfo;
import io.vertx.kafka.admin.OffsetSpec;
import io.vertx.kafka.admin.TopicDescription;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.common.TopicPartitionInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of KafkaClientService using Vert.x KafkaAdminClient.
 */
public class KafkaClientServiceImpl implements KafkaClientService {

  private static final Logger log = LoggerFactory.getLogger(KafkaClientServiceImpl.class);

  private final KafkaAdminClient adminClient;

  /**
   * Creates a new KafkaClientServiceImpl.
   *
   * @param vertx  the Vert.x instance
   * @param config the Kafka client configuration
   */
  public KafkaClientServiceImpl(Vertx vertx, KafkaClientConfig config) {
    Objects.requireNonNull(vertx, "vertx cannot be null");
    Objects.requireNonNull(config, "config cannot be null");
    log.info("Creating Kafka admin client with bootstrap servers: {}", config.getBootstrapServers());
    this.adminClient = KafkaAdminClient.create(vertx, config.toProperties());
  }

  /**
   * Creates a new KafkaClientServiceImpl with an existing admin client (for testing).
   *
   * @param adminClient the Kafka admin client
   */
  KafkaClientServiceImpl(KafkaAdminClient adminClient) {
    this.adminClient = Objects.requireNonNull(adminClient, "adminClient cannot be null");
  }

  @Override
  public Future<Set<String>> listTopics() {
    log.debug("Listing all topics");
    return adminClient.listTopics()
      .onSuccess(topics -> log.info("Listed {} topics", topics.size()))
      .onFailure(err -> log.error("Failed to list topics", err));
  }

  @Override
  public Future<List<PartitionInfo>> listPartitions(String topic) {
    Objects.requireNonNull(topic, "topic cannot be null");
    log.debug("Listing partitions for topic: {}", topic);

    return adminClient.describeTopics(Collections.singletonList(topic))
      .map(descriptions -> {
        TopicDescription description = descriptions.get(topic);
        if (description == null) {
          throw new IllegalArgumentException("Topic not found: " + topic);
        }
        List<PartitionInfo> partitions = description.getPartitions().stream()
          .map(partition -> toPartitionInfo(topic, partition))
          .collect(Collectors.toList());
        log.info("Topic {} has {} partitions", topic, partitions.size());
        return partitions;
      })
      .onFailure(err -> log.error("Failed to list partitions for topic: {}", topic, err));
  }

  @Override
  public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) {
    Objects.requireNonNull(topic, "topic cannot be null");
    log.debug("Getting log end offsets for topic: {}", topic);

    return listPartitions(topic)
      .compose(partitions -> {
        Map<TopicPartition, OffsetSpec> latestRequest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> earliestRequest = new HashMap<>();

        for (PartitionInfo partition : partitions) {
          TopicPartition tp = new TopicPartition(topic, partition.partition());
          latestRequest.put(tp, OffsetSpec.LATEST);
          earliestRequest.put(tp, OffsetSpec.EARLIEST);
        }

        Future<Map<TopicPartition, ListOffsetsResultInfo>> latestFuture =
          adminClient.listOffsets(latestRequest);
        Future<Map<TopicPartition, ListOffsetsResultInfo>> earliestFuture =
          adminClient.listOffsets(earliestRequest);

        return Future.all(latestFuture, earliestFuture)
          .map(composite -> {
            Map<TopicPartition, ListOffsetsResultInfo> latestOffsets = composite.resultAt(0);
            Map<TopicPartition, ListOffsetsResultInfo> earliestOffsets = composite.resultAt(1);

            List<PartitionOffsets> result = partitions.stream()
              .map(partition -> {
                TopicPartition tp = new TopicPartition(topic, partition.partition());
                long logEndOffset = latestOffsets.get(tp).getOffset();
                long logStartOffset = earliestOffsets.get(tp).getOffset();
                log.debug("Topic {} partition {}: logStart={}, logEnd={}",
                  topic, partition.partition(), logStartOffset, logEndOffset);
                return new PartitionOffsets(topic, partition.partition(), logEndOffset, logStartOffset);
              })
              .collect(Collectors.toList());

            log.info("Retrieved offsets for {} partitions of topic {}", result.size(), topic);
            return result;
          });
      })
      .onFailure(err -> log.error("Failed to get log end offsets for topic: {}", topic, err));
  }

  @Override
  public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
    Objects.requireNonNull(groupId, "groupId cannot be null");
    log.debug("Getting committed offsets for consumer group: {}", groupId);

    return adminClient.listConsumerGroupOffsets(groupId)
      .map(offsets -> {
        Map<TopicPartitionKey, Long> offsetMap = new HashMap<>();
        offsets.forEach((tp, offsetAndMetadata) -> {
          TopicPartitionKey key = new TopicPartitionKey(tp.getTopic(), tp.getPartition());
          offsetMap.put(key, offsetAndMetadata.getOffset());
          log.debug("Consumer group {} topic {} partition {}: offset={}",
            groupId, tp.getTopic(), tp.getPartition(), offsetAndMetadata.getOffset());
        });
        log.info("Retrieved {} partition offsets for consumer group {}", offsetMap.size(), groupId);
        return new ConsumerGroupOffsets(groupId, offsetMap);
      })
      .onFailure(err -> log.error("Failed to get offsets for consumer group: {}", groupId, err));
  }

  @Override
  public Future<String> describeCluster() {
    log.debug("Describing cluster");
    return adminClient.describeCluster()
      .map(description -> description.getClusterId())
      .onSuccess(clusterId -> log.debug("Cluster ID: {}", clusterId))
      .onFailure(err -> log.error("Failed to describe cluster", err));
  }

  @Override
  public Future<Set<String>> listConsumerGroups() {
    log.debug("Listing consumer groups");
    return adminClient.listConsumerGroups()
      .map(listings -> listings.stream()
        .map(listing -> listing.getGroupId())
        .collect(Collectors.toSet()))
      .onSuccess(groups -> log.info("Listed {} consumer groups", groups.size()))
      .onFailure(err -> log.error("Failed to list consumer groups", err));
  }

  @Override
  public Future<Map<String, ConsumerGroupState>> describeConsumerGroups(Set<String> groupIds) {
    if (groupIds == null || groupIds.isEmpty()) {
      log.debug("No consumer groups to describe");
      return Future.succeededFuture(Map.of());
    }

    log.debug("Describing {} consumer groups", groupIds.size());

    return adminClient.describeConsumerGroups(new ArrayList<>(groupIds))
      .map(descriptions -> {
        Map<String, ConsumerGroupState> result = new HashMap<>();
        descriptions.forEach((groupId, description) -> {
          ConsumerGroupState.State state = ConsumerGroupState.State
              .fromKafkaState(description.getState());
          result.put(groupId, new ConsumerGroupState(groupId, state));
          log.debug("Consumer group {} state: {}", groupId, state);
        });
        log.info("Described {} consumer groups", result.size());
        return result;
      })
      .onFailure(err -> log.error("Failed to describe consumer groups", err));
  }

  @Override
  public Future<Void> close() {
    log.info("Closing Kafka admin client");
    return adminClient.close()
      .onSuccess(v -> log.info("Kafka admin client closed"))
      .onFailure(err -> log.error("Failed to close Kafka admin client", err));
  }

  private PartitionInfo toPartitionInfo(String topic, TopicPartitionInfo tpi) {
    return new PartitionInfo(
      topic,
      tpi.getPartition(),
      tpi.getLeader().getId(),
      tpi.getReplicas().stream().map(node -> node.getId()).collect(Collectors.toList()),
      tpi.getIsr().stream().map(node -> node.getId()).collect(Collectors.toList())
    );
  }
}
