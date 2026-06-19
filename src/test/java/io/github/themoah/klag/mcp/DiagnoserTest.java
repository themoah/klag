package io.github.themoah.klag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.mcp.Diagnoser.Diagnosis;
import io.github.themoah.klag.mcp.Diagnoser.Severity;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupState.State;
import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.LagMs;
import io.github.themoah.klag.model.LagTrend.Direction;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.StateTransition;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Golden-scenario evals for the diagnose logic. Each scenario crafts a group snapshot in a
 * known state and asserts the diagnosis severity and that the right finding surfaces.
 */
class DiagnoserTest {

  private static boolean hasFindingContaining(Diagnosis d, String needle) {
    String lower = needle.toLowerCase(Locale.ROOT);
    return d.findings().stream()
      .anyMatch(f -> (f.title() + " " + f.detail()).toLowerCase(Locale.ROOT).contains(lower));
  }

  private static GroupSnapshot group(
      String name, State state, long totalLag,
      List<LagVelocity> vel, List<LagMs> lagMs, List<TimeToCloseEstimate> ttc,
      List<RetentionRisk> risk, List<HotPartitionLag> hot) {
    PartitionLag p = PartitionLag.of("orders", 0, 1000, 0, 0, 0, 1000 - totalLag);
    return new GroupSnapshot(name, state, totalLag, totalLag, 0,
      List.of(p), vel, lagMs, ttc, risk, hot);
  }

  @Test
  void healthyGroupIsOk() {
    GroupSnapshot g = group("payments", State.STABLE, 5,
      List.of(new LagVelocity("payments", "orders", -1.0, 1000, 3)),
      List.of(), List.of(), List.of(), List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.OK, d.overall());
    assertTrue(d.summary().toLowerCase(Locale.ROOT).contains("healthy"));
  }

  @Test
  void growingLagWarns() {
    GroupSnapshot g = group("payments", State.STABLE, 5000,
      List.of(new LagVelocity("payments", "orders", 42.0, 1000, 3)),
      List.of(), List.of(), List.of(), List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.WARNING, d.overall());
    assertTrue(hasFindingContaining(d, "growing"));
  }

  @Test
  void dataLossIsCritical() {
    GroupSnapshot g = group("payments", State.STABLE, 9000,
      List.of(), List.of(), List.of(),
      List.of(new RetentionRisk("payments", "orders", 100.0)),
      List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.CRITICAL, d.overall());
    assertTrue(hasFindingContaining(d, "data loss"));
  }

  @Test
  void approachingRetentionWarns() {
    GroupSnapshot g = group("payments", State.STABLE, 4000,
      List.of(), List.of(), List.of(),
      List.of(new RetentionRisk("payments", "orders", 85.0)),
      List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.WARNING, d.overall());
    assertTrue(hasFindingContaining(d, "retention"));
  }

  @Test
  void hotPartitionWarns() {
    GroupSnapshot g = group("payments", State.STABLE, 500,
      List.of(), List.of(), List.of(), List.of(),
      List.of(new HotPartitionLag("payments", "orders", 7, 999, 100, 50, 3.5)));

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.WARNING, d.overall());
    assertTrue(hasFindingContaining(d, "hot"));
  }

  @Test
  void deadGroupIsCritical() {
    GroupSnapshot g = group("payments", State.DEAD, 0,
      List.of(), List.of(), List.of(), List.of(), List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.CRITICAL, d.overall());
    assertTrue(hasFindingContaining(d, "dead"));
  }

  @Test
  void emptyGroupWarnsNoMembers() {
    GroupSnapshot g = group("payments", State.EMPTY, 1000,
      List.of(), List.of(), List.of(), List.of(), List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.WARNING, d.overall());
    assertTrue(hasFindingContaining(d, "no active"));
  }

  @Test
  void catchingUpReportsTimeToClose() {
    GroupSnapshot g = group("payments", State.STABLE, 2000,
      List.of(new LagVelocity("payments", "orders", -10.0, 1000, 3)),
      List.of(),
      List.of(new TimeToCloseEstimate("payments", "orders", 2000, -10.0, 200, 3)),
      List.of(), List.of());

    Diagnosis d = Diagnoser.diagnose(g);

    // catching up is informational, not a problem
    assertEquals(Severity.INFO, d.overall());
    assertTrue(hasFindingContaining(d, "catching up"));
  }

  @Test
  void frequentStateChangesWarn() {
    PartitionLag p = PartitionLag.of("orders", 0, 1000, 0, 0, 0, 1000);
    List<StateTransition> churn = List.of(
      new StateTransition(State.STABLE, State.PREPARING_REBALANCE, 1L),
      new StateTransition(State.PREPARING_REBALANCE, State.COMPLETING_REBALANCE, 2L),
      new StateTransition(State.COMPLETING_REBALANCE, State.STABLE, 3L));
    GroupSnapshot g = new GroupSnapshot("payments", State.STABLE, 0, 0, 0,
      List.of(p), List.of(), List.of(), List.of(), List.of(), List.of(),
      churn, List.of(), Direction.STABLE);

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.WARNING, d.overall());
    assertTrue(hasFindingContaining(d, "state change"));
  }

  @Test
  void fewStateChangesDoNotWarn() {
    PartitionLag p = PartitionLag.of("orders", 0, 1000, 0, 0, 0, 1000);
    List<StateTransition> few = List.of(
      new StateTransition(State.STABLE, State.PREPARING_REBALANCE, 1L));
    GroupSnapshot g = new GroupSnapshot("payments", State.STABLE, 0, 0, 0,
      List.of(p), List.of(), List.of(), List.of(), List.of(), List.of(),
      few, List.of(), Direction.STABLE);

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.OK, d.overall());
  }

  @Test
  void criticalWinsOverWarning() {
    GroupSnapshot g = group("payments", State.STABLE, 9000,
      List.of(new LagVelocity("payments", "orders", 99.0, 1000, 3)),
      List.of(), List.of(),
      List.of(new RetentionRisk("payments", "orders", 100.0)),
      List.of(new HotPartitionLag("payments", "orders", 1, 500, 100, 50, 3.0)));

    Diagnosis d = Diagnoser.diagnose(g);

    assertEquals(Severity.CRITICAL, d.overall());
  }

  // Builds a STABLE group with lag and a given commit staleness (seconds) and no other signals.
  private static GroupSnapshot groupWithStaleness(long totalLag, long stalenessSeconds) {
    PartitionLag p = PartitionLag.of("orders", 0, 1000, 0, 0, 0, 1000 - totalLag);
    return new GroupSnapshot("payments", State.STABLE, totalLag, totalLag, 0,
      List.of(p), List.of(), List.of(), List.of(), List.of(), List.of(),
      List.of(), List.of(), Direction.STABLE, stalenessSeconds);
  }

  @Test
  void stuckConsumerWarns() {
    Diagnosis d = Diagnoser.diagnose(groupWithStaleness(500, 600));

    assertEquals(Severity.WARNING, d.overall());
    assertTrue(hasFindingContaining(d, "stuck"));
  }

  @Test
  void freshCommitsAreNotStuck() {
    Diagnosis d = Diagnoser.diagnose(groupWithStaleness(500, 30));

    assertEquals(Severity.OK, d.overall());
  }

  @Test
  void idleWithNoLagIsNotStuck() {
    // staleness high but no lag: nothing to make progress on, so not stuck.
    Diagnosis d = Diagnoser.diagnose(groupWithStaleness(0, 9999));

    assertEquals(Severity.OK, d.overall());
  }
}
