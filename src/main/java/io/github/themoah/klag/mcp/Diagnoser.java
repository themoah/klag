package io.github.themoah.klag.mcp;

import io.github.themoah.klag.model.HotPartitionLag;
import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.MetricsSnapshot.GroupSnapshot;
import io.github.themoah.klag.model.RetentionRisk;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule-based diagnosis of a single consumer group from its snapshot.
 *
 * <p>Pure and deterministic so it can be exhaustively eval-tested. Produces a structured
 * {@link Diagnosis} that the MCP {@code diagnose} tool formats for agents.
 */
public final class Diagnoser {

  private Diagnoser() {}

  /** Severity ordered from least to most urgent. */
  public enum Severity { OK, INFO, WARNING, CRITICAL }

  /**
   * A single diagnostic observation.
   *
   * @param severity how urgent the observation is
   * @param title short headline
   * @param detail explanation and likely-cause hint
   */
  public record Finding(Severity severity, String title, String detail) {}

  /**
   * Full diagnosis for a group.
   *
   * @param group the consumer group ID
   * @param overall the most urgent severity across findings
   * @param findings all observations
   * @param summary one-line human/agent summary
   */
  public record Diagnosis(String group, Severity overall, List<Finding> findings, String summary) {}

  // Retention risk percentage at/above which data loss is considered to have occurred.
  private static final double RETENTION_DATA_LOSS_PERCENT = 100.0;
  // Retention risk percentage at/above which we warn before data loss.
  private static final double RETENTION_WARN_PERCENT = 80.0;
  // Lag below this (messages) is treated as effectively caught up; trend is not noteworthy.
  private static final long NOTABLE_LAG = 100;

  /**
   * Diagnoses a consumer group.
   *
   * @param g the group snapshot
   * @return the structured diagnosis
   */
  public static Diagnosis diagnose(GroupSnapshot g) {
    List<Finding> findings = new ArrayList<>();

    addStateFinding(g, findings);
    addRetentionFinding(g, findings);
    addVelocityFindings(g, findings);
    addHotPartitionFinding(g, findings);

    Severity overall = findings.stream()
      .map(Finding::severity)
      .max(Severity::compareTo)
      .orElse(Severity.OK);

    if (findings.isEmpty()) {
      findings.add(new Finding(Severity.OK, "Healthy",
        "Group is stable with no lag growth, retention risk, or partition skew."));
    }

    return new Diagnosis(g.consumerGroup(), overall, List.copyOf(findings), summary(g, overall, findings));
  }

  private static void addStateFinding(GroupSnapshot g, List<Finding> findings) {
    switch (g.state()) {
      case DEAD -> findings.add(new Finding(Severity.CRITICAL, "Group is DEAD",
        "The consumer group no longer exists or has no offsets/members. Consumers are not running."));
      case EMPTY -> findings.add(new Finding(Severity.WARNING, "No active members",
        "Group has committed offsets but no consumers are attached. Likely all consumers are down."));
      case PREPARING_REBALANCE, COMPLETING_REBALANCE -> findings.add(new Finding(Severity.INFO, "Rebalancing",
        "Group is rebalancing; lag may spike transiently while partitions are reassigned."));
      case UNKNOWN -> findings.add(new Finding(Severity.INFO, "State unknown",
        "Could not determine group state from the last collection."));
      default -> { /* STABLE: healthy baseline, no finding */ }
    }
  }

  private static void addRetentionFinding(GroupSnapshot g, List<Finding> findings) {
    double maxPercent = g.retentionRisks().stream()
      .mapToDouble(RetentionRisk::percent)
      .max()
      .orElse(0.0);

    if (maxPercent >= RETENTION_DATA_LOSS_PERCENT) {
      findings.add(new Finding(Severity.CRITICAL, "Data loss risk",
        String.format(Locale.ROOT,
          "Lag has reached %.0f%% of the retention window; the consumer is at or behind the "
          + "earliest retained offset and unconsumed messages may be deleted.", maxPercent)));
    } else if (maxPercent >= RETENTION_WARN_PERCENT) {
      findings.add(new Finding(Severity.WARNING, "Approaching retention limit",
        String.format(Locale.ROOT,
          "Lag is %.0f%% of the retention window. If it keeps growing, messages will be lost "
          + "before they are consumed. Scale consumers or raise retention.", maxPercent)));
    }
  }

  private static void addVelocityFindings(GroupSnapshot g, List<Finding> findings) {
    for (LagVelocity v : g.velocities()) {
      if (v.velocity() > 0 && g.totalLag() > 0) {
        findings.add(new Finding(Severity.WARNING, "Lag growing on " + v.topic(),
          String.format(Locale.ROOT,
            "Lag is growing at +%.1f msg/s on topic %s. Consumers are not keeping up with "
            + "producers; add consumers/partitions or speed up processing.", v.velocity(), v.topic())));
      } else if (v.velocity() < 0 && g.totalLag() > NOTABLE_LAG) {
        findings.add(new Finding(Severity.INFO, "Catching up on " + v.topic(),
          "Lag is shrinking on topic " + v.topic() + " (" + describeTimeToClose(g, v.topic()) + ")."));
      }
    }
  }

  private static String describeTimeToClose(GroupSnapshot g, String topic) {
    return g.timeToClose().stream()
      .filter(t -> t.topic().equals(topic))
      .map(TimeToCloseEstimate::estimatedTimeToCloseSeconds)
      .findFirst()
      .map(secs -> "est. " + secs + "s to clear")
      .orElse("recovering");
  }

  private static void addHotPartitionFinding(GroupSnapshot g, List<Finding> findings) {
    for (HotPartitionLag h : g.hotPartitionsByLag()) {
      findings.add(new Finding(Severity.WARNING, "Hot partition on " + h.topic(),
        String.format(Locale.ROOT,
          "Partition %d of topic %s is a lag hot spot (z=%.1f, lag=%d vs mean %.0f). Likely a key "
          + "skew or a slow/stuck consumer for that partition.", h.partition(), h.topic(),
          h.zScore(), h.lag(), h.mean())));
    }
  }

  private static String summary(GroupSnapshot g, Severity overall, List<Finding> findings) {
    if (overall == Severity.OK) {
      return "Group " + g.consumerGroup() + " looks healthy: stable, lag not growing, no retention risk.";
    }
    String top = findings.stream()
      .filter(f -> f.severity() == overall)
      .map(Finding::title)
      .findFirst()
      .orElse("issue detected");
    return "Group " + g.consumerGroup() + " — " + overall + ": " + top + ".";
  }
}
