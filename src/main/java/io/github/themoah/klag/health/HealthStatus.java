package io.github.themoah.klag.health;

/**
 * Represents the health status of a component.
 */
public enum HealthStatus {
  UP("UP"),
  DOWN("DOWN");

  private final String value;

  HealthStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}
