package io.github.themoah.klag.metrics.hotpartition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for hot partition detection.
 *
 * @param enabled whether hot partition detection is enabled
 * @param sigmaMultiplier standard deviation multiplier for outlier detection (default 2.0)
 * @param minPartitions minimum partitions in a topic to enable detection (default 3)
 * @param minSamples minimum samples needed for throughput detection (default 3)
 * @param bufferSize number of samples to retain for throughput calculation (default 20)
 */
public record HotPartitionConfig(
  boolean enabled,
  double sigmaMultiplier,
  int minPartitions,
  int minSamples,
  int bufferSize
) {

  private static final Logger log = LoggerFactory.getLogger(HotPartitionConfig.class);

  private static final boolean DEFAULT_ENABLED = true;
  private static final double DEFAULT_SIGMA_MULTIPLIER = 2.0;
  private static final int DEFAULT_MIN_PARTITIONS = 3;
  private static final int DEFAULT_MIN_SAMPLES = 3;
  private static final int DEFAULT_BUFFER_SIZE = 20;

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>HOT_PARTITION_ENABLED - Enable/disable detection (default: true)</li>
   *   <li>HOT_PARTITION_SIGMA_MULTIPLIER - Standard deviations for outlier threshold (default: 2.0)</li>
   *   <li>HOT_PARTITION_MIN_PARTITIONS - Minimum partitions per topic for detection (default: 3)</li>
   *   <li>HOT_PARTITION_MIN_SAMPLES - Minimum samples for throughput calculation (default: 3)</li>
   *   <li>HOT_PARTITION_BUFFER_SIZE - Samples to retain per partition (default: 20)</li>
   * </ul>
   */
  public static HotPartitionConfig fromEnvironment() {
    boolean enabled = parseBoolean("HOT_PARTITION_ENABLED", DEFAULT_ENABLED);
    double sigma = parseDouble("HOT_PARTITION_SIGMA_MULTIPLIER", DEFAULT_SIGMA_MULTIPLIER);
    int minPartitions = parseInt("HOT_PARTITION_MIN_PARTITIONS", DEFAULT_MIN_PARTITIONS);
    int minSamples = parseInt("HOT_PARTITION_MIN_SAMPLES", DEFAULT_MIN_SAMPLES);
    int bufferSize = parseInt("HOT_PARTITION_BUFFER_SIZE", DEFAULT_BUFFER_SIZE);

    HotPartitionConfig config = new HotPartitionConfig(enabled, sigma, minPartitions, minSamples, bufferSize);
    log.info("Hot partition config: enabled={}, sigma={}, minPartitions={}, minSamples={}, bufferSize={}",
      enabled, sigma, minPartitions, minSamples, bufferSize);

    return config;
  }

  private static boolean parseBoolean(String envVar, boolean defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }

  private static double parseDouble(String envVar, double defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", envVar, value, defaultValue);
      return defaultValue;
    }
  }

  private static int parseInt(String envVar, int defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", envVar, value, defaultValue);
      return defaultValue;
    }
  }
}
