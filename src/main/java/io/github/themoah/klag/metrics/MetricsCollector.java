package io.github.themoah.klag.metrics;

import io.github.themoah.klag.config.Env;
import io.github.themoah.klag.kafka.ChunkConfig;
import io.github.themoah.klag.kafka.ChunkProcessor;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.metrics.freshness.CommitFreshnessConfig;
import io.github.themoah.klag.metrics.freshness.CommitFreshnessTracker;
import io.github.themoah.klag.metrics.dataskew.DataSkewConfig;
import io.github.themoah.klag.metrics.dataskew.DataSkewDetector;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionDetector;
import io.github.themoah.klag.metrics.snapshot.SnapshotBuilder;
import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.metrics.timelag.LagMsCalculator;
import io.github.themoah.klag.metrics.timelag.OffsetTimestampTracker;
import io.github.themoah.klag.metrics.timelag.TimeLagConfig;
import io.github.themoah.klag.metrics.timelag.TimeLagEstimator;
import io.github.themoah.klag.metrics.velocity.LagVelocityTracker;
import io.github.themoah.klag.model.CommitStaleness;
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
import io.github.themoah.klag.model.MemberAssignment;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.PartitionOffsets;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import io.github.themoah.klag.model.TopicSizeSkew;
import io.github.themoah.klag.model.UnderReplicatedPartition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.Collection;
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

  // Caps how many groups have a committed-offset request in flight at once. Purely a
  // concurrency bound, not a throttle — see fetchGroupOffsets.
  private static final String ENV_MAX_CONCURRENT_GROUPS = "KAFKA_MAX_CONCURRENT_GROUPS";
  private static final int DEFAULT_MAX_CONCURRENT_GROUPS = 50;

  private final Vertx vertx;
  private final KafkaClientService kafkaClient;
  private final MicrometerReporter reporter;
  private final long intervalMs;
  private final GroupFilter groupFilter;
  private final LagVelocityTracker velocityTracker;
  private final HotPartitionDetector hotPartitionDetector;
  private final TimeLagEstimator timeLagEstimator;
  private final OffsetTimestampTracker offsetTimestampTracker;
  private final CommitFreshnessTracker commitFreshnessTracker;  // null when disabled
  private final boolean isrEnabled;
  private final DataSkewConfig dataSkewConfig;
  private final ChunkConfig chunkConfig;
  private final int maxConcurrentGroups;

  private final Map<String, Integer> cachedGroupPartitionCounts = new ConcurrentHashMap<>();
  private final Map<String, Integer> cachedTopicPartitionCounts = new ConcurrentHashMap<>();

  // Per-cycle cache of topic offset futures: N groups consuming the same topic share one
  // describeTopics + listOffsets round instead of issuing N identical queries per cycle.
  // Cleared at the start of each cycle; cycles never overlap (in-flight guard below).
  private final Map<String, Future<List<PartitionOffsets>>> cycleTopicOffsets =
      new ConcurrentHashMap<>();

  // Per-cycle cache of the cluster's topic names, used to drop deleted topics before
  // describeTopics sees them. One list call per cycle, not per chunk. Cleared with
  // cycleTopicOffsets; a failed lookup stays cached so the cycle fails once, not per chunk.
  private Future<Set<String>> cycleTopicNames;

  // Guards against overlapping collection cycles when a cycle exceeds the interval
  // (large clusters, chunk delays). Only touched on the Vert.x event loop.
  private boolean collectionInFlight;

  // Optional snapshot store for the MCP layer. When set, the collector publishes its
  // last cycle into it (best-effort, never affecting collection). Null = MCP disabled.
  private SnapshotStore snapshotStore;

  // STABLE band (msg/s) for classifying lag velocity into a basic trend in the MCP snapshot.
  private double lagTrendDeadband = 1.0;

  private Long timerId;

  private String clusterLog() {
    String name = reporter.clusterName();
    return name == null || name.isBlank() ? "" : " [cluster=" + name + "]";
  }

  public MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MicrometerReporter reporter,
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
    MicrometerReporter reporter,
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
    MicrometerReporter reporter,
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
    MicrometerReporter reporter,
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
    this.commitFreshnessTracker = CommitFreshnessConfig.fromEnvironment().enabled()
      ? new CommitFreshnessTracker()
      : null;
    this.isrEnabled = IsrConfig.fromEnvironment().enabled();
    this.dataSkewConfig = DataSkewConfig.fromEnvironment();
    this.chunkConfig = chunkConfig;
    this.maxConcurrentGroups = Math.max(1,
      Env.getInt(ENV_MAX_CONCURRENT_GROUPS, DEFAULT_MAX_CONCURRENT_GROUPS));
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
    log.info("Starting metrics collector{} with interval: {}ms, filter: {}, exclude: {}",
      clusterLog(), intervalMs, groupFilter.includeDescription(), groupFilter.excludeDescription());

    // The first cycle is recovered: a broker that is down at boot must degrade (like
    // KafkaHealthMonitor) instead of failing verticle startup, which exits the process
    // and crash-loops under Kubernetes. collectAndReport already logs the cause.
    return reporter.start()
      .compose(v -> collectAndReport().recover(err -> Future.succeededFuture()))
      .onComplete(ar -> {
        timerId = vertx.setPeriodic(intervalMs, id -> {
          if (collectionInFlight) {
            log.warn("Skipping collection tick{}: previous cycle still running "
              + "(METRICS_INTERVAL_MS={} may be too short for this cluster)",
              clusterLog(), intervalMs);
            return;
          }
          collectAndReport();
        });
        log.info("Metrics collector started{}, timer ID: {}", clusterLog(), timerId);
      })
      .mapEmpty();
  }

  /**
   * Stops the metrics collector.
   */
  public Future<Void> stop() {
    log.info("Stopping metrics collector{}", clusterLog());
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
    cycleTopicNames = null;
    long cycleStartNanos = System.nanoTime();

    return kafkaClient.listConsumerGroups()
      .compose(groups -> {
        Set<String> filteredGroups = groups.stream()
          .filter(this::matchesFilter)
          .collect(Collectors.toSet());

        log.debug("Found {} consumer groups, {} after filtering",
          groups.size(), filteredGroups.size());

        if (filteredGroups.isEmpty()) {
          // Same cycle-end path as a normal cycle, with empty key sets: every tracker
          // drains and the MCP snapshot refreshes to empty instead of staying frozen
          // at the last non-empty cycle.
          finishCycle(new CycleState(newCycleSnapshot()));
          return Future.succeededFuture();
        }

        if (chunkConfig.isChunkingEnabled()) {
          return collectAndReportChunked(filteredGroups);
        }

        return collectAllGroupsParallel(filteredGroups);
      })
      .onFailure(err -> log.error("Failed to collect lag metrics{}", clusterLog(), err))
      .onComplete(ar -> {
        collectionInFlight = false;
        long cycleMs = (System.nanoTime() - cycleStartNanos) / 1_000_000L;
        log.info("Collection cycle finished{} in {}ms (success={})",
          clusterLog(), cycleMs, ar.succeeded());
      });
  }

  /**
   * Original non-chunked path: collects all groups in parallel.
   */
  private Future<Void> collectAllGroupsParallel(Set<String> filteredGroups) {
    CycleState cycle = new CycleState(newCycleSnapshot());
    Future<List<ConsumerGroupLag>> lagFuture = collectGroupLags(filteredGroups, cycle);
    Future<Map<String, ConsumerGroupState>> stateFuture =
        kafkaClient.describeConsumerGroups(filteredGroups);

    return Future.all(lagFuture, stateFuture)
      .map(composite -> {
        List<ConsumerGroupLag> lagData = composite.resultAt(0);
        Map<String, ConsumerGroupState> stateData = composite.resultAt(1);

        reportMetrics(lagData, stateData, cycle);
        finishCycle(cycle);
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

    CycleState cycle = new CycleState(newCycleSnapshot());

    return ChunkProcessor.<String, Void>processSequentially(
      vertx, groupChunks, chunkConfig.chunkDelayMs(),
      chunk -> processGroupChunk(chunk, cycle)
    ).compose(results -> {
      finishCycle(cycle);
      return Future.succeededFuture();
    });
  }

  /**
   * Processes a single chunk of consumer groups: collects lag, describes groups, reports metrics.
   *
   * <p>Recovers on failure so one failed chunk neither aborts the remaining chunks nor fails
   * the cycle; the cycle is marked partial instead (see {@link #finishCycle(CycleState)}).
   */
  private Future<Void> processGroupChunk(List<String> chunk, CycleState cycle) {
    log.debug("Processing group chunk with {} groups", chunk.size());

    Future<List<ConsumerGroupLag>> lagFuture = collectGroupLags(chunk, cycle);
    Future<Map<String, ConsumerGroupState>> stateFuture =
        kafkaClient.describeConsumerGroups(new HashSet<>(chunk));

    return Future.all(lagFuture, stateFuture)
      .<Void>map(composite -> {
        List<ConsumerGroupLag> lagData = composite.resultAt(0);
        Map<String, ConsumerGroupState> stateData = composite.resultAt(1);

        reportMetrics(lagData, stateData, cycle);

        // Update cached group partition counts
        for (ConsumerGroupLag lag : lagData) {
          cachedGroupPartitionCounts.put(lag.consumerGroup(), lag.partitions().size());
        }

        return null;
      })
      .recover(err -> {
        cycle.partial = true;
        log.warn("Failed to process group chunk of {} groups{} (skipped this cycle): {}",
          chunk.size(), clusterLog(), err.getMessage());
        return Future.succeededFuture(null);
      });
  }

  /**
   * Cycle-end cleanup and snapshot publish, called exactly once per collection cycle.
   *
   * <p>All stale-entry cleanups live here (not per chunk) because every cleanup is a
   * retainAll against the keys observed in the whole cycle: running any of them with a
   * chunk-local subset wipes the accumulated state of every other chunk.
   *
   * <p>Partial cycles (a chunk or a single group failed) skip cleanup: the failed keys are
   * missing from the accumulators, and cleaning up against an incomplete key set would mark
   * or delete live series. Stale values beat deleted series. Note this means a permanently
   * failing group (ACL gap, wedged coordinator) freezes cleanup indefinitely — the WARN below
   * names the group; fix its ACL or exclude it via METRICS_GROUP_EXCLUDE.
   *
   * <p>The MCP snapshot still publishes on a partial cycle, because freezing it would leave
   * agents reading hours-old data while /metrics stays current. The exception is an empty
   * snapshot: nothing was collected at all, and wiping the agent view is worse than a stale one.
   */
  private void finishCycle(CycleState cycle) {
    if (cycle.partial) {
      log.warn("Collection cycle was partial{} (at least one chunk or group failed); "
        + "keeping previous metrics and skipping stale cleanup until a full cycle succeeds",
        clusterLog());
      if (cycle.snapshot != null && !cycle.snapshot.groups.isEmpty()) {
        publishSnapshot(cycle.snapshot);
      }
      return;
    }
    velocityTracker.cleanupStaleTopics(cycle.velocityKeys);
    if (hotPartitionDetector != null && hotPartitionDetector.isEnabled()) {
      hotPartitionDetector.cleanupStalePartitions(cycle.throughputKeys);
    }
    if (offsetTimestampTracker != null) {
      offsetTimestampTracker.cleanupStalePartitions(cycle.timeLagKeys);
    }
    if (commitFreshnessTracker != null) {
      commitFreshnessTracker.cleanupStale(cycle.commitStalenessKeys);
    }
    reporter.cleanupStaleGauges(cycle.activeKeys);
    reporter.cleanupStateTracker(cycle.stateGroupKeys);
    publishSnapshot(cycle.snapshot);
  }

  /**
   * Collects lag for a set of groups in two phases.
   *
   * <p>Phase 1 fetches every group's committed offsets (bounded fan-out). Phase 2 takes the
   * union of the topics those groups consume and resolves all of them in a single batched
   * call. That batching is the point: fetching offsets per topic cost four admin requests
   * per topic per cycle, so a few hundred topics meant hundreds of round-trips and cycles
   * that overran the interval. The batched fetch costs four for the whole set.
   *
   * <p>Phase 3 is pure assembly — no I/O — because every group's topics are already resolved.
   */
  private Future<List<ConsumerGroupLag>> collectGroupLags(
      Collection<String> groups, CycleState cycle) {
    return fetchGroupOffsets(groups, cycle)
      .compose(offsetsByGroup -> {
        Set<String> topics = offsetsByGroup.values().stream()
          .flatMap(offsets -> offsets.offsets().keySet().stream())
          .map(TopicPartitionKey::topic)
          .collect(Collectors.toSet());

        return fetchTopicOffsets(topics).map(topicOffsets -> offsetsByGroup.entrySet().stream()
          .map(entry -> buildConsumerGroupLag(entry.getKey(), entry.getValue(), topicOffsets))
          .collect(Collectors.toList()));
      });
  }

  /**
   * Reports metrics for the given lag and state data.
   * Accumulates all observed keys into the cycle state but does NOT perform cleanup:
   * with chunking this method runs once per chunk, and every cleanup is a retainAll
   * that must only see the full cycle's keys (see {@link #finishCycle(CycleState)}).
   */
  private void reportMetrics(
      List<ConsumerGroupLag> lagData,
      Map<String, ConsumerGroupState> stateData,
      CycleState cycle
  ) {
    Set<String> activeKeys = cycle.activeKeys;
    Set<String> velocityKeys = cycle.velocityKeys;
    Set<String> throughputKeys = cycle.throughputKeys;
    CycleSnapshot cycleSnapshot = cycle.snapshot;
    cycle.stateGroupKeys.addAll(stateData.keySet());
    // Member ownership rides along on stateData (same describeConsumerGroups call) so
    // consumer-owned partition series can carry member labels.
    Map<String, Map<TopicPartitionKey, MemberAssignment>> partitionOwners = new HashMap<>();
    stateData.forEach((group, s) -> partitionOwners.put(group, s.partitionOwners()));

    // Report topic partition counts (max partition number + 1)
    Map<String, Integer> topicPartitions = new HashMap<>();
    for (ConsumerGroupLag group : lagData) {
      for (PartitionLag p : group.partitions()) {
        topicPartitions.merge(p.topic(), p.partition() + 1, Integer::max);
      }
    }

    // Under-replicated partition (ISR) detection, from data already fetched this cycle.
    List<UnderReplicatedPartition> underReplicated = isrEnabled
      ? calculateUnderReplicatedPartitions(topicPartitions.keySet())
      : List.of();
    if (!underReplicated.isEmpty()) {
      reporter.reportUnderReplicatedPartitions(underReplicated, activeKeys);
    }

    List<TopicSizeSkew> sizeSkews = dataSkewConfig.enabled()
      ? calculateTopicSizeSkew(topicPartitions.keySet())
      : List.of();
    if (!sizeSkews.isEmpty()) {
      reporter.reportTopicSizeSkew(sizeSkews, activeKeys);
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

    // Commit freshness: track when each group+topic last advanced its committed offset.
    // Staleness is only reported when lag > 0 (a frozen-but-idle consumer is not stuck).
    Map<String, Long> stalenessByGroup = new HashMap<>();
    if (commitFreshnessTracker != null) {
      long now = System.currentTimeMillis();
      List<CommitStaleness> stalenessData = new ArrayList<>();
      for (var groupEntry : groupTopicAggregates.entrySet()) {
        String consumerGroup = groupEntry.getKey();
        for (var topicEntry : groupEntry.getValue().entrySet()) {
          String topic = topicEntry.getKey();
          TopicAggregates agg = topicEntry.getValue();
          commitFreshnessTracker.record(consumerGroup, topic, agg.totalCommittedOffset(), now);
          if (agg.totalLag() > 0) {
            commitFreshnessTracker.stalenessSeconds(consumerGroup, topic, now).ifPresent(seconds -> {
              stalenessData.add(new CommitStaleness(consumerGroup, topic, seconds, agg.totalLag()));
              cycle.commitStalenessKeys.add(LagVelocityTracker.makeKey(consumerGroup, topic));
              stalenessByGroup.merge(consumerGroup, seconds, Math::max);
            });
          }
        }
      }
      reporter.reportCommitStaleness(stalenessData, activeKeys);
    }

    // Calculate and report velocities
    List<LagVelocity> velocities = velocityTracker.calculateVelocities();
    reporter.reportVelocity(velocities, activeKeys);

    // Calculate lag in ms from timestamps
    List<LagMs> lagMsData = calculateLagMs(lagData, cycle.timeLagKeys);
    reporter.reportLagMs(lagMsData, partitionOwners, activeKeys);

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
      reporter.reportTimeToClose(timeToCloseEstimates, activeKeys);
    }

    // Retention risk percentage calculation (offset-based)
    List<RetentionRisk> retentionRisks = calculateRetentionRisks(lagData);
    if (!retentionRisks.isEmpty()) {
      reporter.reportRetentionPercent(retentionRisks, activeKeys);
    }

    // Report lag and state metrics.
    reporter.reportTopicPartitions(topicPartitions, activeKeys);
    reporter.reportLag(lagData, partitionOwners, activeKeys);
    reporter.reportConsumerGroupStates(stateData, activeKeys);

    // Hot partition detection and reporting
    List<HotPartitionLag> hotByLag = List.of();
    List<HotPartitionThroughput> hotByThroughput = List.of();
    if (hotPartitionDetector != null && hotPartitionDetector.isEnabled()) {
      // Accumulate active throughput keys across chunks; cleanup happens once per
      // cycle (see collectAndReportChunked / collectAllGroupsParallel). Cleaning up
      // here per-chunk would retainAll() away other chunks' throughput histories.
      throughputKeys.addAll(hotPartitionDetector.recordThroughputSnapshots(lagData));

      hotByLag = hotPartitionDetector.detectHotPartitionsByLag(lagData);
      reporter.reportHotPartitionLag(hotByLag, activeKeys);

      hotByThroughput = hotPartitionDetector.detectHotPartitionsByThroughput();
      reporter.reportHotPartitionThroughput(hotByThroughput, activeKeys);
    }

    // Accumulate this call's derived metrics into the cycle snapshot for the MCP layer.
    if (cycleSnapshot != null) {
      Map<String, List<StateTransition>> transitionsByGroup = new HashMap<>();
      for (ConsumerGroupLag lag : lagData) {
        transitionsByGroup.put(lag.consumerGroup(),
          reporter.recentStateTransitions(lag.consumerGroup()));
      }
      MetricsSnapshot partial = SnapshotBuilder.build(0L, lagData, stateData, velocities,
        lagMsData, timeToCloseEstimates, retentionRisks, hotByLag, hotByThroughput,
        transitionsByGroup, lagTrendDeadband, stalenessByGroup, underReplicated, sizeSkews);
      cycleSnapshot.groups.addAll(partial.groups());
      cycleSnapshot.throughput.addAll(hotByThroughput);
    }

    log.debug("Reported metrics for {} consumer groups", lagData.size());
  }

  /**
   * Phase 1: committed offsets for every group, in waves of at most
   * {@code maxConcurrentGroups}.
   *
   * <p>Without the bound, every group's {@code listConsumerGroupOffsets} is in flight at
   * once; on a cluster with thousands of groups that saturates the admin client's request
   * queue and the group coordinators. The waves have no delay between them — this bounds
   * concurrency, it is not a throttle. {@code KAFKA_CHUNK_COUNT}/{@code KAFKA_CHUNK_DELAY_MS}
   * remain the explicit broker-load throttle.
   *
   * @return group ID to its committed offsets; groups that failed are absent
   */
  private Future<Map<String, ConsumerGroupOffsets>> fetchGroupOffsets(
      Collection<String> groups, CycleState cycle) {
    Map<String, ConsumerGroupOffsets> byGroup = new HashMap<>();

    return ChunkProcessor.<String, Void>processSequentially(
      vertx, waves(groups, maxConcurrentGroups), 0,
      wave -> {
        List<Future<ConsumerGroupOffsets>> futures = wave.stream()
          // Recover to null so one failing group (deleted mid-cycle, coordinator hiccup,
          // ACL gap) is skipped rather than failing the wave and aborting the cycle for
          // every other group. The cycle is marked partial: a skipped group's keys are
          // absent from the accumulators, so the retainAll cleanups in finishCycle would
          // delete its live series instead of holding the last good values.
          .map(groupId -> kafkaClient.getConsumerGroupOffsets(groupId)
            .recover(err -> {
              cycle.partial = true;
              log.warn("Failed to collect lag for group {}{} (skipped this cycle): {}",
                groupId, clusterLog(), err.getMessage());
              return Future.succeededFuture(null);
            }))
          .collect(Collectors.toList());

        return Future.all(futures).<Void>map(composite -> {
          for (int i = 0; i < composite.size(); i++) {
            ConsumerGroupOffsets offsets = composite.resultAt(i);
            if (offsets != null) {
              byGroup.put(wave.get(i), offsets);
            }
          }
          return null;
        });
      }
    ).map(v -> byGroup);
  }

  /**
   * Phase 2: resolves every topic in one batched fetch and fills {@link #cycleTopicOffsets}.
   *
   * <p>When chunking is enabled the union is split into {@code chunkCount} batches processed
   * sequentially with the configured delay, so the load-spreading knob still works — it now
   * spreads a handful of batched calls instead of four calls per topic.
   *
   * <p>A failure here fails the cycle (or marks the chunk partial) rather than silently
   * yielding empty lag: {@link #finishCycle} is then skipped and existing gauges are kept.
   * Stale values beat deleting live series over a transient broker error.
   *
   * <p>Topics are first filtered against the cluster's topic list. The Vert.x admin wrapper
   * resolves describeTopics through {@code allTopicNames()}, so a single unknown topic fails
   * the whole batch — and a group's committed offsets outlive a deleted topic until
   * {@code offsets.retention.minutes} (7 days by default), which would keep every cycle
   * partial, and therefore stale-gauge cleanup frozen, for that long. Dropping the topic here
   * makes deletion look like deletion: its series go missing from the cycle's key set and are
   * retired within 1-2 cycles.
   *
   * @return flattened topic-partition to offsets lookup for lag assembly
   */
  private Future<Map<TopicPartitionKey, PartitionOffsets>> fetchTopicOffsets(Set<String> topics) {
    Map<TopicPartitionKey, PartitionOffsets> merged = new HashMap<>();

    // With chunking on this runs once per group chunk, and chunks routinely share topics.
    // Reuse whatever an earlier chunk already resolved this cycle instead of refetching it.
    Set<String> missing = new HashSet<>();
    for (String topic : topics) {
      Future<List<PartitionOffsets>> resolved = cycleTopicOffsets.get(topic);
      if (resolved != null && resolved.succeeded()) {
        index(merged, resolved.result());
      } else {
        missing.add(topic);
      }
    }
    if (missing.isEmpty()) {
      return Future.succeededFuture(merged);
    }

    // A failed listTopics propagates: filtering must never silently fall through unfiltered,
    // which is what reintroduces the week-long cleanup freeze.
    return clusterTopics().compose(existing -> {
      Set<String> gone = new HashSet<>(missing);
      gone.removeAll(existing);
      if (!gone.isEmpty()) {
        // Also covers topics the principal cannot see: with asymmetric ACLs (group offsets
        // readable, topic not) klag cannot tell that apart from deletion and retires the series.
        log.info("Skipping {} topic(s){} absent from the cluster topic list (deleted or not "
          + "visible to this principal); their series are retired: {}",
          gone.size(), clusterLog(), gone);
        missing.removeAll(gone);
      }
      if (missing.isEmpty()) {
        return Future.succeededFuture(merged);
      }

      List<List<String>> topicChunks = chunkConfig.isChunkingEnabled()
        ? ChunkProcessor.balanceIntoChunks(missing, chunkConfig.chunkCount(),
            topic -> cachedTopicPartitionCounts.getOrDefault(topic, 1))
        : List.of(new ArrayList<>(missing));

      return ChunkProcessor.<String, Void>processSequentially(
        vertx, topicChunks, chunkConfig.chunkDelayMs(),
        chunk -> kafkaClient.getLogEndOffsets(new HashSet<>(chunk)).<Void>map(byTopic -> {
          byTopic.forEach((topic, partitions) -> {
            // Publish as a completed future: the ISR check reads cycleTopicOffsets and treats
            // an absent or failed entry as "metadata unavailable, skip this topic".
            cycleTopicOffsets.put(topic, Future.succeededFuture(partitions));
            cachedTopicPartitionCounts.put(topic, partitions.size());
            index(merged, partitions);
          });
          return null;
        })
      ).map(v -> merged);
    });
  }

  /** Cluster topic names, fetched at most once per cycle and shared by every chunk. */
  private Future<Set<String>> clusterTopics() {
    if (cycleTopicNames == null) {
      cycleTopicNames = kafkaClient.listTopics();
    }
    return cycleTopicNames;
  }

  private static void index(
      Map<TopicPartitionKey, PartitionOffsets> target, List<PartitionOffsets> partitions) {
    for (PartitionOffsets po : partitions) {
      target.put(new TopicPartitionKey(po.topic(), po.partition()), po);
    }
  }

  /** Splits items into consecutive groups of at most {@code size}. */
  private static <T> List<List<T>> waves(Collection<T> items, int size) {
    List<T> all = new ArrayList<>(items);
    List<List<T>> waves = new ArrayList<>();
    for (int i = 0; i < all.size(); i += size) {
      waves.add(all.subList(i, Math.min(i + size, all.size())));
    }
    return waves;
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

        // Per-partition series (issue #55) alongside the topic-level aggregate below.
        risks.add(new RetentionRisk(group.consumerGroup(), p.topic(), p.partition(), percent));
        topicMaxPercent.merge(p.topic(), percent, Math::max);
      }

      // Topic-level aggregate (max across partitions)
      for (var entry : topicMaxPercent.entrySet()) {
        risks.add(new RetentionRisk(
          group.consumerGroup(), entry.getKey(), RetentionRisk.AGGREGATE, entry.getValue()));
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
   * Detects under-replicated partitions among the topics observed this chunk, using the ISR/replica
   * counts already fetched by {@link #getLogEndOffsetsCached}. No new Kafka calls.
   *
   * @param topics topics to check (this chunk's {@code topicPartitions.keySet()})
   * @return under-replicated partitions found (empty when none or data not yet resolved)
   */
  private List<UnderReplicatedPartition> calculateUnderReplicatedPartitions(Set<String> topics) {
    List<UnderReplicatedPartition> result = new ArrayList<>();
    for (String topic : topics) {
      Future<List<PartitionOffsets>> future = cycleTopicOffsets.get(topic);
      if (future == null || !future.succeeded()) {
        // Offset metadata failed/absent this cycle -> ISR goes blind for this topic. Failures
        // tend to happen exactly when ISR shrinks, so log rather than skip silently.
        log.warn("Skipping ISR check for topic {}: offset metadata unavailable this cycle", topic);
        continue;
      }
      result.addAll(detectUnderReplicated(future.result()));
    }
    if (!result.isEmpty()) {
      log.debug("Detected {} under-replicated partitions", result.size());
    }
    return result;
  }

  /**
   * Scores retained-size skew for topics observed this chunk, using offsets already fetched by
   * {@link #getLogEndOffsetsCached}. No new Kafka calls.
   *
   * @param topics topics to score (this chunk's {@code topicPartitions.keySet()})
   * @return size-skew scores (empty when none eligible or data not yet resolved)
   */
  private List<TopicSizeSkew> calculateTopicSizeSkew(Set<String> topics) {
    List<PartitionOffsets> offsets = new ArrayList<>();
    for (String topic : topics) {
      Future<List<PartitionOffsets>> future = cycleTopicOffsets.get(topic);
      if (future == null || !future.succeeded()) {
        log.debug("Skipping size-skew check for topic {}: offset metadata unavailable this cycle", topic);
        continue;
      }
      offsets.addAll(future.result());
    }
    List<TopicSizeSkew> result = DataSkewDetector.detect(offsets, dataSkewConfig.minPartitions());
    if (!result.isEmpty()) {
      log.debug("Calculated size skew for {} topics", result.size());
    }
    return result;
  }

  /**
   * Pure detection: returns the partitions whose in-sync replica set is smaller than their full
   * replica set. Package-visible and static so it is testable without a collector/Kafka harness.
   */
  static List<UnderReplicatedPartition> detectUnderReplicated(Collection<PartitionOffsets> partitions) {
    List<UnderReplicatedPartition> result = new ArrayList<>();
    for (PartitionOffsets po : partitions) {
      if (po.inSyncReplicaCount() < po.replicaCount()) {
        result.add(new UnderReplicatedPartition(
          po.topic(), po.partition(), po.replicaCount(), po.inSyncReplicaCount()));
      }
    }
    return result;
  }

  /**
   * Calculates lag in milliseconds per consumer group and topic.
   *
   * <p>Primary path: linear interpolation between Kafka {@code listOffsets} log start/end
   * timestamps. Fallback: poll-time {@code (logEndOffset, systemTime)} history when Kafka
   * timestamps are unavailable; fallback does not extrapolate beyond the oldest retained sample.
   *
   * @param lagData list of consumer group lag data
   * @param timeLagKeys cycle-level accumulator of tracked "topic:partition" keys; the
   *     tracker is cleaned against the full cycle's keys in {@link #finishCycle(CycleState)},
   *     never here, so one chunk cannot wipe another chunk's poll history
   * @return list of lag in milliseconds per consumer group and topic
   */
  private List<LagMs> calculateLagMs(List<ConsumerGroupLag> lagData, Set<String> timeLagKeys) {
    if (offsetTimestampTracker == null) {
      return List.of();
    }

    List<LagMs> lagMsList = new ArrayList<>();
    int skippedPartitions = 0;
    long currentTime = System.currentTimeMillis();

    for (ConsumerGroupLag group : lagData) {
      for (PartitionLag p : group.partitions()) {
        offsetTimestampTracker.recordOffset(p.topic(), p.partition(), p.logEndOffset());
        timeLagKeys.add(p.topic() + ":" + p.partition());
      }

      Map<String, TopicLagMsAggregates> topicAggregates = new HashMap<>();

      for (PartitionLag p : group.partitions()) {
        var lagMs = LagMsCalculator.estimatePartitionLagMs(p, offsetTimestampTracker, currentTime);

        if (lagMs.isPresent()) {
          // Per-partition series (issue #55) alongside the topic-level aggregate below.
          lagMsList.add(new LagMs(
            group.consumerGroup(), p.topic(), p.partition(), p.lag(), lagMs.getAsLong()));
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
          lagMsList.add(new LagMs(
            group.consumerGroup(), topic, LagMs.AGGREGATE, agg.totalLag(), agg.maxLagMs()));
          log.debug("Lag in ms for {}:{}: {} ms (lag_messages={}, partitions_sampled={})",
            group.consumerGroup(), topic, agg.maxLagMs(), agg.totalLag(), agg.count());
        }
      }
    }

    if (skippedPartitions > 0) {
      log.debug("Skipped {} partitions with no lag_ms estimate", skippedPartitions);
    }
    if (!lagMsList.isEmpty()) {
      log.debug("Calculated lag_ms for {} consumer-group/topic pairs", lagMsList.size());
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
   * Cycle-level accumulators shared by every chunk of one collection cycle.
   *
   * <p>Each set gathers the keys observed across all chunks so the retainAll-based
   * cleanups in {@link #finishCycle(CycleState)} see the complete cycle. {@code partial}
   * is set when a chunk fails, which suppresses cleanup for the cycle (the snapshot still
   * publishes unless it is empty).
   */
  private static class CycleState {
    final Set<String> activeKeys = new HashSet<>();
    final Set<String> velocityKeys = new HashSet<>();
    final Set<String> throughputKeys = new HashSet<>();
    final Set<String> stateGroupKeys = new HashSet<>();
    final Set<String> timeLagKeys = new HashSet<>();
    final Set<String> commitStalenessKeys = new HashSet<>();
    final CycleSnapshot snapshot; // null when no snapshot store is attached
    boolean partial;

    CycleState(CycleSnapshot snapshot) {
      this.snapshot = snapshot;
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
