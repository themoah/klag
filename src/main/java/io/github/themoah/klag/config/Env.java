package io.github.themoah.klag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads typed values from configuration with defaults.
 * Each key is resolved in order (first non-blank wins): environment variable {@code NAME},
 * then JVM system property {@code -DNAME}, then dotted-lowercase property {@code -Dname.dotted}
 * (e.g. {@code HTTP_PORT} -> {@code -Dhttp.port}). This lets jar/native users set config via
 * {@code -D} flags while env vars (Docker/k8s) keep precedence.
 * A null or blank value yields the default; an unparseable value logs a warning
 * and falls back to the default.
 */
public final class Env {

  private static final Logger log = LoggerFactory.getLogger(Env.class);

  private Env() {}

  /**
   * Resolves a config value from env var, then -DNAME, then -Dname.dotted. Null if none set.
   */
  static String resolve(String name) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) {
      return value;
    }
    value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }
    return System.getProperty(name.toLowerCase(java.util.Locale.ROOT).replace('_', '.'));
  }

  public static int getInt(String name, int defaultValue) {
    String value = resolve(name);
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
    String value = resolve(name);
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
    String value = resolve(name);
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
    String value = resolve(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
    if ("true".equals(normalized)) {
      return true;
    }
    if ("false".equals(normalized)) {
      return false;
    }
    log.warn("Invalid value for {}: '{}', using default: {}", name, value, defaultValue);
    return defaultValue;
  }
}
