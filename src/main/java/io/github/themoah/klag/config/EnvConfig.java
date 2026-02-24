package io.github.themoah.klag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utility for parsing environment variables with defaults.
 */
public final class EnvConfig {

  private static final Logger log = LoggerFactory.getLogger(EnvConfig.class);

  private EnvConfig() {}

  public static String getString(String envVar, String defaultValue) {
    return System.getenv().getOrDefault(envVar, defaultValue);
  }

  public static int getInt(String envVar, int defaultValue) {
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

  public static long getLong(String envVar, long defaultValue) {
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

  public static double getDouble(String envVar, double defaultValue) {
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

  public static boolean getBoolean(String envVar, boolean defaultValue) {
    String value = System.getenv(envVar);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }
}
