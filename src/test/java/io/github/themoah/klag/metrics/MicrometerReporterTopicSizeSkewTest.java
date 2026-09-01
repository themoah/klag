package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.model.TopicSizeSkew;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the topic size-skew gauge: value is max/mean × 100, tags are topic
 * (no consumer_group or partition; unnamed reporters omit cluster_name), and
 * two-phase stale-gauge cleanup retires deleted topics. Named clusters add
 * cluster_name via {@link MicrometerReporterClusterNameTest}.
 */
class MicrometerReporterTopicSizeSkewTest {

  private static boolean hasTag(Meter meter, String key) {
    return meter.getId().getTags().stream().anyMatch(tag -> tag.getKey().equals(key));
  }

  @Test
  void reportsGaugeScaledBy100() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.5)), null);

    Gauge g = registry.find("klag.topic.size_skew").tag("topic", "orders").gauge();
    assertNotNull(g);
    assertEquals(150.0, g.value(), "ratio 1.5 is stored as 150 to preserve two decimal places");
  }

  @Test
  void topicTagOnly() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.0)), null);

    Gauge gauge = registry.find("klag.topic.size_skew").tag("topic", "orders").gauge();
    assertNotNull(gauge);
    assertTrue(hasTag(gauge, "topic"));
    assertFalse(hasTag(gauge, "consumer_group"),
      "size-skew is topic-level, must not carry a consumer_group tag");
    assertFalse(hasTag(gauge, "partition"),
      "size-skew is topic-level, must not carry a partition tag");
  }

  @Test
  void staleGaugeRemovedAfterTwoCleanupCyclesWhenTopicGone() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    Set<String> cycle1 = new HashSet<>();
    reporter.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.0)), cycle1);
    reporter.cleanupStaleGauges(cycle1);

    assertNotNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge(),
      "still present right after first cycle");

    reporter.cleanupStaleGauges(Set.of());
    assertNotNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge(),
      "survives the mark phase");

    reporter.cleanupStaleGauges(Set.of());
    assertNull(registry.find("klag.topic.size_skew").tag("topic", "orders").gauge(),
      "removed after two consecutive misses");
  }
}
