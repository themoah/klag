package io.github.themoah.klag.metrics;

import io.github.themoah.klag.kafka.ChunkConfig;
import io.github.themoah.klag.kafka.ChunkProcessor;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionDetector;
import io.github.themoah.klag.metrics.timelag.OffsetTimestampTracker;
import io.github.themoah.klag.metrics.timelag.TimeLagConfig;
import io.github.themoah.klag.metrics.timelag.TimeLagEstimator;
import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.PartitionOffsets;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically collects lag metrics from Kafka and reports them.
 * Dynamically discovers consumer groups with optional glob filter.
 */
public class MetricsCollector {

  private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

  private final Vertx vertx;
  private final KafkaClientService kafkaClient;
  private final MetricsReporter reporter;
  private final long intervalMs;
  private final Pattern groupPattern;
  private final LagVelocityTracker velocityTracker;
  private final HotPartitionDetector hotPartitionDetector;
  private final TimeLagEstimator timeLagEstimator;
  private final OffsetTimestampTracker offsetTimestampTracker;
  private final ChunkConfig chunkConfig;

  private final Map<String, Integer> cachedGroupPartitionCounts = new ConcurrentHashMap<>();
  private final Map<String, Integer> cachedTopicPartitionCounts = new ConcurrentHashMap<>();

  private Long timerId;

