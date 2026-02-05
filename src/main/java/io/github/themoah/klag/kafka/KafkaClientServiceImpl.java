package io.github.themoah.klag.kafka;

import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.PartitionInfo;
import io.github.themoah.klag.model.PartitionOffsets;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.kafka.admin.Config;
import io.vertx.kafka.admin.ConfigEntry;
import io.vertx.kafka.admin.KafkaAdminClient;
import io.vertx.kafka.admin.ListOffsetsResultInfo;
import io.vertx.kafka.admin.OffsetSpec;
import io.vertx.kafka.admin.TopicDescription;
import io.vertx.kafka.client.common.ConfigResource;
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

  // Kafka 3.0+ MAX_TIMESTAMP spec value (not exposed by Vert.x wrapper)
  // Returns the offset with the highest timestamp in the partition
  private static final long MAX_TIMESTAMP_SPEC = -3L;

  @Override
  public Future<List<PartitionOffsets>> getLogEndOffsets(String topic) {
    Objects.requireNonNull(topic, "topic cannot be null");
    log.debug("Getting log end offsets for topic: {}", topic);

    return listPartitions(topic)
      .compose(partitions -> {
        // Use MAX_TIMESTAMP to get the offset with highest timestamp (Kafka 3.0+)
        // Use TIMESTAMP(0) to get earliest offset with its timestamp
        // Use LATEST as fallback for offset if MAX_TIMESTAMP fails
        Map<TopicPartition, OffsetSpec> maxTimestampRequest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> earliestTimestampRequest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latestOffsetRequest = new HashMap<>();

        for (PartitionInfo partition : partitions) {
          TopicPartition tp = new TopicPartition(topic, partition.partition());
          maxTimestampRequest.put(tp, new OffsetSpec(MAX_TIMESTAMP_SPEC));
          earliestTimestampRequest.put(tp, OffsetSpec.TIMESTAMP(0));
          latestOffsetRequest.put(tp, OffsetSpec.LATEST);
        }

        // Three queries:
        // 1. MAX_TIMESTAMP - returns offset with highest timestamp (Kafka 3.0+)
        // 2. TIMESTAMP(0) - returns earliest offset with its timestamp
        // 3. LATEST - fallback for actual latest offset
        Future<Map<TopicPartition, ListOffsetsResultInfo>> maxTimestampFuture =
          adminClient.listOffsets(maxTimestampRequest);
        Future<Map<TopicPartition, ListOffsetsResultInfo>> earliestTimestampFuture =
          adminClient.listOffsets(earliestTimestampRequest);
        Future<Map<TopicPartition, ListOffsetsResultInfo>> latestOffsetFuture =
          adminClient.listOffsets(latestOffsetRequest);

        return Future.all(maxTimestampFuture, earliestTimestampFuture, latestOffsetFuture)
          .map(composite -> {
            Map<TopicPartition, ListOffsetsResultInfo> maxTimestampOffsets = composite.resultAt(0);
            Map<TopicPartition, ListOffsetsResultInfo> earliestTimestampOffsets = composite.resultAt(1);
            Map<TopicPartition, ListOffsetsResultInfo> latestOffsets = composite.resultAt(2);

            List<PartitionOffsets> result = new ArrayList<>();
            boolean loggedSampleTimestamp = false;

            for (PartitionInfo partition : partitions) {
              TopicPartition tp = new TopicPartition(topic, partition.partition());

              // Get earliest offset and timestamp from TIMESTAMP(0)
              long logStartOffset = earliestTimestampOffsets.get(tp).getOffset();
              long logStartTimestamp = earliestTimestampOffsets.get(tp).getTimestamp();

              // Get latest offset and timestamp - prefer MAX_TIMESTAMP if valid
              ListOffsetsResultInfo maxTimestampResult = maxTimestampOffsets.get(tp);
              ListOffsetsResultInfo latestOffsetResult = latestOffsets.get(tp);

              long logEndOffset;
              long logEndTimestamp;

              if (maxTimestampResult.getOffset() >= 0 && maxTimestampResult.getTimestamp() > 0) {
                // MAX_TIMESTAMP returned valid offset and timestamp
                logEndOffset = maxTimestampResult.getOffset();
                logEndTimestamp = maxTimestampResult.getTimestamp();
              } else {
                // MAX_TIMESTAMP failed (pre-3.0 broker or no timestamps) - use LATEST
                logEndOffset = latestOffsetResult.getOffset();
                logEndTimestamp = latestOffsetResult.getTimestamp();
              }

              // Handle edge case: if earliest also returns -1, topic may be empty
              if (logStartOffset < 0) {
                logStartOffset = 0;
                logStartTimestamp = -1;
              }

              // Log first partition's timestamps at INFO level for debugging
              if (!loggedSampleTimestamp) {
                log.info("Topic {} sample timestamps: partition {} logStart={} (ts={}), logEnd={} (ts={})",
                  topic, partition.partition(), logStartOffset, logStartTimestamp, logEndOffset, logEndTimestamp);
                loggedSampleTimestamp = true;
              } else {
                log.debug("Topic {} partition {}: logStart={} (ts={}), logEnd={} (ts={})",
                  topic, partition.partition(), logStartOffset, logStartTimestamp, logEndOffset, logEndTimestamp);
              }

              result.add(new PartitionOffsets(topic, partition.partition(), logEndOffset, logStartOffset,
                logEndTimestamp, logStartTimestamp));
            }

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
  public Future<Map<String, Long>> getTopicRetentionMs(Set<String> topics) {
    if (topics == null || topics.isEmpty()) {
      log.debug("No topics to get retention for");
      return Future.succeededFuture(Map.of());
    }

    log.debug("Getting retention.ms for {} topics", topics.size());

    List<ConfigResource> resources = topics.stream()
      .map(topic -> new ConfigResource(
        org.apache.kafka.common.config.ConfigResource.Type.TOPIC, topic))
      .collect(Collectors.toList());

    return adminClient.describeConfigs(resources)
      .map(configs -> {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<ConfigResource, Config> entry : configs.entrySet()) {
          String topic = entry.getKey().getName();
          Config config = entry.getValue();

          for (ConfigEntry configEntry : config.getEntries()) {
            if ("retention.ms".equals(configEntry.getName())) {
              try {
                long retention = Long.parseLong(configEntry.getValue());
                result.put(topic, retention);
                log.debug("Topic {} retention.ms: {}", topic, retention);
              } catch (NumberFormatException e) {
                log.warn("Invalid retention.ms value for topic {}: {}", topic, configEntry.getValue());
              }
              break;
            }
          }
        }
        log.info("Retrieved retention.ms for {} topics", result.size());
        return result;
      })
      .onFailure(err -> log.error("Failed to get topic retention configs", err));
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
