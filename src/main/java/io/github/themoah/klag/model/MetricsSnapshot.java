package io.github.themoah.klag.model;

import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.LagTrend.Direction;
import java.util.List;
import java.util.Optional;

/**
 * Immutable point-in-time snapshot of the last completed metrics collection cycle.
 *
 * <p>Produced by the metrics collector and consumed read-only by the MCP layer. Holds the
 * derived metrics already computed during reporting so MCP tools never touch Kafka.
 *
 * @param timestampMs wall-clock time the snapshot was assembled
 * @param groups per consumer-group derived metrics
 * @param hotPartitionsByThroughput topic-level throughput outliers (no consumer dimension)
 */
public record MetricsSnapshot(
  long timestampMs,
  List<GroupSnapshot> groups,
  List<HotPartitionThroughput> hotPartitionsByThroughput
) {

  /**
   * Derived metrics for a single consumer group at snapshot time.
   *
   * @param consumerGroup the consumer group ID
   * @param state the consumer group state
   * @param totalLag total lag across all partitions
   * @param maxLag max partition lag
   * @param minLag min partition lag
   * @param partitions per-partition lag detail
   * @param velocities per-topic lag velocity
   * @param lagMs per-topic lag in milliseconds
   * @param timeToClose per-topic time-to-close estimates
   * @param retentionRisks per-topic retention risk percentages
   * @param hotPartitionsByLag lag outlier partitions for this group
   * @param recentTransitions rolling history of recent state changes (oldest first)
   * @param trends per-topic basic lag trend (growing/shrinking/stable)
   * @param overallTrend group-level lag trend rollup
   */
  public record GroupSnapshot(
    String consumerGroup,
    State state,
    long totalLag,
    long maxLag,
    long minLag,
    List<PartitionLag> partitions,
    List<LagVelocity> velocities,
    List<LagMs> lagMs,
    List<TimeToCloseEstimate> timeToClose,
    List<RetentionRisk> retentionRisks,
    List<HotPartitionLag> hotPartitionsByLag,
    List<StateTransition> recentTransitions,
    List<LagTrend> trends,
    Direction overallTrend
  ) {

    /**
     * Backward-compatible constructor defaulting the state-change/trend fields to empty/STABLE.
     * Used by tests and any caller that does not supply trend/transition data.
     */
    public GroupSnapshot(
      String consumerGroup,
      State state,
      long totalLag,
      long maxLag,
      long minLag,
      List<PartitionLag> partitions,
      List<LagVelocity> velocities,
      List<LagMs> lagMs,
      List<TimeToCloseEstimate> timeToClose,
      List<RetentionRisk> retentionRisks,
      List<HotPartitionLag> hotPartitionsByLag
    ) {
      this(consumerGroup, state, totalLag, maxLag, minLag, partitions, velocities, lagMs,
        timeToClose, retentionRisks, hotPartitionsByLag, List.of(), List.of(), Direction.STABLE);
    }
  }

  /**
   * Looks up a group snapshot by exact (case-sensitive) consumer group ID.
   *
   * @param consumerGroup the consumer group ID
   * @return the group snapshot if present
   */
  public Optional<GroupSnapshot> group(String consumerGroup) {
    return groups.stream()
      .filter(g -> g.consumerGroup().equals(consumerGroup))
      .findFirst();
  }
}