  public MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MetricsReporter reporter,
    long intervalMs,
    String groupFilter
  ) {
    this(vertx, kafkaClient, reporter, intervalMs, groupFilter,
      new LagVelocityTracker(), HotPartitionConfig.fromEnvironment(),
      TimeLagConfig.fromEnvironment(), ChunkConfig.fromEnvironment());
  }

  public MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MetricsReporter reporter,
    long intervalMs,
    String groupFilter,
    HotPartitionConfig hotPartitionConfig
  ) {
    this(vertx, kafkaClient, reporter, intervalMs, groupFilter,
      new LagVelocityTracker(), hotPartitionConfig,
      TimeLagConfig.fromEnvironment(), ChunkConfig.fromEnvironment());
  }

  /**
   * Constructor with injectable velocity tracker, hot partition config, time lag config, and chunk config (for testing).
   */
  MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MetricsReporter reporter,
    long intervalMs,
    String groupFilter,
    LagVelocityTracker velocityTracker,
    HotPartitionConfig hotPartitionConfig,
    TimeLagConfig timeLagConfig,
    ChunkConfig chunkConfig
  ) {
    this.vertx = vertx;
    this.kafkaClient = kafkaClient;
    this.reporter = reporter;
    this.intervalMs = intervalMs;
    this.groupPattern = compileGlobPattern(groupFilter);
    this.velocityTracker = velocityTracker;
    this.hotPartitionDetector = hotPartitionConfig.enabled()
      ? new HotPartitionDetector(hotPartitionConfig)
      : null;
    this.timeLagEstimator = timeLagConfig.enabled()
      ? new TimeLagEstimator(timeLagConfig)
      : null;
    this.offsetTimestampTracker = timeLagConfig.enabled()
      ? new OffsetTimestampTracker(timeLagConfig.interpolationBufferSize(),
                                   timeLagConfig.staleProducerThresholdMs())
      : null;
    this.chunkConfig = chunkConfig;
  }

  /**
   * Starts the metrics collector with periodic collection.
   */
  public Future<Void> start() {
    log.info("Starting metrics collector with interval: {}ms, filter: {}",
      intervalMs, groupPattern != null ? groupPattern.pattern() : "*");

    return reporter.start()
      .compose(v -> collectAndReport())
      .onComplete(ar -> {
        timerId = vertx.setPeriodic(intervalMs, id -> collectAndReport());
        log.info("Metrics collector started, timer ID: {}", timerId);
      })
      .mapEmpty();
  }

  /**
   * Stops the metrics collector.
   */
  public Future<Void> stop() {
    log.info("Stopping metrics collector");
    if (timerId != null) {
      vertx.cancelTimer(timerId);
      timerId = null;
    }
    return reporter.close();
  }

  private Future<Void> collectAndReport() {
    log.debug("Collecting lag metrics");

    return kafkaClient.listConsumerGroups()
      .compose(groups -> {
        Set<String> filteredGroups = groups.stream()
          .filter(this::matchesFilter)
          .collect(Collectors.toSet());

        log.debug("Found {} consumer groups, {} after filtering",
          groups.size(), filteredGroups.size());

        if (filteredGroups.isEmpty()) {
          if (reporter instanceof MicrometerReporter micrometerReporter) {
            micrometerReporter.cleanupStaleGauges(Set.of());
          }
          return Future.succeededFuture();
        }

        if (chunkConfig.isChunkingEnabled()) {
          return collectAndReportChunked(filteredGroups);
        }

        return collectAllGroupsParallel(filteredGroups);
      })
      .onFailure(err -> log.error("Failed to collect lag metrics", err));
  }

  /**
   * Original non-chunked path: collects all groups in parallel.
   */
  private Future<Void> collectAllGroupsParallel(Set<String> filteredGroups) {
    Future<List<ConsumerGroupLag>> lagFuture = collectAllGroupLags(filteredGroups);
    Future<Map<String, ConsumerGroupState>> stateFuture =
        kafkaClient.describeConsumerGroups(filteredGroups);

    return Future.all(lagFuture, stateFuture)
      .map(composite -> {
        List<ConsumerGroupLag> lagData = composite.resultAt(0);
        Map<String, ConsumerGroupState> stateData = composite.resultAt(1);

        Set<String> activeKeys = new HashSet<>();
        Set<String> velocityKeys = new HashSet<>();
        reportMetrics(lagData, stateData, activeKeys, velocityKeys);
        velocityTracker.cleanupStaleTopics(velocityKeys);
        if (reporter instanceof MicrometerReporter micrometerReporter) {
          micrometerReporter.cleanupStaleGauges(activeKeys);
        }
        return (Void) null;
      });
  }

  /**
   * Chunked path: splits groups into balanced chunks and processes sequentially.
   */
  private Future<Void> collectAndReportChunked(Set<String> filteredGroups) {
    log.debug("Processing {} groups in {} chunks with {}ms delay",
      filteredGroups.size(), chunkConfig.chunkCount(), chunkConfig.chunkDelayMs());

    List<List<String>> groupChunks = ChunkProcessor.balanceIntoChunks(
      filteredGroups, chunkConfig.chunkCount(),
      group -> cachedGroupPartitionCounts.getOrDefault(group, 1));

    Set<String> cycleActiveKeys = new HashSet<>();
    Set<String> cycleVelocityKeys = new HashSet<>();

    return ChunkProcessor.<String, Void>processSequentially(
      vertx, groupChunks, chunkConfig.chunkDelayMs(),
      chunk -> processGroupChunk(chunk, cycleActiveKeys, cycleVelocityKeys)
    ).compose(results -> {
      velocityTracker.cleanupStaleTopics(cycleVelocityKeys);
      if (reporter instanceof MicrometerReporter micrometerReporter) {
        micrometerReporter.cleanupStaleGauges(cycleActiveKeys);
      }
      return Future.succeededFuture();
    });
  }

  /**
   * Processes a single chunk of consumer groups: collects lag, describes groups, reports metrics.
   */
  private Future<Void> processGroupChunk(
      List<String> chunk,
      Set<String> cycleActiveKeys,
      Set<String> cycleVelocityKeys
  ) {
    log.debug("Processing group chunk with {} groups", chunk.size());

    Future<List<ConsumerGroupLag>> lagFuture;
    if (chunkConfig.isChunkingEnabled()) {
      lagFuture = collectGroupLagsChunked(chunk);
    } else {
      lagFuture = collectAllGroupLags(new HashSet<>(chunk));
    }

    Future<Map<String, ConsumerGroupState>> stateFuture =
        kafkaClient.describeConsumerGroups(new HashSet<>(chunk));

    return Future.all(lagFuture, stateFuture)
      .map(composite -> {
        List<ConsumerGroupLag> lagData = composite.resultAt(0);
        Map<String, ConsumerGroupState> stateData = composite.resultAt(1);

        reportMetrics(lagData, stateData, cycleActiveKeys, cycleVelocityKeys);

        // Update cached group partition counts
        for (ConsumerGroupLag lag : lagData) {
          cachedGroupPartitionCounts.put(lag.consumerGroup(), lag.partitions().size());
        }

        return (Void) null;
      })
      .onFailure(err -> log.warn("Failed to process group chunk: {}", err.getMessage()));
  }

  /**
   * Collects lag for a list of groups with topic-level chunking.
   */
  private Future<List<ConsumerGroupLag>> collectGroupLagsChunked(List<String> groups) {
    List<Future<ConsumerGroupLag>> futures = groups.stream()
      .map(this::collectGroupLagChunked)
      .collect(Collectors.toList());

    return Future.all(futures)
      .map(composite -> {
        List<ConsumerGroupLag> results = new ArrayList<>();
        for (int i = 0; i < composite.size(); i++) {
          ConsumerGroupLag lag = composite.resultAt(i);
          if (lag != null) {
            results.add(lag);
          }
        }
        return results;
      });
  }

  /**
   * Collects lag for a single group with topic-level chunking for log end offset fetches.
   */
  private Future<ConsumerGroupLag> collectGroupLagChunked(String groupId) {
    return kafkaClient.getConsumerGroupOffsets(groupId)
      .compose(offsets -> {
        Set<String> topics = offsets.offsets().keySet().stream()
          .map(TopicPartitionKey::topic)
          .collect(Collectors.toSet());

        if (topics.isEmpty()) {
          return Future.succeededFuture(ConsumerGroupLag.fromPartitions(groupId, List.of()));
        }

        // Balance topics into chunks
        List<List<String>> topicChunks = ChunkProcessor.balanceIntoChunks(
          topics, chunkConfig.chunkCount(),
          topic -> cachedTopicPartitionCounts.getOrDefault(topic, 1));

        // Process topic chunks sequentially, each chunk fetches offsets in parallel
        return ChunkProcessor.<String, List<PartitionOffsets>>processSequentially(
          vertx, topicChunks, chunkConfig.chunkDelayMs(),
          topicChunk -> {
            List<Future<List<PartitionOffsets>>> offsetFutures = topicChunk.stream()
              .map(kafkaClient::getLogEndOffsets)
              .collect(Collectors.toList());

            return Future.all(offsetFutures)
              .map(composite -> {
                List<PartitionOffsets> merged = new ArrayList<>();
                for (int i = 0; i < composite.size(); i++) {
                  List<PartitionOffsets> partitionOffsets = composite.resultAt(i);
                  merged.addAll(partitionOffsets);
                }
                return merged;
              });
          }
        ).map(chunkResults -> {
          // Merge all partition offsets from all topic chunks
          Map<TopicPartitionKey, PartitionOffsets> topicOffsets = new HashMap<>();
          for (List<PartitionOffsets> chunkResult : chunkResults) {
            for (PartitionOffsets po : chunkResult) {
              TopicPartitionKey key = new TopicPartitionKey(po.topic(), po.partition());
              topicOffsets.put(key, po);
              // Update cached topic partition counts
              cachedTopicPartitionCounts.merge(po.topic(), 1, Integer::max);
            }
          }

          // Update cached topic partition counts with actual partition counts
          Map<String, Integer> topicPartitionCounts = new HashMap<>();
          for (PartitionOffsets po : topicOffsets.values()) {
            topicPartitionCounts.merge(po.topic(), 1, Integer::sum);
          }
          cachedTopicPartitionCounts.putAll(topicPartitionCounts);

          return buildConsumerGroupLag(groupId, offsets, topicOffsets);
        });
      })
      .onFailure(err -> log.warn("Failed to collect lag for group {}: {}", groupId, err.getMessage()));
  }

  private Future<List<ConsumerGroupLag>> collectAllGroupLags(Set<String> groups) {
    List<Future<ConsumerGroupLag>> futures = groups.stream()
      .map(this::collectGroupLag)
      .collect(Collectors.toList());

    return Future.all(futures)
      .map(composite -> {
        List<ConsumerGroupLag> results = new ArrayList<>();
        for (int i = 0; i < composite.size(); i++) {
          ConsumerGroupLag lag = composite.resultAt(i);
          if (lag != null) {
            results.add(lag);
          }
        }
        return results;
      });
  }

  /**
   * Reports metrics for the given lag and state data.
   * Adds active gauge keys to the provided set but does NOT perform cleanup.
   * Velocity keys are accumulated across chunks for cycle-level cleanup.
   */
  private void reportMetrics(
      List<ConsumerGroupLag> lagData,
      Map<String, ConsumerGroupState> stateData,
      Set<String> activeKeys,
      Set<String> velocityKeys
  ) {
    if (reporter instanceof MicrometerReporter micrometerReporter) {
      // Report topic partition counts (max partition number + 1)
      Map<String, Integer> topicPartitions = new HashMap<>();
      for (ConsumerGroupLag group : lagData) {
        for (PartitionLag p : group.partitions()) {
          topicPartitions.merge(p.topic(), p.partition() + 1, Integer::max);
        }
      }

      // Aggregate partition data by topic for velocity tracking
      Map<String, Map<String, TopicAggregates>> groupTopicAggregates = new HashMap<>();
      for (ConsumerGroupLag group : lagData) {
        Map<String, TopicAggregates> topicAggregates = groupTopicAggregates
          .computeIfAbsent(group.consumerGroup(), k -> new HashMap<>());

        for (PartitionLag p : group.partitions()) {
          topicAggregates.computeIfAbsent(p.topic(), k -> new TopicAggregates())
            .add(p.logEndOffset(), p.committedOffset(), p.lag());
        }
      }

      // Record snapshots for velocity calculation (velocityKeys accumulates across chunks)
      groupTopicAggregates.forEach((consumerGroup, topicMap) ->
        topicMap.forEach((topic, agg) -> {
          recordVelocitySnapshot(consumerGroup, topic, agg);
          velocityKeys.add(consumerGroup + ":" + topic);
        })
      );

      // Calculate and report velocities
      List<LagVelocity> velocities = velocityTracker.calculateVelocities();
      micrometerReporter.reportVelocity(velocities, activeKeys);

      // Calculate lag in ms from timestamps
      List<LagMs> lagMsData = calculateLagMs(lagData);
      micrometerReporter.reportLagMs(lagMsData, activeKeys);

      // Time-to-close estimation (based on velocity data)
      if (timeLagEstimator != null && timeLagEstimator.isEnabled()) {
        // Build lag map: group -> topic -> totalLag
        Map<String, Map<String, Long>> lagByGroupTopic = new HashMap<>();
        groupTopicAggregates.forEach((group, topicMap) ->
          topicMap.forEach((topic, agg) ->
            lagByGroupTopic.computeIfAbsent(group, k -> new HashMap<>())
              .put(topic, agg.totalLag())
          )
        );

        List<TimeToCloseEstimate> timeToCloseEstimates = timeLagEstimator.calculateTimeToClose(velocities, lagByGroupTopic);
        micrometerReporter.reportTimeToClose(timeToCloseEstimates, activeKeys);
      }

      // Retention risk percentage calculation (offset-based)
      List<RetentionRisk> retentionRisks = calculateRetentionRisks(lagData);
      if (!retentionRisks.isEmpty()) {
        micrometerReporter.reportRetentionPercent(retentionRisks, activeKeys);
      }

      // Report lag and state metrics
      micrometerReporter.reportTopicPartitions(topicPartitions, activeKeys);
      micrometerReporter.reportLag(lagData, activeKeys);
      micrometerReporter.reportConsumerGroupStates(stateData, activeKeys);

      // Hot partition detection and reporting
      if (hotPartitionDetector != null && hotPartitionDetector.isEnabled()) {
        Set<String> throughputKeys = hotPartitionDetector.recordThroughputSnapshots(lagData);

        List<HotPartitionLag> hotByLag = hotPartitionDetector.detectHotPartitionsByLag(lagData);
        micrometerReporter.reportHotPartitionLag(hotByLag, activeKeys);

        List<HotPartitionThroughput> hotByThroughput = hotPartitionDetector.detectHotPartitionsByThroughput();
        micrometerReporter.reportHotPartitionThroughput(hotByThroughput, activeKeys);

        hotPartitionDetector.cleanupStalePartitions(throughputKeys);
      }

      log.debug("Reported metrics for {} consumer groups", lagData.size());
    } else {
      reporter.reportLag(lagData);
    }
  }

  private Future<ConsumerGroupLag> collectGroupLag(String groupId) {
    return kafkaClient.getConsumerGroupOffsets(groupId)
      .compose(offsets -> {
        Set<String> topics = offsets.offsets().keySet().stream()
          .map(TopicPartitionKey::topic)
          .collect(Collectors.toSet());

        if (topics.isEmpty()) {
          return Future.succeededFuture(ConsumerGroupLag.fromPartitions(groupId, List.of()));
        }

        // Get log end offsets for all topics the group is consuming
        List<Future<List<PartitionOffsets>>> offsetFutures = topics.stream()
          .map(kafkaClient::getLogEndOffsets)
          .collect(Collectors.toList());

        return Future.all(offsetFutures)
          .map(composite -> {
            Map<TopicPartitionKey, PartitionOffsets> topicOffsets = new HashMap<>();
            for (int i = 0; i < composite.size(); i++) {
              List<PartitionOffsets> partitionOffsets = composite.resultAt(i);
              for (PartitionOffsets po : partitionOffsets) {
                TopicPartitionKey key = new TopicPartitionKey(po.topic(), po.partition());
                topicOffsets.put(key, po);
              }
            }
            return buildConsumerGroupLag(groupId, offsets, topicOffsets);
          });
      })
      .onFailure(err -> log.warn("Failed to collect lag for group {}: {}", groupId, err.getMessage()));
  }

  private ConsumerGroupLag buildConsumerGroupLag(
    String groupId,
    ConsumerGroupOffsets offsets,
    Map<TopicPartitionKey, PartitionOffsets> topicOffsets
  ) {
    List<PartitionLag> partitionLags = new ArrayList<>();

    for (Map.Entry<TopicPartitionKey, Long> entry : offsets.offsets().entrySet()) {
      TopicPartitionKey key = entry.getKey();
      long committedOffset = entry.getValue();

      PartitionOffsets po = topicOffsets.get(key);
      if (po != null) {
        PartitionLag lag = PartitionLag.of(
          key.topic(),
          key.partition(),
          po.logEndOffset(),
          po.logStartOffset(),
          po.logEndTimestamp(),
          po.logStartTimestamp(),
          committedOffset
        );
        partitionLags.add(lag);
      }
    }

    return ConsumerGroupLag.fromPartitions(groupId, partitionLags);
  }

  private boolean matchesFilter(String groupId) {
    if (groupPattern == null) {
      return true;
    }
    return groupPattern.matcher(groupId).matches();
  }

  /**
   * Converts a simple glob pattern to a regex pattern.
   * Supports * as wildcard for any characters.
   */
  private static Pattern compileGlobPattern(String glob) {
    if (glob == null || glob.isBlank() || glob.equals("*")) {
      return null;
    }

    StringBuilder regex = new StringBuilder("^");
    for (char c : glob.toCharArray()) {
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append(".");
        case '.' -> regex.append("\\.");
        case '\\' -> regex.append("\\\\");
        case '[', ']', '(', ')', '{', '}', '^', '$', '|', '+' -> regex.append("\\").append(c);
        default -> regex.append(c);
      }
    }
    regex.append("$");

    return Pattern.compile(regex.toString());
  }

  /**
   * Records a velocity snapshot for a consumer group and topic.
   *
   * @param consumerGroup the consumer group ID
   * @param topic the topic name
   * @param agg the aggregated topic metrics
   */
  private void recordVelocitySnapshot(String consumerGroup, String topic, TopicAggregates agg) {
    velocityTracker.recordSnapshot(
      consumerGroup,
      topic,
      agg.totalLogEndOffset(),
      agg.totalCommittedOffset(),
      agg.totalLag()
    );
  }

  /**
   * Calculates retention risk percentages from offsets.
   * Formula: (lag / (logEndOffset - logStartOffset)) * 100
   *
   * <p>Per-partition calculation, aggregated to max per topic.
   *
   * @param lagData list of consumer group lag data
   * @return list of retention risks
   */
  private List<RetentionRisk> calculateRetentionRisks(List<ConsumerGroupLag> lagData) {
    List<RetentionRisk> risks = new ArrayList<>();

    for (ConsumerGroupLag group : lagData) {
      // Group partitions by topic and calculate max percentage
      Map<String, Double> topicMaxPercent = new HashMap<>();

      for (PartitionLag p : group.partitions()) {
        long retentionWindow = p.logEndOffset() - p.logStartOffset();

        // Skip empty partitions (no messages to lose)
        if (retentionWindow <= 0) {
          continue;
        }

        double percent;
        if (p.committedOffset() < p.logStartOffset()) {
          // Consumer is behind log start - data loss already occurred
          percent = 100.0;
        } else if (p.lag() <= 0) {
          // Consumer caught up
          percent = 0.0;
        } else {
          percent = (p.lag() / (double) retentionWindow) * 100.0;
        }

        topicMaxPercent.merge(p.topic(), percent, Math::max);
      }

      // Create RetentionRisk for each topic
      for (var entry : topicMaxPercent.entrySet()) {
        risks.add(new RetentionRisk(group.consumerGroup(), entry.getKey(), entry.getValue()));
        log.debug("Retention risk for {}:{}: {:.2f}%",
          group.consumerGroup(), entry.getKey(), entry.getValue());
      }
    }

    if (!risks.isEmpty()) {
      log.debug("Calculated {} retention risk metrics", risks.size());
    }

    return risks;
  }

  /**
   * Calculates lag in milliseconds using interpolation from recorded offset/timestamp history.
   * For each partition, records the current log end offset, then interpolates the timestamp
   * for the committed offset to determine how old the unconsumed messages are.
   *
   * <p>This approach uses system time instead of Kafka message timestamps, avoiding issues
   * where Kafka doesn't provide logStartTimestamp (returns 0).
   *
   * @param lagData list of consumer group lag data
   * @return list of lag in milliseconds per consumer group and topic
   */
  private List<LagMs> calculateLagMs(List<ConsumerGroupLag> lagData) {
    if (offsetTimestampTracker == null) {
      return List.of();
    }

    List<LagMs> lagMsList = new ArrayList<>();
    int skippedDueToWarmup = 0;
    long currentTime = System.currentTimeMillis();
    Set<String> trackedPartitions = new HashSet<>();

    for (ConsumerGroupLag group : lagData) {
      // First pass: record all partition offsets to the tracker
      for (PartitionLag p : group.partitions()) {
        offsetTimestampTracker.recordOffset(p.topic(), p.partition(), p.logEndOffset());
        trackedPartitions.add(p.topic() + ":" + p.partition());
      }

      // Second pass: aggregate lag_ms by topic using max lag_ms across partitions
      Map<String, TopicLagMsAggregates> topicAggregates = new HashMap<>();

      for (PartitionLag p : group.partitions()) {
        // Consumer is caught up - no lag
        if (p.lag() <= 0) {
          topicAggregates.computeIfAbsent(p.topic(), k -> new TopicLagMsAggregates())
            .add(0, p.lag());
          continue;
        }

        // Check if we have interpolation data for this partition
        if (!offsetTimestampTracker.hasInterpolationData(p.topic(), p.partition())) {
          skippedDueToWarmup++;
          log.trace("Skipping lag_ms for {}:{}:{}: insufficient interpolation data (warmup)",
            group.consumerGroup(), p.topic(), p.partition());
          continue;
        }

        // Interpolate timestamp for the committed offset
        var interpolatedTs = offsetTimestampTracker.getInterpolatedTimestamp(
          p.topic(), p.partition(), p.committedOffset());

        if (interpolatedTs.isPresent()) {
          long lagMs = Math.max(0, currentTime - interpolatedTs.getAsLong());
          topicAggregates.computeIfAbsent(p.topic(), k -> new TopicLagMsAggregates())
            .add(lagMs, p.lag());
          log.trace("Partition {}:{}:{} lag_ms={} (committed={}, interpolated_ts={})",
            group.consumerGroup(), p.topic(), p.partition(), lagMs,
            p.committedOffset(), interpolatedTs.getAsLong());
        } else {
          skippedDueToWarmup++;
        }
      }

      // Create LagMs records for topics that have data
      for (Map.Entry<String, TopicLagMsAggregates> entry : topicAggregates.entrySet()) {
        String topic = entry.getKey();
        TopicLagMsAggregates agg = entry.getValue();

        if (agg.hasData()) {
          lagMsList.add(new LagMs(group.consumerGroup(), topic, agg.totalLag(), agg.maxLagMs()));
          log.debug("Lag in ms for {}:{}: {} ms (lag_messages={}, partitions_sampled={})",
            group.consumerGroup(), topic, agg.maxLagMs(), agg.totalLag(), agg.count());
        }
      }
    }

    // Cleanup stale partitions
    offsetTimestampTracker.cleanupStalePartitions(trackedPartitions);

    if (skippedDueToWarmup > 0) {
      log.debug("Skipped {} partitions due to warmup (insufficient interpolation data)", skippedDueToWarmup);
    }
    if (!lagMsList.isEmpty()) {
      log.info("Calculated lag_ms for {} consumer-group/topic pairs using interpolation", lagMsList.size());
    }

    return lagMsList;
  }

  /**
   * Helper class for topic-level lag_ms aggregation.
   * Uses max lag_ms across partitions as the topic-level value.
   */
  private static class TopicLagMsAggregates {
    private long maxLagMs = 0;
    private long totalLag = 0;
    private int count = 0;

    void add(long lagMs, long lag) {
      if (lagMs > maxLagMs) {
        maxLagMs = lagMs;
      }
      totalLag += lag;
      count++;
    }

    boolean hasData() {
      return count > 0;
    }

    long maxLagMs() {
      return maxLagMs;
    }

    long totalLag() {
      return totalLag;
    }

    int count() {
      return count;
    }
  }

  /**
   * Helper class for topic-level aggregation.
   */
  private static class TopicAggregates {
    private long totalLogEndOffset = 0;
    private long totalCommittedOffset = 0;
    private long totalLag = 0;

    void add(long logEndOffset, long committedOffset, long lag) {
      this.totalLogEndOffset += logEndOffset;
      this.totalCommittedOffset += committedOffset;
      this.totalLag += lag;
    }

    long totalLogEndOffset() { return totalLogEndOffset; }
    long totalCommittedOffset() { return totalCommittedOffset; }
    long totalLag() { return totalLag; }
  }
}
