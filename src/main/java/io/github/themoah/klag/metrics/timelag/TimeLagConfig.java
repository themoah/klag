package io.github.themoah.klag.metrics.timelag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for time-based lag estimation.
 *
 * @param enabled whether time lag estimation is enabled
 * @param minLagMessages minimum lag messages required before reporting time estimates
 */
public record TimeLagConfig(
  boolean enabled,
  long minLagMessages
) {

  private static final Logger log = LoggerFactory.getLogger(TimeLagConfig.class);

  private static final boolean DEFAULT_ENABLED = true;
  private static final long DEFAULT_MIN_LAG_MESSAGES = 100;

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>TIME_LAG_ENABLED - Enable/disable time lag estimation (default: true)</li>
   *   <li>TIME_LAG_MIN_MESSAGES - Minimum lag messages to report time estimates (default: 100)</li>
   * </ul>
   */
  public static TimeLagConfig fromEnvironment() {
    boolean enabled = parseBoolean("TIME_LAG_ENABLED", DEFAULT_ENABLED);
    long minLagMessages = parseLong("TIME_LAG_MIN_MESSAGES", DEFAULT_MIN_LAG_MESSAGES);

    TimeLagConfig config = new TimeLagConfig(enabled, minLagMessages);
    log.info("Time lag config: enabled={}, minLagMessages={}", enabled, minLagMessages);

    return config;
  }

  private static boolean parseBoolean(String envVar, boolean defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
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
