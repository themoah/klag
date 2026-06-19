package io.github.themoah.klag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads typed values from environment variables with defaults.
 * A null or blank value yields the default; an unparseable value logs a warning
 * and falls back to the default.
 */
public final class Env {

  private static final Logger log = LoggerFactory.getLogger(Env.class);

  private Env() {}

  public static int getInt(String name, int defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", name, value, defaultValue);
      return defaultValue;
    }
  }

  public static long getLong(String name, long defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", name, value, defaultValue);
      return defaultValue;
    }
  }

  public static double getDouble(String name, double defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      log.warn("Invalid value for {}: '{}', using default: {}", name, value, defaultValue);
      return defaultValue;
    }
  }

  public static boolean getBool(String name, boolean defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }
}
