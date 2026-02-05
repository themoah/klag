package io.github.themoah.klag.metrics.timelag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for time-based lag estimation.
 *
 * @param enabled whether time lag estimation is enabled
 * @param minLagMessages minimum lag messages required before reporting time estimates
 * @param interpolationBufferSize number of offset/timestamp points per partition for interpolation
 * @param staleProducerThresholdMs time in ms before a producer with no progress is considered stale
 */
public record TimeLagConfig(
  boolean enabled,
  long minLagMessages,
  int interpolationBufferSize,
  long staleProducerThresholdMs
) {

  private static final Logger log = LoggerFactory.getLogger(TimeLagConfig.class);

  private static final boolean DEFAULT_ENABLED = true;
  private static final long DEFAULT_MIN_LAG_MESSAGES = 100;
  private static final int DEFAULT_INTERPOLATION_BUFFER_SIZE = 60;
  private static final long DEFAULT_STALE_PRODUCER_THRESHOLD_MS = 180000;  // 3 minutes

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>TIME_LAG_ENABLED - Enable/disable time lag estimation (default: true)</li>
   *   <li>TIME_LAG_MIN_MESSAGES - Minimum lag messages to report time estimates (default: 100)</li>
   *   <li>TIME_LAG_INTERPOLATION_BUFFER_SIZE - Number of offset/timestamp points per partition (default: 60)</li>
   *   <li>TIME_LAG_STALE_PRODUCER_THRESHOLD_MS - Time before producer considered stale (default: 180000)</li>
   * </ul>
   */
  public static TimeLagConfig fromEnvironment() {
    boolean enabled = parseBoolean("TIME_LAG_ENABLED", DEFAULT_ENABLED);
    long minLagMessages = parseLong("TIME_LAG_MIN_MESSAGES", DEFAULT_MIN_LAG_MESSAGES);
    int interpolationBufferSize = parseInt("TIME_LAG_INTERPOLATION_BUFFER_SIZE", DEFAULT_INTERPOLATION_BUFFER_SIZE);
    long staleProducerThresholdMs = parseLong("TIME_LAG_STALE_PRODUCER_THRESHOLD_MS", DEFAULT_STALE_PRODUCER_THRESHOLD_MS);

    TimeLagConfig config = new TimeLagConfig(enabled, minLagMessages, interpolationBufferSize, staleProducerThresholdMs);
    log.info("Time lag config: enabled={}, minLagMessages={}, interpolationBufferSize={}, staleProducerThresholdMs={}",
      enabled, minLagMessages, interpolationBufferSize, staleProducerThresholdMs);

    return config;
  }

  private static boolean parseBoolean(String envVar, boolean defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
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

  private static long parseLong(String envVar, long defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", envVar, value, defaultValue);
      return defaultValue;
    }
  }
}
