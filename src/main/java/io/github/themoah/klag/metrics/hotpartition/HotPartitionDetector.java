package io.github.themoah.klag.metrics.hotpartition;

import io.github.themoah.klag.metrics.hotpartition.StatisticalUtils.Stats;
import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects hot partitions based on statistical outliers for lag and throughput.
 *
 * <p>Hot partitions are those where the lag or throughput is more than
 * (sigmaMultiplier * stdDev) above the mean for their topic.
 *
 * <p>This detector uses population standard deviation since we have all partitions
 * in the topic, not a sample.
 */
public class HotPartitionDetector {

  private static final Logger log = LoggerFactory.getLogger(HotPartitionDetector.class);

  private final HotPartitionConfig config;
  private final PartitionThroughputTracker throughputTracker;

  public HotPartitionDetector(HotPartitionConfig config) {
    this.config = config;
    this.throughputTracker = new PartitionThroughputTracker(
      config.bufferSize(),
      config.minSamples()
    );
  }

  /**
   * Constructor for testing with injectable tracker.
   */
  HotPartitionDetector(HotPartitionConfig config, PartitionThroughputTracker tracker) {
    this.config = config;
    this.throughputTracker = tracker;
  }

  /**
   * Detects hot partitions by lag for all consumer groups.
   *
   * <p>For each consumer group and topic combination, calculates the mean and
   * standard deviation of partition lags, then identifies partitions that exceed
   * the threshold (mean + sigma * stdDev).
   *
   * @param lagData lag data for all consumer groups
   * @return list of hot partition lag results (only outliers)
   */
  public List<HotPartitionLag> detectHotPartitionsByLag(List<ConsumerGroupLag> lagData) {
    List<HotPartitionLag> hotPartitions = new ArrayList<>();

    for (ConsumerGroupLag group : lagData) {
      // Group partitions by topic
      Map<String, List<PartitionLag>> partitionsByTopic = group.partitions().stream()
        .collect(Collectors.groupingBy(PartitionLag::topic));

      for (Map.Entry<String, List<PartitionLag>> entry : partitionsByTopic.entrySet()) {
        String topic = entry.getKey();
        List<PartitionLag> partitions = entry.getValue();

        // Skip topics with too few partitions
        if (partitions.size() < config.minPartitions()) {
          continue;
        }

        // Calculate statistics for this topic's partitions
        List<Long> lags = partitions.stream()
          .map(PartitionLag::lag)
          .collect(Collectors.toList());

        Stats stats = StatisticalUtils.calculateStats(lags);

        // Find outliers
        for (PartitionLag partition : partitions) {
          if (StatisticalUtils.isOutlier(
              partition.lag(), stats.mean(), stats.stdDev(), config.sigmaMultiplier())) {

            double zScore = StatisticalUtils.zScore(partition.lag(), stats.mean(), stats.stdDev());

            hotPartitions.add(new HotPartitionLag(
              group.consumerGroup(),
              topic,
              partition.partition(),
              partition.lag(),
              stats.mean(),
              stats.stdDev(),
              zScore
            ));

            log.debug("Hot partition detected by lag: group={}, topic={}, partition={}, " +
              "lag={}, mean={}, stdDev={}, zScore={}",
              group.consumerGroup(), topic, partition.partition(),
              partition.lag(), String.format("%.2f", stats.mean()),
              String.format("%.2f", stats.stdDev()), String.format("%.2f", zScore));
          }
        }
      }
    }

    if (!hotPartitions.isEmpty()) {
      log.info("Detected {} hot partitions by lag", hotPartitions.size());
    }

    return hotPartitions;
  }

