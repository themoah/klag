package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.ConsumerGroupOffsets.TopicPartitionKey;
import io.github.themoah.klag.model.MemberAssignment;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the kafka-lag-exporter-parity member labels (member_host / consumer_id / client_id)
 * on consumer-owned lag series: present and correct when enabled with an owner, empty-string when
 * unowned, absent when disabled, and that a rebalanced owner retires the old series.
 */
class MicrometerReporterMemberLabelsTest {

  private static ConsumerGroupLag oneGroup() {
    PartitionLag p = PartitionLag.of("orders", 0, 100, 0, 0, 0, 90); // lag = 10
    return ConsumerGroupLag.fromPartitions("payments", List.of(p));
  }

  private static Map<String, Map<TopicPartitionKey, MemberAssignment>> owners(MemberAssignment a) {
    return Map.of("payments", Map.of(new TopicPartitionKey("orders", 0), a));
  }

  @Test
  void enabledWithOwnerTagsLagSeriesWithMemberLabels() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry, true);

    reporter.reportLag(List.of(oneGroup()),
      owners(new MemberAssignment("10.0.0.1", "consumer-1-abc", "svc-payments")), null);

    Gauge lag = registry.find("klag.consumer.lag")
      .tag("consumer_group", "payments").tag("topic", "orders").tag("partition", "0")
      .tag("member_host", "10.0.0.1").tag("consumer_id", "consumer-1-abc")
      .tag("client_id", "svc-payments").gauge();
    assertNotNull(lag, "lag series must carry member labels when enabled with an owner");

    // committed offset is also consumer-owned -> labelled
    assertNotNull(registry.find("klag.consumer.committed_offset")
      .tag("member_host", "10.0.0.1").gauge(), "committed_offset carries member labels");

    // partition-level offsets are NOT member-tagged
    assertNull(registry.find("klag.partition.log_end_offset").tag("member_host", "10.0.0.1").gauge(),
      "log_end_offset must stay member-agnostic");
  }

  @Test
  void enabledWithoutOwnerEmitsEmptyStringLabels() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry, true);

    reporter.reportLag(List.of(oneGroup()), Map.of(), null); // no owners (Empty group)

    Gauge lag = registry.find("klag.consumer.lag")
      .tag("member_host", "").tag("consumer_id", "").tag("client_id", "").gauge();
    assertNotNull(lag, "unowned partition gets stable empty-string member labels");
  }

  @Test
  void disabledOmitsMemberLabels() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry, false);

    reporter.reportLag(List.of(oneGroup()),
      owners(new MemberAssignment("10.0.0.1", "consumer-1-abc", "svc-payments")), null);

    assertNull(registry.find("klag.consumer.lag").tag("member_host", "10.0.0.1").gauge(),
      "no member_host tag when disabled");
    assertNotNull(registry.find("klag.consumer.lag")
      .tag("consumer_group", "payments").tag("topic", "orders").tag("partition", "0").gauge(),
      "plain lag series still present when disabled");
  }

  @Test
  void rebalanceRetiresOldMemberSeriesFromRegistry() {
    // On owner change the member labels change -> a new gauge key. The old key is absent from
    // the new cycle's active set and the two-phase sweep must remove it from the registry, not
    // just the internal map (the key-format bug that left meters lingering on /metrics).
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry, true);

    Set<String> cycle1 = new HashSet<>();
    reporter.reportLag(List.of(oneGroup()),
      owners(new MemberAssignment("10.0.0.1", "consumer-1", "svc")), cycle1);
    reporter.cleanupStaleGauges(cycle1);

    // New owner after rebalance; two more sweeps (mark, then delete) retire the old series.
    Set<String> cycle2 = new HashSet<>();
    reporter.reportLag(List.of(oneGroup()),
      owners(new MemberAssignment("10.0.0.2", "consumer-2", "svc")), cycle2);
    reporter.cleanupStaleGauges(cycle2);
    reporter.cleanupStaleGauges(cycle2);

    assertNull(registry.find("klag.consumer.lag").tag("member_host", "10.0.0.1").gauge(),
      "old owner's series must be removed from the registry after rebalance");
    assertNotNull(registry.find("klag.consumer.lag").tag("member_host", "10.0.0.2").gauge(),
      "new owner's series present");
  }
}
