package io.github.themoah.klag.metrics;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.CommitStaleness;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MemberAssignment;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import io.github.themoah.klag.model.TopicSizeSkew;
import io.github.themoah.klag.model.UnderReplicatedPartition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.vertx.core.Future;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports metrics using Micrometer MeterRegistry.
 * Works with any Micrometer-supported backend (Datadog, Prometheus, etc).
 */
public class MicrometerReporter {

  private static final Logger log = LoggerFactory.getLogger(MicrometerReporter.class);

  private final MeterRegistry registry;
  private final boolean memberLabelsEnabled;
  private final String clusterName;
  private final boolean closeRegistryOnStop;
  /** Gauge value + registered meter, keyed by {@code name + tags.toString()}. */
  private final Map<String, HeldGauge> gauges = new ConcurrentHashMap<>();
  private final Set<String> markedForDeletion = ConcurrentHashMap.newKeySet();
  private final ConsumerGroupStateTracker stateTracker = new ConsumerGroupStateTracker();

  /**
   * Holds the mutable gauge value together with the Micrometer {@link Meter} so stale cleanup
   * can remove from the registry in O(1) without scanning {@code registry.getMeters()}.
   */
  private record HeldGauge(AtomicLong value, Meter meter) {}

  public MicrometerReporter(MeterRegistry registry) {
    this(registry, true);
  }

  public MicrometerReporter(MeterRegistry registry, boolean memberLabelsEnabled) {
    this(registry, memberLabelsEnabled, "", true);
  }

  /**
   * @param clusterName when non-blank, prepended as {@code cluster_name} on Kafka series
   * @param closeRegistryOnStop false when several reporters share one Prometheus registry
   */
  public MicrometerReporter(
      MeterRegistry registry,
      boolean memberLabelsEnabled,
      String clusterName,
      boolean closeRegistryOnStop) {
    this.registry = registry;
    this.memberLabelsEnabled = memberLabelsEnabled;
    this.clusterName = clusterName == null ? "" : clusterName;
    this.closeRegistryOnStop = closeRegistryOnStop;
  }

  /** Configured {@code cluster_name}; blank when the cluster is unnamed. */
  String clusterName() {
    return clusterName;
  }

  private String clusterLog() {
    return clusterName.isBlank() ? "" : " [cluster=" + clusterName + "]";
  }

