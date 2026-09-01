package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.ConsumerGroupLag.PartitionLag;
import io.github.themoah.klag.model.TopicSizeSkew;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MicrometerReporterClusterNameTest {

  @Test
  void omitsClusterNameWhenBlank() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter reporter = new MicrometerReporter(registry);

    reporter.reportLag(List.of(onePartition()), null);

    Gauge lag = registry.find("klag.consumer.lag")
      .tag("consumer_group", "payments")
      .tag("topic", "orders")
      .gauge();
    assertNotNull(lag);
    assertNull(
      lag.getId().getTag("cluster_name"),
      "cluster_name must be omitted for single-cluster default");
  }

  @Test
  void prependsClusterNameOnSharedRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter a = new MicrometerReporter(registry, true, "msk-a", false);
    MicrometerReporter b = new MicrometerReporter(registry, true, "msk-b", false);

    a.reportLag(List.of(onePartition()), null);
    b.reportLag(List.of(onePartition()), null);

    Gauge lagA = registry.find("klag.consumer.lag").tag("cluster_name", "msk-a").gauge();
    Gauge lagB = registry.find("klag.consumer.lag").tag("cluster_name", "msk-b").gauge();
    assertNotNull(lagA);
    assertNotNull(lagB);
    assertEquals(10.0, lagA.value());
    assertEquals(10.0, lagB.value());
    assertEquals(2, registry.find("klag.consumer.lag").gauges().size());
  }

  @Test
  void topicSizeSkewGetsClusterNameOnSharedRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter a = new MicrometerReporter(registry, true, "msk-a", false);
    MicrometerReporter b = new MicrometerReporter(registry, true, "msk-b", false);

    a.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 1.0)), null);
    b.reportTopicSizeSkew(List.of(new TopicSizeSkew("orders", 2.0)), null);

    Gauge skewA = registry.find("klag.topic.size_skew").tag("cluster_name", "msk-a").gauge();
    Gauge skewB = registry.find("klag.topic.size_skew").tag("cluster_name", "msk-b").gauge();
    assertNotNull(skewA);
    assertNotNull(skewB);
    assertEquals(100.0, skewA.value());
    assertEquals(200.0, skewB.value());
  }

  @Test
  void closeDoesNotCloseSharedRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerReporter a = new MicrometerReporter(registry, true, "msk-a", false);
    a.reportLag(List.of(onePartition()), null);
    a.close();

    MicrometerReporter b = new MicrometerReporter(registry, true, "msk-b", false);
    b.reportLag(List.of(onePartition()), null);

    assertNotNull(registry.find("klag.consumer.lag").tag("cluster_name", "msk-b").gauge());
    assertNull(registry.find("klag.consumer.lag").tag("cluster_name", "msk-a").gauge());
  }

  private static ConsumerGroupLag onePartition() {
    return ConsumerGroupLag.fromPartitions("payments", List.of(
      PartitionLag.of("orders", 0, 100, 0, 0, 0, 90)
    ));
  }
}
