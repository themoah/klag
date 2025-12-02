package io.github.themoah.klag.metrics;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.LagVelocity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.vertx.core.Future;
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
public class MicrometerReporter implements MetricsReporter {

  private static final Logger log = LoggerFactory.getLogger(MicrometerReporter.class);

  private final MeterRegistry registry;
  private final Map<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();
  private final Set<String> markedForDeletion = ConcurrentHashMap.newKeySet();

  public MicrometerReporter(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Future<Void> reportLag(List<ConsumerGroupLag> lagData) {
    return reportLag(lagData, null);
  }

  /**
   * Reports lag metrics and tracks active gauge keys.
   *
   * @param lagData the lag data to report
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  public Future<Void> reportLag(List<ConsumerGroupLag> lagData, Set<String> activeKeys) {
    log.debug("Reporting lag metrics for {} consumer groups", lagData.size());

    for (ConsumerGroupLag group : lagData) {
      Tags groupTags = Tags.of("consumer_group", group.consumerGroup());

      // Aggregated lag metrics
      trackKey(activeKeys, recordGauge("klag.consumer.lag.sum", groupTags, group.totalLag()));
      trackKey(activeKeys, recordGauge("klag.consumer.lag.max", groupTags, group.maxLag()));
      trackKey(activeKeys, recordGauge("klag.consumer.lag.min", groupTags, group.minLag()));

      // Per-partition metrics
      for (PartitionLag p : group.partitions()) {
        Tags partitionTags = Tags.of(
          "consumer_group", group.consumerGroup(),
          "topic", p.topic(),
          "partition", String.valueOf(p.partition())
        );

        trackKey(activeKeys, recordGauge("klag.consumer.lag", partitionTags, p.lag()));
        trackKey(activeKeys, recordGauge("klag.partition.log_end_offset", partitionTags, p.logEndOffset()));
        trackKey(activeKeys, recordGauge("klag.partition.log_start_offset", partitionTags, p.logStartOffset()));
        trackKey(activeKeys, recordGauge("klag.consumer.committed_offset", partitionTags, p.committedOffset()));
      }
    }

    return Future.succeededFuture();
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
      Tags tags = Tags.of("topic", entry.getKey());
      trackKey(activeKeys, recordGauge("klag.topic.partitions", tags, entry.getValue()));
    }
  }

  /**
   * Reports consumer group state metrics.
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
      Tags tags = Tags.of(
        "consumer_group", groupState.groupId(),
        "state", groupState.state().toMetricValue()
      );
      trackKey(activeKeys, recordGauge("klag.consumer.group.state", tags, 1));
    }
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
      Tags tags = Tags.of(
        "consumer_group", velocity.consumerGroup(),
        "topic", velocity.topic()
      );

      // Round to 2 decimal places for cleaner metrics
      long velocityScaled = Math.round(velocity.velocity() * 100);
      trackKey(activeKeys, recordGauge("klag.consumer.lag.velocity", tags, velocityScaled));
    }
  }

  @Override
  public Future<Void> start() {
    log.info("MicrometerReporter started");
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> close() {
    log.info("Closing MicrometerReporter");
    if (registry != null) {
      registry.close();
    }
    return Future.succeededFuture();
  }

  private String recordGauge(String name, Tags tags, long value) {
    String key = name + tags.toString();
    AtomicLong atomicValue = gaugeValues.computeIfAbsent(key, k -> {
      AtomicLong newValue = new AtomicLong(value);
      Gauge.builder(name, newValue, AtomicLong::get)
        .tags(tags)
        .register(registry);
      return newValue;
    });
    atomicValue.set(value);
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
    Set<String> currentKeys = gaugeValues.keySet();

    // Phase 2: Delete gauges marked for deletion that are still missing
    Set<String> toDelete = new HashSet<>(markedForDeletion);
    toDelete.removeAll(activeKeys);

    for (String key : toDelete) {
      removeGauge(key);
      markedForDeletion.remove(key);
    }

    if (!toDelete.isEmpty()) {
      log.info("Cleaned up {} stale gauges", toDelete.size());
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
    AtomicLong value = gaugeValues.remove(key);
    if (value != null) {
      registry.getMeters().stream()
        .filter(meter -> buildMeterKey(meter).equals(key))
        .findFirst()
        .ifPresent(registry::remove);
      log.debug("Removed stale gauge: {}", key);
    }
  }

  private String buildMeterKey(Meter meter) {
    return meter.getId().getName() + meter.getId().getTags().toString();
  }
}
