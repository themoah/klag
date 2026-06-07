package io.github.themoah.klag.metrics.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupState;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.HotPartitionThroughput;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagTrend.Direction;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.StateTransition;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotBuilderTest {

  private static ConsumerGroupLag groupLag(String group, String topic, int partition, long end, long committed) {
    PartitionLag p = PartitionLag.of(topic, partition, end, 0, 0, 0, committed);
    return ConsumerGroupLag.fromPartitions(group, List.of(p));
  }

  @Test
  void assemblesGroupWithStateAndLag() {
    MetricsSnapshot snap = SnapshotBuilder.build(
      1234L,
      List.of(groupLag("payments", "orders", 0, 100, 40)),
      Map.of("payments", new ConsumerGroupState("payments", State.STABLE)),
      List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    assertEquals(1234L, snap.timestampMs());
    GroupSnapshot g = snap.group("payments").orElseThrow();
    assertEquals(State.STABLE, g.state());
    assertEquals(60, g.totalLag());
    assertEquals(1, g.partitions().size());
  }

  @Test
  void defaultsStateToUnknownWhenMissing() {
    MetricsSnapshot snap = SnapshotBuilder.build(
      1L, List.of(groupLag("g", "t", 0, 10, 0)),
      Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    assertEquals(State.UNKNOWN, snap.group("g").orElseThrow().state());
  }

  @Test
  void routesPerTopicMetricsToOwningGroup() {
    LagVelocity vel = new LagVelocity("payments", "orders", 5.0, 1000, 3);
    LagMs lagMs = new LagMs("payments", "orders", 60, 2000);
    TimeToCloseEstimate ttc = new TimeToCloseEstimate("payments", "orders", 60, -2.0, 30, 3);
    RetentionRisk risk = new RetentionRisk("payments", "orders", 12.5);
    HotPartitionLag hot = new HotPartitionLag("payments", "orders", 7, 999, 100, 50, 3.0);

    MetricsSnapshot snap = SnapshotBuilder.build(
      1L,
      List.of(groupLag("payments", "orders", 0, 100, 40), groupLag("other", "misc", 0, 10, 10)),
      Map.of(),
      List.of(vel), List.of(lagMs), List.of(ttc), List.of(risk), List.of(hot),
      List.of());

    GroupSnapshot pay = snap.group("payments").orElseThrow();
    assertEquals(List.of(vel), pay.velocities());
    assertEquals(List.of(lagMs), pay.lagMs());
    assertEquals(List.of(ttc), pay.timeToClose());
    assertEquals(List.of(risk), pay.retentionRisks());
    assertEquals(List.of(hot), pay.hotPartitionsByLag());

    GroupSnapshot other = snap.group("other").orElseThrow();
    assertTrue(other.velocities().isEmpty());
    assertTrue(other.retentionRisks().isEmpty());
  }

  @Test
  void classifiesTrendsAndCarriesTransitions() {
    LagVelocity growing = new LagVelocity("payments", "orders", 50.0, 1000, 3);
    StateTransition t = new StateTransition(State.STABLE, State.EMPTY, 999L);

    MetricsSnapshot snap = SnapshotBuilder.build(
      1L,
      List.of(groupLag("payments", "orders", 0, 100, 40)),
      Map.of("payments", new ConsumerGroupState("payments", State.STABLE)),
      List.of(growing), List.of(), List.of(), List.of(), List.of(),
      List.of(),
      Map.of("payments", List.of(t)),
      1.0);

    GroupSnapshot g = snap.group("payments").orElseThrow();
    assertEquals(1, g.trends().size());
    assertEquals(Direction.GROWING, g.trends().get(0).direction());
    assertEquals(Direction.GROWING, g.overallTrend());
    assertEquals(List.of(t), g.recentTransitions());
  }

  @Test
  void defaultsTrendsAndTransitionsWhenNotSupplied() {
    MetricsSnapshot snap = SnapshotBuilder.build(
      1L, List.of(groupLag("g", "t", 0, 10, 0)),
      Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    GroupSnapshot g = snap.group("g").orElseThrow();
    assertTrue(g.trends().isEmpty());
    assertTrue(g.recentTransitions().isEmpty());
    assertEquals(Direction.STABLE, g.overallTrend());
  }

  @Test
  void keepsThroughputHotPartitionsAtSnapshotLevel() {
    HotPartitionThroughput hot = new HotPartitionThroughput("orders", 2, 500.0, 100, 80, 5.0);
    MetricsSnapshot snap = SnapshotBuilder.build(
      1L, List.of(groupLag("g", "orders", 0, 10, 0)),
      Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
      List.of(hot));

    assertEquals(List.of(hot), snap.hotPartitionsByThroughput());
  }
}