  /**
   * Reports lag metrics and tracks active gauge keys.
   *
   * @param lagData the lag data to report
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public Future<Void> reportLag(List<ConsumerGroupLag> lagData, Set<String> activeKeys) {
    return reportLag(lagData, Map.of(), activeKeys);
  }

  /**
   * Reports lag metrics, optionally tagging consumer-owned series with member labels.
   *
   * @param lagData the lag data to report
   * @param owners  per-group (topic,partition) -> owning member; ignored when member labels
   *                are disabled. Partitions absent from the map get empty-string member labels.
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public Future<Void> reportLag(
      List<ConsumerGroupLag> lagData,
      Map<String, Map<TopicPartitionKey, MemberAssignment>> owners,
      Set<String> activeKeys) {
    log.debug("Reporting lag metrics for {} consumer groups", lagData.size());

    for (ConsumerGroupLag group : lagData) {
      // Per-topic aggregated lag metrics (issue #55: sum/max/min now carry a topic label).
      // Group total is recoverable via sum by(consumer_group). Model-level
      // totalLag()/maxLag()/minLag() stay group-level for MCP and the snapshot.
      Map<String, long[]> topicAgg = new HashMap<>(); // topic -> [sum, max, min]
      for (PartitionLag p : group.partitions()) {
        long[] a = topicAgg.computeIfAbsent(
          p.topic(), k -> new long[] {0, Long.MIN_VALUE, Long.MAX_VALUE});
        a[0] += p.lag();
        a[1] = Math.max(a[1], p.lag());
        a[2] = Math.min(a[2], p.lag());
      }
      for (var e : topicAgg.entrySet()) {
        Tags topicTags = metricTags("consumer_group", group.consumerGroup(), "topic", e.getKey());
        long[] a = e.getValue();
        trackKey(activeKeys, recordGauge("klag.consumer.lag.sum", topicTags, a[0]));
        trackKey(activeKeys, recordGauge("klag.consumer.lag.max", topicTags, a[1]));
        trackKey(activeKeys, recordGauge("klag.consumer.lag.min", topicTags, a[2]));
      }

      // Per-partition metrics
      for (PartitionLag p : group.partitions()) {
        TopicPartitionKey key = new TopicPartitionKey(p.topic(), p.partition());
        Tags partitionTags = metricTags(
          "consumer_group", group.consumerGroup(),
          "topic", p.topic(),
          "partition", String.valueOf(p.partition())
        );

        // Member labels apply only to consumer-owned series (lag, committed offset) — the
        // log_end/log_start offsets are partition-level and stay member-agnostic, matching
        // kafka-lag-exporter's kafka_partition_* metrics.
        Tags memberTags = tagsWithMemberLabels(partitionTags, owners, group.consumerGroup(), key);

        trackKey(activeKeys, recordGauge("klag.consumer.lag", memberTags, p.lag()));
        trackKey(activeKeys, recordGauge("klag.partition.log_end_offset", partitionTags, p.logEndOffset()));
        trackKey(activeKeys, recordGauge("klag.partition.log_start_offset", partitionTags, p.logStartOffset()));
        trackKey(activeKeys, recordGauge("klag.consumer.committed_offset", memberTags, p.committedOffset()));
      }
    }

    return Future.succeededFuture();
  }

  private static Tags memberTags(MemberAssignment owner) {
    MemberAssignment m = owner != null ? owner : MemberAssignment.UNASSIGNED;
    return Tags.of(
      "member_host", m.memberHost(),
      "consumer_id", m.consumerId(),
      "client_id", m.clientId()
    );
  }

  private Tags tagsWithMemberLabels(
      Tags baseTags,
      Map<String, Map<TopicPartitionKey, MemberAssignment>> owners,
      String consumerGroup,
      TopicPartitionKey key) {
    if (!memberLabelsEnabled) {
      return baseTags;
    }
    Map<TopicPartitionKey, MemberAssignment> groupOwners =
      owners.getOrDefault(consumerGroup, Map.of());
    return baseTags.and(memberTags(groupOwners.get(key)));
  }

  private void trackKey(Set<String> activeKeys, String key) {
    if (activeKeys != null) {
      activeKeys.add(key);
    }
  }

  /**
   * Reports topic partition counts.
   */
  public void reportTopicPartitions(Map<String, Integer> topicPartitions) {
    reportTopicPartitions(topicPartitions, null);
  }

  /**
   * Reports topic partition counts and tracks active gauge keys.
   *
   * @param topicPartitions map of topic to partition count
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportTopicPartitions(Map<String, Integer> topicPartitions, Set<String> activeKeys) {
    for (var entry : topicPartitions.entrySet()) {
      Tags tags = metricTags("topic", entry.getKey());
      trackKey(activeKeys, recordGauge("klag.topic.partitions", tags, entry.getValue()));
    }
  }

  /**
   * Reports consumer group state metrics.
   *
   * <p>The metric value represents cumulative state changes:
   * <ul>
   *   <li>0 = state unchanged from previous check (or first observation)</li>
   *   <li>N = cumulative count of state changes since tracking started</li>
   * </ul>
   *
   * @param stateData map of group ID to consumer group state
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportConsumerGroupStates(
      Map<String, ConsumerGroupState> stateData,
      Set<String> activeKeys
  ) {
    log.debug("Reporting state metrics for {} consumer groups", stateData.size());

    for (ConsumerGroupState groupState : stateData.values()) {
      long changeCount = stateTracker.recordState(groupState.groupId(), groupState.state());
      Tags tags = metricTags(
        "consumer_group", groupState.groupId(),
        "state", groupState.state().toMetricValue()
      );
      trackKey(activeKeys, recordGauge("klag.consumer.group.state", tags, changeCount));
    }
  }

  /**
   * Removes state-tracking data for consumer groups that are no longer active.
   *
   * <p>Must be called once per collection cycle with the union of all groups observed in
   * that cycle. Calling it per chunk with a chunk-local subset wipes the state history
   * (change counts and transitions) of every other chunk's groups.
   *
   * @param activeGroupIds all group IDs observed in the completed cycle
   */
  public void cleanupStateTracker(Set<String> activeGroupIds) {
    stateTracker.cleanup(activeGroupIds);
  }

