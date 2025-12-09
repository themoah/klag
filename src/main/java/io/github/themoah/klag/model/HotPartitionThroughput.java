package io.github.themoah.klag.model;

/**
 * Represents a hot partition detected by throughput analysis.
 *
 * @param topic the topic name
 * @param partition the partition number
 * @param throughput the current throughput (messages/second)
 * @param mean the mean throughput across all partitions in the topic
 * @param stdDev the standard deviation of throughput across partitions
 * @param zScore how many standard deviations above the mean
 */
public record HotPartitionThroughput(
  String topic,
  int partition,
  double throughput,
  double mean,
  double stdDev,
  double zScore
) {}
