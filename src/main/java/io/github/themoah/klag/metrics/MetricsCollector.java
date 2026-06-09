package io.github.themoah.klag.metrics;

import io.github.themoah.klag.kafka.ChunkConfig;
import io.github.themoah.klag.kafka.ChunkProcessor;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionDetector;
import io.github.themoah.klag.metrics.snapshot.SnapshotBuilder;
import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.metrics.timelag.LagMsCalculator;
import io.github.themoah.klag.metrics.timelag.OffsetTimestampTracker;
import io.github.themoah.klag.metrics.timelag.TimeLagConfig;
import io.github.themoah.klag.metrics.timelag.TimeLagEstimator;
import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.StateTransition;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
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
  private final GroupFilter groupFilter;
  private final LagVelocityTracker velocityTracker;
  private final HotPartitionDetector hotPartitionDetector;
  private final TimeLagEstimator timeLagEstimator;
  private final OffsetTimestampTracker offsetTimestampTracker;
  private final ChunkConfig chunkConfig;

  private final Map<String, Integer> cachedGroupPartitionCounts = new ConcurrentHashMap<>();
  private final Map<String, Integer> cachedTopicPartitionCounts = new ConcurrentHashMap<>();

  // Per-cycle cache of topic offset futures: N groups consuming the same topic share one
  // describeTopics + listOffsets round instead of issuing N identical queries per cycle.
  // Cleared at the start of each cycle; cycles never overlap (in-flight guard below).
  private final Map<String, Future<List<PartitionOffsets>>> cycleTopicOffsets =
      new ConcurrentHashMap<>();

  // Guards against overlapping collection cycles when a cycle exceeds the interval
  // (large clusters, chunk delays). Only touched on the Vert.x event loop.
  private boolean collectionInFlight;

  // Optional snapshot store for the MCP layer. When set, the collector publishes its
  // last cycle into it (best-effort, never affecting collection). Null = MCP disabled.
  private SnapshotStore snapshotStore;

  // STABLE band (msg/s) for classifying lag velocity into a basic trend in the MCP snapshot.
  private double lagTrendDeadband = 1.0;

  private Long timerId;

  public MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MetricsReporter reporter,
    long intervalMs,
    String groupFilter
  ) {
    this(vertx, kafkaClient, reporter, intervalMs, groupFilter, "",
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
    this(vertx, kafkaClient, reporter, intervalMs, groupFilter, "",
      new LagVelocityTracker(), hotPartitionConfig,
      TimeLagConfig.fromEnvironment(), ChunkConfig.fromEnvironment());
  }

  public MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MetricsReporter reporter,
    long intervalMs,
    String groupFilter,
    String groupExclude,
    HotPartitionConfig hotPartitionConfig
  ) {
    this(vertx, kafkaClient, reporter, intervalMs, groupFilter, groupExclude,
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
    String groupExclude,
    LagVelocityTracker velocityTracker,
    HotPartitionConfig hotPartitionConfig,
    TimeLagConfig timeLagConfig,
    ChunkConfig chunkConfig
  ) {
    this.vertx = vertx;
    this.kafkaClient = kafkaClient;
    this.reporter = reporter;
    this.intervalMs = intervalMs;
    this.groupFilter = new GroupFilter(groupFilter, groupExclude);
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
   * Attaches a snapshot store. After each collection cycle the collector publishes its
   * derived metrics into this store for the MCP layer to read. Publishing is best-effort
   * and never affects collection. Pass null to disable.
   *
   * @param snapshotStore the store to publish to, or null
   */
  public void setSnapshotStore(SnapshotStore snapshotStore) {
    this.snapshotStore = snapshotStore;
  }

  /**
   * Sets the STABLE deadband (msg/s) used to classify lag velocity into a basic trend for the
   * MCP snapshot. Defaults to 1.0.
   *
   * @param lagTrendDeadband the deadband magnitude in messages/second
   */
  public void setLagTrendDeadband(double lagTrendDeadband) {
    this.lagTrendDeadband = lagTrendDeadband;
  }

  /**
   * Runs a single collection-and-report cycle. Exposed for tests.
   *
   * @return future completing when the cycle finishes
   */
  Future<Void> collectOnce() {
    return collectAndReport();
  }

  /**
   * Starts the metrics collector with periodic collection.
   */
  public Future<Void> start() {
    log.info("Starting metrics collector with interval: {}ms, filter: {}, exclude: {}",
      intervalMs, groupFilter.includeDescription(), groupFilter.excludeDescription());

    return reporter.start()
      .compose(v -> collectAndReport())
      .onComplete(ar -> {
        timerId = vertx.setPeriodic(intervalMs, id -> {
          if (collectionInFlight) {
            log.warn("Skipping collection tick: previous cycle still running "
              + "(METRICS_INTERVAL_MS={} may be too short for this cluster)", intervalMs);
            return;
          }
          collectAndReport();
        });
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
    collectionInFlight = true;
    cycleTopicOffsets.clear();

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
      .onFailure(err -> log.error("Failed to collect lag metrics", err))
      .onComplete(ar -> collectionInFlight = false);
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
        Set<String> throughputKeys = new HashSet<>();
        CycleSnapshot cycleSnapshot = newCycleSnapshot();
        reportMetrics(lagData, stateData, activeKeys, velocityKeys, throughputKeys, cycleSnapshot);
        velocityTracker.cleanupStaleTopics(velocityKeys);
        if (hotPartitionDetector != null && hotPartitionDetector.isEnabled()) {
          hotPartitionDetector.cleanupStalePartitions(throughputKeys);
        }
        if (reporter instanceof MicrometerReporter micrometerReporter) {
          micrometerReporter.cleanupStaleGauges(activeKeys);
        }
        publishSnapshot(cycleSnapshot);
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
    Set<String> cycleThroughputKeys = new HashSet<>();
    CycleSnapshot cycleSnapshot = newCycleSnapshot();

    return ChunkProcessor.<String, Void>processSequentially(
      vertx, groupChunks, chunkConfig.chunkDelayMs(),
      chunk -> processGroupChunk(chunk, cycleActiveKeys, cycleVelocityKeys,
        cycleThroughputKeys, cycleSnapshot)
    ).compose(results -> {
      velocityTracker.cleanupStaleTopics(cycleVelocityKeys);
      if (hotPartitionDetector != null && hotPartitionDetector.isEnabled()) {
        hotPartitionDetector.cleanupStalePartitions(cycleThroughputKeys);
      }
      if (reporter instanceof MicrometerReporter micrometerReporter) {
        micrometerReporter.cleanupStaleGauges(cycleActiveKeys);
      }
      publishSnapshot(cycleSnapshot);
      return Future.succeededFuture();
    });
  }

  /**
   * Processes a single chunk of consumer groups: collects lag, describes groups, reports metrics.
   */
  private Future<Void> processGroupChunk(
      List<String> chunk,
      Set<String> cycleActiveKeys,
      Set<String> cycleVelocityKeys,
      Set<String> cycleThroughputKeys,
      CycleSnapshot cycleSnapshot
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

        reportMetrics(lagData, stateData, cycleActiveKeys, cycleVelocityKeys,
          cycleThroughputKeys, cycleSnapshot);

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
              .map(this::getLogEndOffsetsCached)
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
      // Same recovery as collectGroupLag: a single failed group must not abort the chunk.
      .recover(err -> {
        log.warn("Failed to collect lag for group {} (skipped this cycle): {}",
          groupId, err.getMessage());
        return Future.succeededFuture(null);
      });
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
   * Velocity and throughput keys are accumulated across chunks for cycle-level cleanup.
   */
  private void reportMetrics(
      List<ConsumerGroupLag> lagData,
      Map<String, ConsumerGroupState> stateData,
      Set<String> activeKeys,
      Set<String> velocityKeys,
      Set<String> throughputKeys,
      CycleSnapshot cycleSnapshot
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
          velocityKeys.add(LagVelocityTracker.makeKey(consumerGroup, topic));
        })
      );

      // Calculate and report velocities
      List<LagVelocity> velocities = velocityTracker.calculateVelocities();
      micrometerReporter.reportVelocity(velocities, activeKeys);

      // Calculate lag in ms from timestamps
      List<LagMs> lagMsData = calculateLagMs(lagData);
      micrometerReporter.reportLagMs(lagMsData, activeKeys);

      // Time-to-close estimation (based on velocity data)
      List<TimeToCloseEstimate> timeToCloseEstimates = List.of();
      if (timeLagEstimator != null && timeLagEstimator.isEnabled()) {
        // Build lag map: group -> topic -> totalLag
        Map<String, Map<String, Long>> lagByGroupTopic = new HashMap<>();
        groupTopicAggregates.forEach((group, topicMap) ->
          topicMap.forEach((topic, agg) ->
            lagByGroupTopic.computeIfAbsent(group, k -> new HashMap<>())
              .put(topic, agg.totalLag())
          )
        );

        timeToCloseEstimates = timeLagEstimator.calculateTimeToClose(velocities, lagByGroupTopic);
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
      List<HotPartitionLag> hotByLag = List.of();
      List<HotPartitionThroughput> hotByThroughput = List.of();
      if (hotPartitionDetector != null && hotPartitionDetector.isEnabled()) {
        // Accumulate active throughput keys across chunks; cleanup happens once per
        // cycle (see collectAndReportChunked / collectAllGroupsParallel). Cleaning up
        // here per-chunk would retainAll() away other chunks' throughput histories.
        throughputKeys.addAll(hotPartitionDetector.recordThroughputSnapshots(lagData));

        hotByLag = hotPartitionDetector.detectHotPartitionsByLag(lagData);
        micrometerReporter.reportHotPartitionLag(hotByLag, activeKeys);

        hotByThroughput = hotPartitionDetector.detectHotPartitionsByThroughput();
        micrometerReporter.reportHotPartitionThroughput(hotByThroughput, activeKeys);
      }

      // Accumulate this call's derived metrics into the cycle snapshot for the MCP layer.
      if (cycleSnapshot != null) {
        Map<String, List<StateTransition>> transitionsByGroup = new HashMap<>();
        for (ConsumerGroupLag lag : lagData) {
          transitionsByGroup.put(lag.consumerGroup(),
            micrometerReporter.recentStateTransitions(lag.consumerGroup()));
        }
        MetricsSnapshot partial = SnapshotBuilder.build(0L, lagData, stateData, velocities,
          lagMsData, timeToCloseEstimates, retentionRisks, hotByLag, hotByThroughput,
          transitionsByGroup, lagTrendDeadband);
        cycleSnapshot.groups.addAll(partial.groups());
        cycleSnapshot.throughput.addAll(hotByThroughput);
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
          .map(this::getLogEndOffsetsCached)
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
      // Recover to null so one failing group (deleted mid-cycle, coordinator hiccup, ACL gap)
      // is skipped by the null-filter in collectAllGroupLags instead of failing Future.all
      // and aborting the whole cycle for every other group.
      .recover(err -> {
        log.warn("Failed to collect lag for group {} (skipped this cycle): {}",
          groupId, err.getMessage());
        return Future.succeededFuture(null);
      });
  }

  /**
   * Per-cycle cached variant of {@link KafkaClientService#getLogEndOffsets(String)}.
   * Multiple groups consuming the same topic reuse one in-flight (or completed) future.
   */
  private Future<List<PartitionOffsets>> getLogEndOffsetsCached(String topic) {
    return cycleTopicOffsets.computeIfAbsent(topic, kafkaClient::getLogEndOffsets);
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
          po.maxTimestampOffset(),
          po.logStartTimestamp(),
          committedOffset
        );
        partitionLags.add(lag);
      }
    }

    return ConsumerGroupLag.fromPartitions(groupId, partitionLags);
  }

  private boolean matchesFilter(String groupId) {
    return groupFilter.matches(groupId);
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
        if (log.isDebugEnabled()) {
          log.debug("Retention risk for {}:{}: {}%",
            group.consumerGroup(), entry.getKey(), String.format("%.2f", entry.getValue()));
        }
      }
    }

    if (!risks.isEmpty()) {
      log.debug("Calculated {} retention risk metrics", risks.size());
    }

    return risks;
  }

  /**
   * Calculates lag in milliseconds per consumer group and topic.
   *
   * <p>Primary path: linear interpolation between Kafka {@code listOffsets} log start/end
   * timestamps. Fallback: poll-time {@code (logEndOffset, systemTime)} history when Kafka
   * timestamps are unavailable; fallback does not extrapolate beyond the oldest retained sample.
   *
   * @param lagData list of consumer group lag data
   * @return list of lag in milliseconds per consumer group and topic
   */
  private List<LagMs> calculateLagMs(List<ConsumerGroupLag> lagData) {
    if (offsetTimestampTracker == null) {
      return List.of();
    }

    List<LagMs> lagMsList = new ArrayList<>();
    int skippedPartitions = 0;
    long currentTime = System.currentTimeMillis();
    Set<String> trackedPartitions = new HashSet<>();

    for (ConsumerGroupLag group : lagData) {
      for (PartitionLag p : group.partitions()) {
        offsetTimestampTracker.recordOffset(p.topic(), p.partition(), p.logEndOffset());
        trackedPartitions.add(p.topic() + ":" + p.partition());
      }

      Map<String, TopicLagMsAggregates> topicAggregates = new HashMap<>();

      for (PartitionLag p : group.partitions()) {
        var lagMs = LagMsCalculator.estimatePartitionLagMs(p, offsetTimestampTracker, currentTime);

        if (lagMs.isPresent()) {
          topicAggregates.computeIfAbsent(p.topic(), k -> new TopicLagMsAggregates())
            .add(lagMs.getAsLong(), p.lag());
          log.trace("Partition {}:{}:{} lag_ms={} (committed={})",
            group.consumerGroup(), p.topic(), p.partition(), lagMs.getAsLong(), p.committedOffset());
        } else if (p.lag() > 0) {
          skippedPartitions++;
          log.trace("Skipping lag_ms for {}:{}:{}: no Kafka anchors and insufficient poll history",
            group.consumerGroup(), p.topic(), p.partition());
        }
      }

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

    offsetTimestampTracker.cleanupStalePartitions(trackedPartitions);

    if (skippedPartitions > 0) {
      log.debug("Skipped {} partitions with no lag_ms estimate", skippedPartitions);
    }
    if (!lagMsList.isEmpty()) {
      log.info("Calculated lag_ms for {} consumer-group/topic pairs", lagMsList.size());
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
   * Mutable per-cycle accumulator of derived metrics destined for the MCP snapshot.
   * Across chunked collection it gathers groups from every chunk before a single publish.
   */
  private static class CycleSnapshot {
    final List<GroupSnapshot> groups = new ArrayList<>();
    final List<HotPartitionThroughput> throughput = new ArrayList<>();
  }

  /**
   * @return a fresh accumulator when a snapshot store is attached, else null (no work done).
   */
  private CycleSnapshot newCycleSnapshot() {
    return snapshotStore != null ? new CycleSnapshot() : null;
  }

  /**
   * Publishes the accumulated cycle into the snapshot store. Best-effort: any failure is
   * logged and swallowed so it can never disrupt metrics collection or reporting.
   *
   * @param cycleSnapshot the accumulated cycle, or null when no store is attached
   */
  private void publishSnapshot(CycleSnapshot cycleSnapshot) {
    if (snapshotStore == null || cycleSnapshot == null) {
      return;
    }
    try {
      snapshotStore.set(new MetricsSnapshot(
        System.currentTimeMillis(),
        List.copyOf(cycleSnapshot.groups),
        List.copyOf(cycleSnapshot.throughput)));
    } catch (RuntimeException e) {
      log.warn("Failed to publish MCP snapshot (collection unaffected): {}", e.getMessage());
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
