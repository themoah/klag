package io.github.themoah.klag.kafka;

import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.MemberAssignment;
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
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of KafkaClientService using Vert.x KafkaAdminClient.
 */
public class KafkaClientServiceImpl implements KafkaClientService {

  private static final Logger log = LoggerFactory.getLogger(KafkaClientServiceImpl.class);

  private final KafkaAdminClient adminClient;

  // Per-group signature of the last logged "missing offsets" map. Used to dedupe
  // the WARN emitted by processOffsets so steady-state idle/new groups do not
  // produce repeated WARN noise on each collection cycle.
  final Map<String, Map<String, Integer>> lastMissingByGroup = new ConcurrentHashMap<>();

  // Latched once the broker rejects MAX_TIMESTAMP so the fallback WARN fires once
  // per process instead of once per topic per scrape. Broker capability does not
  // change at runtime, so a single warning is enough.
  private final AtomicBoolean maxTimestampUnsupportedLogged = new AtomicBoolean(false);

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

  // Test-only: admin client is unused. Use this when exercising pure logic
  // (e.g., processOffsets) without a real Kafka cluster or admin client.
  KafkaClientServiceImpl() {
    this.adminClient = null;
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

        // Future.join (not Future.all) so MAX_TIMESTAMP failing on pre-3.0 brokers
        // doesn't fail the whole call — we want to fall back to LATEST instead.
        var composite = Future.join(maxTimestampFuture, earliestTimestampFuture, latestOffsetFuture);
        return composite.transform(ar -> {
          // earliestTimestampFuture and latestOffsetFuture are required.
          if (composite.failed(1)) {
            return Future.failedFuture(composite.cause(1));
          }
          if (composite.failed(2)) {
            return Future.failedFuture(composite.cause(2));
          }

          Map<TopicPartition, ListOffsetsResultInfo> maxTimestampOffsets;
          if (composite.failed(0)) {
            // Broker likely pre-Kafka 3.0 (LIST_OFFSETS v7 unsupported). Fall back to LATEST.
            if (maxTimestampUnsupportedLogged.compareAndSet(false, true)) {
              log.warn("MAX_TIMESTAMP listOffsets unsupported by broker (likely pre-Kafka 3.0); "
                  + "falling back to LATEST for logEndTimestamp. Logged once per process; "
                  + "further occurrences at DEBUG. Cause: {}", composite.cause(0).toString());
            } else {
              log.debug("MAX_TIMESTAMP listOffsets failed for topic {}; using LATEST fallback", topic);
            }
            maxTimestampOffsets = Collections.emptyMap();
          } else {
            maxTimestampOffsets = composite.resultAt(0);
          }
          Map<TopicPartition, ListOffsetsResultInfo> earliestTimestampOffsets = composite.resultAt(1);
          Map<TopicPartition, ListOffsetsResultInfo> latestOffsets = composite.resultAt(2);

          List<PartitionOffsets> result = new ArrayList<>();
          boolean loggedSampleTimestamp = false;

          for (PartitionInfo partition : partitions) {
            TopicPartition tp = new TopicPartition(topic, partition.partition());

            ListOffsetsResultInfo earliestResult = earliestTimestampOffsets.get(tp);
            ListOffsetsResultInfo maxTimestampResult = maxTimestampOffsets.get(tp);
            ListOffsetsResultInfo latestOffsetResult = latestOffsets.get(tp);

            // The partition list comes from a separate describeTopics call; a partition can
            // be missing from a listOffsets response (leaderless partition, partition added
            // mid-call, per-partition broker error). Skip it instead of NPEing the whole topic.
            if (earliestResult == null || latestOffsetResult == null) {
              log.warn("Missing listOffsets result for {}-{} (earliest={}, latest={}); skipping partition",
                topic, partition.partition(), earliestResult != null, latestOffsetResult != null);
              continue;
            }

            // Get earliest offset and timestamp from TIMESTAMP(0)
            long logStartOffset = earliestResult.getOffset();
            long logStartTimestamp = earliestResult.getTimestamp();

            // logEndOffset is ALWAYS the true end-of-log (LATEST). This is the boundary
            // for lag = logEndOffset - committedOffset, throughput, and retention.
            long logEndOffset = latestOffsetResult.getOffset();

            // The timestamp anchor comes from MAX_TIMESTAMP (offset of the highest-timestamp
            // record). This offset can be < logEndOffset, so carry it separately rather than
            // overwriting logEndOffset, otherwise interpolation anchors and lag disagree.
            long logEndTimestamp;
            long maxTimestampOffset;

            if (maxTimestampResult != null
                && maxTimestampResult.getOffset() >= 0
                && maxTimestampResult.getTimestamp() > 0) {
              // MAX_TIMESTAMP returned a valid offset/timestamp anchor
              maxTimestampOffset = maxTimestampResult.getOffset();
              logEndTimestamp = maxTimestampResult.getTimestamp();
            } else {
              // MAX_TIMESTAMP missing/failed (pre-3.0 broker or no timestamps) - fall back to LATEST
              maxTimestampOffset = latestOffsetResult.getOffset();
              logEndTimestamp = latestOffsetResult.getTimestamp();
            }

            // Handle edge case: if earliest also returns -1, topic may be empty
            if (logStartOffset < 0) {
              logStartOffset = 0;
              logStartTimestamp = -1;
            }

            // Log first partition's timestamps at INFO level for debugging
            if (!loggedSampleTimestamp) {
              log.info("Topic {} sample timestamps: partition {} logStart={} (ts={}), logEnd={}, maxTsOffset={} (ts={})",
                topic, partition.partition(), logStartOffset, logStartTimestamp, logEndOffset, maxTimestampOffset, logEndTimestamp);
              loggedSampleTimestamp = true;
            } else {
              log.debug("Topic {} partition {}: logStart={} (ts={}), logEnd={}, maxTsOffset={} (ts={})",
                topic, partition.partition(), logStartOffset, logStartTimestamp, logEndOffset, maxTimestampOffset, logEndTimestamp);
            }

            result.add(new PartitionOffsets(topic, partition.partition(), logEndOffset, logStartOffset,
              logEndTimestamp, maxTimestampOffset, logStartTimestamp));
          }

          log.info("Retrieved offsets for {} partitions of topic {}", result.size(), topic);
          return Future.succeededFuture(result);
        });
      })
      .onFailure(err -> log.error("Failed to get log end offsets for topic: {}", topic, err));
  }

  @Override
  public Future<ConsumerGroupOffsets> getConsumerGroupOffsets(String groupId) {
    Objects.requireNonNull(groupId, "groupId cannot be null");
    log.debug("Getting committed offsets for consumer group: {}", groupId);

    return adminClient.listConsumerGroupOffsets(groupId)
      .map(offsets -> processOffsets(groupId, offsets))
      .onFailure(err -> log.error("Failed to get offsets for consumer group: {}", groupId, err));
  }

  // Package-private for unit testing.
  // Builds the ConsumerGroupOffsets result, tolerates null OffsetAndMetadata
  // entries (Kafka returns null for partitions a group has subscribed to but
  // not yet committed), and dedupes the WARN log by per-group signature so
  // unchanged "missing offsets" state does not spam every collection cycle.
  ConsumerGroupOffsets processOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsets) {
    Map<TopicPartitionKey, Long> offsetMap = new HashMap<>();
    Map<String, Integer> missingByTopic = new HashMap<>();

    offsets.forEach((tp, offsetAndMetadata) -> {
      if (offsetAndMetadata == null) {
        missingByTopic.merge(tp.getTopic(), 1, Integer::sum);
        log.debug("Consumer group {} has no committed offset for topic {} partition {} — skipping",
          groupId, tp.getTopic(), tp.getPartition());
        return;
      }
      TopicPartitionKey key = new TopicPartitionKey(tp.getTopic(), tp.getPartition());
      offsetMap.put(key, offsetAndMetadata.getOffset());
      log.debug("Consumer group {} topic {} partition {}: offset={}",
        groupId, tp.getTopic(), tp.getPartition(), offsetAndMetadata.getOffset());
    });

    Map<String, Integer> prev = lastMissingByGroup.get(groupId);
    if (!missingByTopic.isEmpty()) {
      if (!missingByTopic.equals(prev)) {
        log.warn("Consumer group {} has uncommitted partitions (skipped): {}",
          groupId, missingByTopic);
        lastMissingByGroup.put(groupId, Map.copyOf(missingByTopic));
      } else {
        log.debug("Consumer group {} uncommitted partitions unchanged: {}",
          groupId, missingByTopic);
      }
    } else if (prev != null) {
      log.info("Consumer group {} all partitions now committed (previously missing: {})",
        groupId, prev);
      lastMissingByGroup.remove(groupId);
    }

    log.info("Retrieved {} partition offsets for consumer group {}", offsetMap.size(), groupId);
    return new ConsumerGroupOffsets(groupId, offsetMap);
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
      .onSuccess(groups -> {
        // Prune log-dedup state for groups that no longer exist, otherwise churning
        // ephemeral group IDs (console-consumer-*, CI runs) leak entries for the
        // process lifetime.
        lastMissingByGroup.keySet().retainAll(groups);
        log.info("Listed {} consumer groups", groups.size());
      })
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
          result.put(groupId, new ConsumerGroupState(groupId, state, partitionOwners(description)));
          log.debug("Consumer group {} state: {}", groupId, state);
        });
        log.info("Described {} consumer groups", result.size());
        return result;
      })
      .onFailure(err -> log.error("Failed to describe consumer groups", err));
  }

  // Flattens member assignments into a (topic, partition) -> owning member lookup.
  // A partition can be owned by at most one member; Empty/Dead groups have no members
  // and yield an empty map (callers emit empty-string member labels for those).
  // Package-private for unit testing.
  static Map<TopicPartitionKey, MemberAssignment> partitionOwners(
      io.vertx.kafka.admin.ConsumerGroupDescription description) {
    Map<TopicPartitionKey, MemberAssignment> owners = new HashMap<>();
    if (description.getMembers() == null) {
      return owners;
    }
    description.getMembers().forEach(member -> {
      if (member.getAssignment() == null || member.getAssignment().getTopicPartitions() == null) {
        return;
      }
      MemberAssignment owner = new MemberAssignment(
        member.getHost(), member.getConsumerId(), member.getClientId());
      member.getAssignment().getTopicPartitions().forEach(tp ->
        owners.put(new TopicPartitionKey(tp.getTopic(), tp.getPartition()), owner));
    });
    return owners;
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
