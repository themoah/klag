package io.github.themoah.klag.metrics;

import io.github.themoah.klag.config.EnvConfig;

/**
 * Configuration for metrics collection and reporting.
 */
public record MetricsConfig(
  String reporterType,
  long collectionIntervalMs,
  String consumerGroupFilter,
  boolean jvmMetricsEnabled
) {

  private static final String DEFAULT_REPORTER = "none";
  private static final long DEFAULT_INTERVAL_MS = 60_000L;
  private static final String DEFAULT_FILTER = "*";

  /**
   * Loads configuration from environment variables.
   */
  public static MetricsConfig fromEnvironment() {
    String reporter = EnvConfig.getString("METRICS_REPORTER", DEFAULT_REPORTER);
    long interval = EnvConfig.getLong("METRICS_INTERVAL_MS", DEFAULT_INTERVAL_MS);
    String filter = EnvConfig.getString("METRICS_GROUP_FILTER", DEFAULT_FILTER);
    boolean jvmEnabled = EnvConfig.getBoolean("METRICS_JVM_ENABLED", false);

    return new MetricsConfig(reporter, interval, filter, jvmEnabled);
  }

  /**
   * Returns true if metrics reporting is enabled.
   */
  public boolean isEnabled() {
    return reporterType != null && !reporterType.isBlank() && !reporterType.equals("none");
  }
}
