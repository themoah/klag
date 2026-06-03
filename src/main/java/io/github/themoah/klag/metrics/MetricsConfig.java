package io.github.themoah.klag.metrics;

/**
 * Configuration for metrics collection and reporting.
 */
public record MetricsConfig(
  String reporterType,
  long collectionIntervalMs,
  String consumerGroupFilter,
  String consumerGroupExclude,
  boolean jvmMetricsEnabled,
  double lagTrendDeadband
) {

  private static final String DEFAULT_REPORTER = "none";
  private static final long DEFAULT_INTERVAL_MS = 60_000L;
  private static final String DEFAULT_FILTER = "*";
  private static final String DEFAULT_EXCLUDE = "";
  private static final double DEFAULT_LAG_TREND_DEADBAND = 1.0;

  /**
   * Convenience constructor defaulting the lag-trend deadband.
   */
  public MetricsConfig(
    String reporterType,
    long collectionIntervalMs,
    String consumerGroupFilter,
    String consumerGroupExclude,
    boolean jvmMetricsEnabled
  ) {
    this(reporterType, collectionIntervalMs, consumerGroupFilter, consumerGroupExclude,
      jvmMetricsEnabled, DEFAULT_LAG_TREND_DEADBAND);
  }

  /**
   * Loads configuration from environment variables.
   */
  public static MetricsConfig fromEnvironment() {
    String reporter = System.getenv().getOrDefault("METRICS_REPORTER", DEFAULT_REPORTER);
    long interval = parseLong("METRICS_INTERVAL_MS", DEFAULT_INTERVAL_MS);
    String filter = System.getenv().getOrDefault("METRICS_GROUP_FILTER", DEFAULT_FILTER);
    String exclude = System.getenv().getOrDefault("METRICS_GROUP_EXCLUDE", DEFAULT_EXCLUDE);
    boolean jvmEnabled = Boolean.parseBoolean(
      System.getenv().getOrDefault("METRICS_JVM_ENABLED", "false")
    );
    double lagTrendDeadband = parseDouble("LAG_TREND_DEADBAND_MSG_PER_SEC",
      DEFAULT_LAG_TREND_DEADBAND);

    return new MetricsConfig(reporter, interval, filter, exclude, jvmEnabled, lagTrendDeadband);
  }

  /**
   * Returns true if metrics reporting is enabled.
   */
  public boolean isEnabled() {
    return reporterType != null && !reporterType.isBlank() && !reporterType.equals("none");
  }

  private static long parseLong(String envVar, long defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static double parseDouble(String envVar, double defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
