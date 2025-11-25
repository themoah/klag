package io.github.themoah.klag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application configuration loaded from environment variables.
 *
 * @param httpPort HTTP server port
 * @param healthCheckIntervalMs Kafka health check interval in milliseconds
 */
public record AppConfig(
  int httpPort,
  long healthCheckIntervalMs
) {
  private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

  private static final int DEFAULT_HTTP_PORT = 8888;
  private static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 30_000L;

  /**
   * Loads configuration from environment variables with defaults.
   *
   * @return AppConfig instance
   */
  public static AppConfig fromEnvironment() {
    int port = getEnvInt("HTTP_PORT", DEFAULT_HTTP_PORT);
    long interval = getEnvLong("KAFKA_HEALTH_CHECK_INTERVAL_MS", DEFAULT_HEALTH_CHECK_INTERVAL_MS);

    log.info("AppConfig loaded: httpPort={}, healthCheckIntervalMs={}", port, interval);
    return new AppConfig(port, interval);
  }

  private static int getEnvInt(String name, int defaultValue) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        log.warn("Invalid integer for {}: {}, using default: {}", name, value, defaultValue);
      }
    }
    return defaultValue;
  }

  private static long getEnvLong(String name, long defaultValue) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        log.warn("Invalid long for {}: {}, using default: {}", name, value, defaultValue);
      }
    }
    return defaultValue;
  }
}
