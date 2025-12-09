package io.github.themoah.klag.metrics.hotpartition;

import io.github.themoah.klag.model.PartitionThroughputSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages historical snapshots for a single partition's throughput.
 * Used to calculate the rate of log_end_offset growth (messages/second).
 *
 * <p>Uses linear regression on (timestamp, logEndOffset) points to calculate
 * the throughput rate, similar to the velocity calculation in TopicLagHistory.
 */
public class PartitionThroughputHistory {

  private final String topic;
  private final int partition;
  private final ArrayDeque<PartitionThroughputSnapshot> snapshots;
  private final int maxSize;
  private final int minSamples;

  public PartitionThroughputHistory(String topic, int partition, int bufferSize, int minSamples) {
    this.topic = topic;
    this.partition = partition;
    this.snapshots = new ArrayDeque<>(bufferSize);
    this.maxSize = bufferSize;
    this.minSamples = minSamples;
  }

  /**
   * Adds a new snapshot to the history.
   * If the buffer is full, the oldest snapshot is removed.
   *
   * @param snapshot the snapshot to add
   */
  public void addSnapshot(PartitionThroughputSnapshot snapshot) {
    if (snapshots.size() >= maxSize) {
      snapshots.removeFirst();
    }
    snapshots.addLast(snapshot);
  }

  /**
   * Calculates throughput rate using linear regression on (timestamp, logEndOffset) points.
   *
   * <p>Uses Ordinary Least Squares (OLS) to fit a line through the data points.
   * The slope of this line represents the rate of change in log end offset,
   * which is the throughput in messages per second.
   *
   * @return throughput in messages per second, or null if insufficient data
   */
  public Double calculateThroughput() {
    if (snapshots.size() < minSamples) {
      return null;
    }

    List<PartitionThroughputSnapshot> samples = new ArrayList<>(snapshots);

    // Linear regression: fit line y = mx + b to (timestamp, logEndOffset)
    // Slope m = rate of change in messages per millisecond
    long n = samples.size();
    double sumT = 0, sumOffset = 0, sumTOffset = 0, sumTSquared = 0;

    long firstTimestamp = samples.get(0).timestamp();

    for (PartitionThroughputSnapshot sample : samples) {
      // Normalize timestamps to avoid overflow
      double t = (sample.timestamp() - firstTimestamp);
      double offset = sample.logEndOffset();

      sumT += t;
      sumOffset += offset;
      sumTOffset += t * offset;
      sumTSquared += t * t;
    }

    double meanT = sumT / n;
    double meanOffset = sumOffset / n;

    // OLS formula: slope = (Σ(t*offset) - n*mean_t*mean_offset) / (Σ(t²) - n*mean_t²)
    double numerator = sumTOffset - n * meanT * meanOffset;
    double denominator = sumTSquared - n * meanT * meanT;

    if (Math.abs(denominator) < 1e-10) {
      return null;  // All samples at same time, can't calculate rate
    }

    double ratePerMs = numerator / denominator;
    return ratePerMs * 1000.0;  // Convert to messages/second
  }

  /**
   * Returns whether this history has enough data to calculate throughput.
   *
   * @return true if at least minSamples snapshots have been recorded
   */
  public boolean hasEnoughData() {
    return snapshots.size() >= minSamples;
  }

  public String topic() {
    return topic;
  }

  public int partition() {
    return partition;
  }
}