  /**
   * Records throughput snapshots for all partitions and returns the set of tracked keys.
   *
   * <p>This should be called each collection cycle before detecting hot partitions
   * by throughput. The returned keys can be used for cleanup.
   *
   * @param lagData lag data containing logEndOffset for all partitions
   * @return set of "topic:partition" keys that were updated
   */
  public Set<String> recordThroughputSnapshots(List<ConsumerGroupLag> lagData) {
    Set<String> activeKeys = new HashSet<>();

    // Track unique topic:partition combinations (avoid duplicates from multiple consumer groups)
    Map<String, Long> partitionOffsets = new HashMap<>();

    for (ConsumerGroupLag group : lagData) {
      for (PartitionLag partition : group.partitions()) {
        String key = PartitionThroughputTracker.makeKey(partition.topic(), partition.partition());
        // Use the max offset if we see the same partition from multiple groups
        partitionOffsets.merge(key, partition.logEndOffset(), Long::max);
      }
    }

    // Record snapshots
    for (Map.Entry<String, Long> entry : partitionOffsets.entrySet()) {
      String[] parts = entry.getKey().split(":");
      String topic = parts[0];
      int partition = Integer.parseInt(parts[1]);
      throughputTracker.recordSnapshot(topic, partition, entry.getValue());
      activeKeys.add(entry.getKey());
    }

    return activeKeys;
  }

  /**
   * Detects hot partitions by throughput.
   *
   * <p>For each topic, calculates the mean and standard deviation of partition
   * throughput rates, then identifies partitions that exceed the threshold.
   *
   * <p>Note: This method requires multiple collection cycles before it can
   * produce results, as throughput calculation needs historical data.
   *
   * @return list of hot partition throughput results (only outliers)
   */
  public List<HotPartitionThroughput> detectHotPartitionsByThroughput() {
    List<HotPartitionThroughput> hotPartitions = new ArrayList<>();

    Map<String, Double> allThroughputs = throughputTracker.calculateAllThroughputs();

    // Group by topic
    Map<String, Map<Integer, Double>> throughputsByTopic = new HashMap<>();
    for (Map.Entry<String, Double> entry : allThroughputs.entrySet()) {
      String[] parts = entry.getKey().split(":");
      String topic = parts[0];
      int partition = Integer.parseInt(parts[1]);

      throughputsByTopic.computeIfAbsent(topic, k -> new HashMap<>())
        .put(partition, entry.getValue());
    }

    // Analyze each topic
    for (Map.Entry<String, Map<Integer, Double>> topicEntry : throughputsByTopic.entrySet()) {
      String topic = topicEntry.getKey();
      Map<Integer, Double> partitionThroughputs = topicEntry.getValue();

      // Skip topics with too few partitions with data
      if (partitionThroughputs.size() < config.minPartitions()) {
        continue;
      }

      List<Double> throughputValues = new ArrayList<>(partitionThroughputs.values());
      Stats stats = StatisticalUtils.calculateStats(throughputValues);

      for (Map.Entry<Integer, Double> partitionEntry : partitionThroughputs.entrySet()) {
        int partition = partitionEntry.getKey();
        double throughput = partitionEntry.getValue();

        if (StatisticalUtils.isOutlier(
            throughput, stats.mean(), stats.stdDev(), config.sigmaMultiplier())) {

          double zScore = StatisticalUtils.zScore(throughput, stats.mean(), stats.stdDev());

          hotPartitions.add(new HotPartitionThroughput(
            topic,
            partition,
            throughput,
            stats.mean(),
            stats.stdDev(),
            zScore
          ));

          log.debug("Hot partition detected by throughput: topic={}, partition={}, " +
            "throughput={} msg/s, mean={}, stdDev={}, zScore={}",
            topic, partition, String.format("%.2f", throughput),
            String.format("%.2f", stats.mean()),
            String.format("%.2f", stats.stdDev()), String.format("%.2f", zScore));
        }
      }
    }

    if (!hotPartitions.isEmpty()) {
      log.info("Detected {} hot partitions by throughput", hotPartitions.size());
    }

    return hotPartitions;
  }

  /**
   * Cleans up stale partition data that is no longer being tracked.
   *
   * @param activeKeys set of "topic:partition" keys that are currently active
   */
  public void cleanupStalePartitions(Set<String> activeKeys) {
    throughputTracker.cleanupStalePartitions(activeKeys);
  }

  /**
   * Returns whether hot partition detection is enabled.
   *
   * @return true if enabled
   */
  public boolean isEnabled() {
    return config.enabled();
  }
}
