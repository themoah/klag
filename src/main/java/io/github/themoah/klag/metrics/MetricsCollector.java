package io.github.themoah.klag.metrics;

import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupOffsets;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.PartitionOffsets;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private Long timerId;

  public MetricsCollector(
    Vertx vertx,
    KafkaClientService kafkaClient,
    MetricsReporter reporter,
    long intervalMs,
    String groupFilter
  ) {
    this.vertx = vertx;
    this.kafkaClient = kafkaClient;
    this.reporter = reporter;
    this.intervalMs = intervalMs;
    this.groupPattern = compileGlobPattern(groupFilter);
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
          // Cleanup stale gauges when no groups match
          if (reporter instanceof MicrometerReporter micrometerReporter) {
            micrometerReporter.cleanupStaleGauges(Set.of());
          }
          return Future.succeededFuture();
        }

        // Collect lag and state in parallel
        Future<List<ConsumerGroupLag>> lagFuture = collectAllGroupLags(filteredGroups);
        Future<Map<String, ConsumerGroupState>> stateFuture =
            kafkaClient.describeConsumerGroups(filteredGroups);

        return Future.all(lagFuture, stateFuture)
          .compose(composite -> {
            List<ConsumerGroupLag> lagData = composite.resultAt(0);
            Map<String, ConsumerGroupState> stateData = composite.resultAt(1);
            return reportAllMetrics(lagData, stateData);
          });
      })
      .onFailure(err -> log.error("Failed to collect lag metrics", err));
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

  private Future<Void> reportAllMetrics(
      List<ConsumerGroupLag> lagData,
      Map<String, ConsumerGroupState> stateData
  ) {
    Set<String> activeKeys = new HashSet<>();

    if (reporter instanceof MicrometerReporter micrometerReporter) {
      // Report topic partition counts (max partition number + 1)
      Map<String, Integer> topicPartitions = new HashMap<>();
      for (ConsumerGroupLag group : lagData) {
        for (PartitionLag p : group.partitions()) {
          topicPartitions.merge(p.topic(), p.partition() + 1, Integer::max);
        }
      }

      micrometerReporter.reportTopicPartitions(topicPartitions, activeKeys);
      micrometerReporter.reportLag(lagData, activeKeys);
      micrometerReporter.reportConsumerGroupStates(stateData, activeKeys);
      micrometerReporter.cleanupStaleGauges(activeKeys);

      log.debug("Reported metrics for {} consumer groups", lagData.size());
      return Future.succeededFuture();
    }

    return reporter.reportLag(lagData);
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
}