  /**
   * Returns the recent state transitions tracked for a consumer group (oldest first).
   *
   * <p>Exposed for the MCP snapshot so agents can see recent state churn. Read-only; does not
   * affect the state-change metric.
   *
   * @param groupId the consumer group ID
   * @return immutable transition history (empty if none)
   */
  public List<io.github.themoah.klag.model.StateTransition> recentStateTransitions(String groupId) {
    return stateTracker.recentTransitions(groupId);
  }

  /**
   * Reports lag velocity metrics.
   *
   * @param velocities list of calculated velocities
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportVelocity(List<LagVelocity> velocities, Set<String> activeKeys) {
    log.debug("Reporting velocity metrics for {} consumer-group/topic pairs", velocities.size());

    for (LagVelocity velocity : velocities) {
      Tags tags = metricTags(
        "consumer_group", velocity.consumerGroup(),
        "topic", velocity.topic()
      );

      // Round to 2 decimal places for cleaner metrics
      long velocityScaled = Math.round(velocity.velocity() * 100);
      trackKey(activeKeys, recordGauge("klag.consumer.lag.velocity", tags, velocityScaled));
    }
  }

  /**
   * Reports hot partition lag metrics.
   * Only reports partitions that are statistical outliers.
   *
   * @param hotPartitions list of detected hot partitions by lag
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportHotPartitionLag(List<HotPartitionLag> hotPartitions, Set<String> activeKeys) {
    log.debug("Reporting {} hot partition lag metrics", hotPartitions.size());

    for (HotPartitionLag hot : hotPartitions) {
      Tags tags = metricTags(
        "consumer_group", hot.consumerGroup(),
        "topic", hot.topic(),
        "partition", String.valueOf(hot.partition())
      );

      trackKey(activeKeys, recordGauge("klag.hot_partition.lag", tags, hot.lag()));
    }
  }

  /**
   * Reports hot partition throughput metrics.
   * Only reports partitions that are statistical outliers.
   *
   * @param hotPartitions list of detected hot partitions by throughput
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportHotPartitionThroughput(List<HotPartitionThroughput> hotPartitions, Set<String> activeKeys) {
    log.debug("Reporting {} hot partition throughput metrics", hotPartitions.size());

    for (HotPartitionThroughput hot : hotPartitions) {
      Tags tags = metricTags(
        "topic", hot.topic(),
        "partition", String.valueOf(hot.partition())
      );

      // Report throughput scaled by 100 to preserve 2 decimal places of precision
      long throughputScaled = Math.round(hot.throughput() * 100);
      trackKey(activeKeys, recordGauge("klag.hot_partition", tags, throughputScaled));
    }
  }

  /**
   * Reports under-replicated partition metrics.
   * Only reports partitions where the in-sync replica set is smaller than the full replica set.
   *
   * @param partitions list of detected under-replicated partitions
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportUnderReplicatedPartitions(
      List<UnderReplicatedPartition> partitions, Set<String> activeKeys) {
    log.debug("Reporting {} under-replicated partition metrics", partitions.size());

    for (UnderReplicatedPartition u : partitions) {
      Tags tags = metricTags(
        "topic", u.topic(),
        "partition", String.valueOf(u.partition())
      );

      long missingReplicas = u.replicaCount() - u.inSyncReplicaCount();
      trackKey(activeKeys, recordGauge("klag.partition.under_replicated", tags, missingReplicas));
    }
  }

  /**
   * Reports topic-level retained-size skew ({@code max/mean} of logEnd−logStart, scaled ×100).
   * Tags are {@code topic} plus optional {@code cluster_name}.
   *
   * @param skews list of topic size-skew scores
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportTopicSizeSkew(List<TopicSizeSkew> skews, Set<String> activeKeys) {
    log.debug("Reporting {} topic size-skew metrics", skews.size());

    for (TopicSizeSkew skew : skews) {
      Tags tags = metricTags("topic", skew.topic());
      long scaled = Math.round(skew.ratio() * 100);
      trackKey(activeKeys, recordGauge("klag.topic.size_skew", tags, scaled));
    }
  }

  /**
   * Reports lag in milliseconds (Kafka timestamps or poll-history fallback).
   *
   * @param lagMsData list of lag in milliseconds data
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportLagMs(List<LagMs> lagMsData, Set<String> activeKeys) {
    reportLagMs(lagMsData, Map.of(), activeKeys);
  }

  /**
   * Reports lag in milliseconds (Kafka timestamps or poll-history fallback), optionally tagging
   * per-partition series with the owning consumer member.
   *
   * @param lagMsData list of lag in milliseconds data
   * @param owners per-group (topic,partition) -> owning member; ignored when member labels are
   *               disabled. Aggregate topic rollups are never member-tagged.
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportLagMs(
      List<LagMs> lagMsData,
      Map<String, Map<TopicPartitionKey, MemberAssignment>> owners,
      Set<String> activeKeys) {
    log.debug("Reporting {} lag_ms metrics", lagMsData.size());

    for (LagMs lagMs : lagMsData) {
      Tags tags = metricTags(
        "consumer_group", lagMs.consumerGroup(),
        "topic", lagMs.topic()
      );
      // LagMs.AGGREGATE (-1) is the topic-level aggregate: omit the tag so it stays a topic rollup.
      if (lagMs.partition() != LagMs.AGGREGATE) {
        TopicPartitionKey key = new TopicPartitionKey(lagMs.topic(), lagMs.partition());
        tags = tagsWithMemberLabels(
          tags.and("partition", String.valueOf(lagMs.partition())),
          owners,
          lagMs.consumerGroup(),
          key
        );
      }

      trackKey(activeKeys, recordGauge("klag.consumer.lag.ms", tags, lagMs.lagMs()));
    }
  }

  /**
   * Reports time-to-close estimates in seconds.
   * Only reports when consumer is catching up (velocity < 0).
   *
   * @param estimates list of time-to-close estimates
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportTimeToClose(List<TimeToCloseEstimate> estimates, Set<String> activeKeys) {
    log.debug("Reporting {} time-to-close estimates", estimates.size());

    for (TimeToCloseEstimate estimate : estimates) {
      Tags tags = metricTags(
        "consumer_group", estimate.consumerGroup(),
        "topic", estimate.topic()
      );

      trackKey(activeKeys, recordGauge("klag.consumer.lag.time_to_close_seconds", tags, estimate.estimatedTimeToCloseSeconds()));
    }
  }

  /**
   * Reports retention risk percentage metrics (DLP).
   * Shows what percentage of topic retention is consumed by consumer lag.
   *
   * @param risks list of retention risk data
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportRetentionPercent(List<RetentionRisk> risks, Set<String> activeKeys) {
    log.debug("Reporting {} retention risk metrics", risks.size());

    for (RetentionRisk risk : risks) {
      Tags tags = metricTags(
        "consumer_group", risk.consumerGroup(),
        "topic", risk.topic()
      );
      // RetentionRisk.AGGREGATE (-1) is the topic-level aggregate: omit the tag so it stays a topic rollup.
      if (risk.partition() != RetentionRisk.AGGREGATE) {
        tags = tags.and("partition", String.valueOf(risk.partition()));
      }

      // Store as integer (percent * 100) to preserve 2 decimal places
      long percentScaled = Math.round(risk.percent() * 100);
      trackKey(activeKeys, recordGauge("klag.consumer.lag.retention_percent", tags, percentScaled));
    }
  }

  /**
   * Reports commit staleness in seconds (time since the committed offset last advanced).
   * Only populated for group/topics with lag &gt; 0.
   *
   * @param stalenessData list of commit staleness data
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public void reportCommitStaleness(List<CommitStaleness> stalenessData, Set<String> activeKeys) {
    log.debug("Reporting {} commit staleness metrics", stalenessData.size());

    for (CommitStaleness staleness : stalenessData) {
      Tags tags = metricTags(
        "consumer_group", staleness.consumerGroup(),
        "topic", staleness.topic()
      );

      trackKey(activeKeys, recordGauge("klag.consumer.commit.staleness_seconds", tags, staleness.stalenessSeconds()));
    }
  }

  public Future<Void> start() {
    log.info("MicrometerReporter started{}", clusterLog());
    return Future.succeededFuture();
  }

  public Future<Void> close() {
    log.info("Closing MicrometerReporter{}", clusterLog());
    for (String key : new HashSet<>(gauges.keySet())) {
      removeGauge(key);
    }
    markedForDeletion.clear();
    if (closeRegistryOnStop && registry != null) {
      registry.close();
    }
    return Future.succeededFuture();
  }

  private Tags metricTags(String... keyValues) {
    Tags tags = Tags.of(keyValues);
    if (clusterName.isBlank()) {
      return tags;
    }
    return Tags.of("cluster_name", clusterName).and(tags);
  }

  /**
   * Registers or updates a gauge. The map key is {@code name + tags.toString()} — use
   * {@link Tags#toString()} (no spaces after commas), not {@code Meter.Id#getTags()} list
   * formatting, so keys stay stable across report and cleanup.
   */
  private String recordGauge(String name, Tags tags, long value) {
    String key = name + tags.toString();
    HeldGauge held = gauges.computeIfAbsent(key, k -> {
      AtomicLong newValue = new AtomicLong(value);
      Meter meter = Gauge.builder(name, newValue, AtomicLong::get)
        .tags(tags)
        .register(registry);
      return new HeldGauge(newValue, meter);
    });
    held.value().set(value);
    return key;
  }

