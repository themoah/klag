package io.github.themoah.klag.health;

import io.vertx.core.json.JsonObject;

/**
 * Immutable health check response.
 *
 * @param status overall health status
 * @param kafka Kafka connection status (null for liveness check)
 */
public record HealthCheckResponse(
  HealthStatus status,
  String kafka
) {
  /**
   * Creates a liveness response (HTTP server only).
   *
   * @return HealthCheckResponse with UP status
   */
  public static HealthCheckResponse liveness() {
    return new HealthCheckResponse(HealthStatus.UP, null);
  }

  /**
   * Creates a readiness response with Kafka status.
   *
   * @param kafkaConnected true if Kafka is connected
   * @return HealthCheckResponse with appropriate status
   */
  public static HealthCheckResponse readiness(boolean kafkaConnected) {
    HealthStatus status = kafkaConnected ? HealthStatus.UP : HealthStatus.DOWN;
    String kafka = kafkaConnected ? "connected" : "disconnected";
    return new HealthCheckResponse(status, kafka);
  }

  /**
   * Converts to JSON for HTTP response.
   *
   * @return JsonObject representation
   */
  public JsonObject toJson() {
    JsonObject json = new JsonObject().put("status", status.getValue());
    if (kafka != null) {
      json.put("kafka", kafka);
    }
    return json;
  }
}
