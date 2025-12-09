package io.github.themoah.klag.model;

/**
 * Represents a hot partition detected by lag analysis.
 *
 * @param consumerGroup the consumer group
 * @param topic the topic name
 * @param partition the partition number
 * @param lag the current lag value
 * @param mean the mean lag across all partitions in the topic
 * @param stdDev the standard deviation of lag across partitions
 * @param zScore how many standard deviations above the mean
 */
public record HotPartitionLag(
  String consumerGroup,
  String topic,
  int partition,
  long lag,
  double mean,
  double stdDev,
  double zScore
) {}