  /**
   * Two-phase cleanup for stale gauges.
   * Phase 1: Mark missing gauges for deletion
   * Phase 2: Delete gauges that were marked AND still missing
   *
   * @param activeKeys set of gauge keys that were updated in the current cycle
   */
  public void cleanupStaleGauges(Set<String> activeKeys) {
    Set<String> currentKeys = gauges.keySet();

    // Phase 2: Delete gauges marked for deletion that are still missing
    Set<String> toDelete = new HashSet<>(markedForDeletion);
    toDelete.removeAll(activeKeys);

    long deleteStartNanos = System.nanoTime();
    for (String key : toDelete) {
      removeGauge(key);
    }
    markedForDeletion.removeAll(toDelete);

    if (!toDelete.isEmpty()) {
      long deleteMs = (System.nanoTime() - deleteStartNanos) / 1_000_000L;
      log.info("Cleaned up {} stale gauges in {}ms", toDelete.size(), deleteMs);
    }

    // Phase 1: Mark currently missing gauges for deletion
    Set<String> missing = new HashSet<>(currentKeys);
    missing.removeAll(activeKeys);
    missing.removeAll(toDelete);

    // Clear marks for gauges that came back
    markedForDeletion.retainAll(missing);

    // Add new marks
    for (String key : missing) {
      if (markedForDeletion.add(key)) {
        log.debug("Marked gauge for deletion: {}", key);
      }
    }
  }

  private void removeGauge(String key) {
    HeldGauge held = gauges.remove(key);
    if (held != null) {
      registry.remove(held.meter());
      log.debug("Removed stale gauge: {}", key);
    }
  }
}
