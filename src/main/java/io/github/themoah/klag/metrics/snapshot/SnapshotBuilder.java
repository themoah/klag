package io.github.themoah.klag.metrics.snapshot;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure assembly of a {@link MetricsSnapshot} from the per-cycle data the collector already
 * computes. Side-effect free so it is fully unit-testable and safe to run best-effort.
 */
public final class SnapshotBuilder {

  private SnapshotBuilder() {}

  /**
   * Builds an immutable snapshot from the collector's reporting data.
   *
   * @param timestampMs wall-clock time of assembly
   * @param lagData per-group lag detail (defines the set of groups in the snapshot)
   * @param stateData group ID to state (missing entries default to UNKNOWN)
   * @param velocities per group+topic lag velocity
   * @param lagMsData per group+topic lag in ms
   * @param timeToClose per group+topic time-to-close estimates
   * @param retentionRisks per group+topic retention risk
   * @param hotByLag per group+topic+partition lag outliers
   * @param hotByThroughput topic-level throughput outliers (no consumer dimension)
   * @return the assembled snapshot
   */
  public static MetricsSnapshot build(
    long timestampMs,
    List<ConsumerGroupLag> lagData,
    Map<String, ConsumerGroupState> stateData,
    List<LagVelocity> velocities,
    List<LagMs> lagMsData,
    List<TimeToCloseEstimate> timeToClose,
    List<RetentionRisk> retentionRisks,
    List<HotPartitionLag> hotByLag,
    List<HotPartitionThroughput> hotByThroughput
  ) {
    Map<String, List<LagVelocity>> velByGroup = groupBy(velocities, LagVelocity::consumerGroup);
    Map<String, List<LagMs>> lagMsByGroup = groupBy(lagMsData, LagMs::consumerGroup);
    Map<String, List<TimeToCloseEstimate>> ttcByGroup = groupBy(timeToClose, TimeToCloseEstimate::consumerGroup);
    Map<String, List<RetentionRisk>> riskByGroup = groupBy(retentionRisks, RetentionRisk::consumerGroup);
    Map<String, List<HotPartitionLag>> hotByGroup = groupBy(hotByLag, HotPartitionLag::consumerGroup);

    List<GroupSnapshot> groups = new ArrayList<>(lagData.size());
    for (ConsumerGroupLag lag : lagData) {
      String group = lag.consumerGroup();
      State state = stateData.containsKey(group) ? stateData.get(group).state() : State.UNKNOWN;
      groups.add(new GroupSnapshot(
        group,
        state,
        lag.totalLag(),
        lag.maxLag(),
        lag.minLag(),
        lag.partitions(),
        velByGroup.getOrDefault(group, List.of()),
        lagMsByGroup.getOrDefault(group, List.of()),
        ttcByGroup.getOrDefault(group, List.of()),
        riskByGroup.getOrDefault(group, List.of()),
        hotByGroup.getOrDefault(group, List.of())
      ));
    }

    return new MetricsSnapshot(timestampMs, List.copyOf(groups), List.copyOf(hotByThroughput));
  }

  private static <T> Map<String, List<T>> groupBy(List<T> items, Function<T, String> keyFn) {
    return items.stream().collect(Collectors.groupingBy(keyFn));
  }
}
