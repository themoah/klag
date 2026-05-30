package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetricsConfig}.
 *
 * <p>Env-var stubbing is intentionally avoided (no portable JVM API); we exercise
 * the record + {@code isEnabled} directly and only sanity-check
 * {@link MetricsConfig#fromEnvironment()} produces a non-null record. The end-to-end
 * include/exclude filtering logic is covered by {@link GroupFilterTest}.
 */
class MetricsConfigTest {

  @Test
  void record_holdsAllFields() {
    MetricsConfig c = new MetricsConfig("prometheus", 30_000L, "ingest*,categorize*", "debug-*", true);
    assertEquals("prometheus", c.reporterType());
    assertEquals(30_000L, c.collectionIntervalMs());
    assertEquals("ingest*,categorize*", c.consumerGroupFilter());
    assertEquals("debug-*", c.consumerGroupExclude());
    assertTrue(c.jvmMetricsEnabled());
  }

  @Test
  void consumerGroupExclude_defaultsToEmptyString_whenConstructedDirectly() {
    MetricsConfig c = new MetricsConfig("prometheus", 30_000L, "*", "", false);
    assertEquals("", c.consumerGroupExclude());
  }

  @Test
  void isEnabled_returnsFalseForNoneOrBlankReporter() {
    assertFalse(new MetricsConfig("none", 60_000L, "*", "", false).isEnabled());
    assertFalse(new MetricsConfig("", 60_000L, "*", "", false).isEnabled());
    assertFalse(new MetricsConfig(null, 60_000L, "*", "", false).isEnabled());
  }

  @Test
  void isEnabled_returnsTrueForRealReporter() {
    assertTrue(new MetricsConfig("prometheus", 60_000L, "*", "", false).isEnabled());
    assertTrue(new MetricsConfig("datadog", 60_000L, "*", "", false).isEnabled());
    assertTrue(new MetricsConfig("otlp", 60_000L, "*", "", false).isEnabled());
  }

  @Test
  void fromEnvironment_producesNonNullRecord() {
    MetricsConfig c = MetricsConfig.fromEnvironment();
    assertNotNull(c);
    assertNotNull(c.reporterType());
    assertNotNull(c.consumerGroupFilter());
    assertNotNull(c.consumerGroupExclude());
  }

  @Test
  void fromEnvironment_excludeIsConsumableByGroupFilter() {
    // Whatever the env yields, GroupFilter must accept it without throwing.
    MetricsConfig c = MetricsConfig.fromEnvironment();
    GroupFilter f = new GroupFilter(c.consumerGroupFilter(), c.consumerGroupExclude());
    assertNotNull(f.includeDescription());
    assertNotNull(f.excludeDescription());
  }
}
