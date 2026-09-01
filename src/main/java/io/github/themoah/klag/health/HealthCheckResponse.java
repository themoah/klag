package io.github.themoah.klag.health;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Immutable health check response.
 *
 * @param status overall health status
 * @param kafka Kafka connection status (null for liveness check)
 * @param clusters per-cluster Kafka status (empty for liveness)
 */
public record HealthCheckResponse(
  HealthStatus status,
  String kafka,
  List<ClusterHealth> clusters
) {

  /**
   * Kafka connectivity for one configured cluster.
   *
   * @param name optional cluster name; omitted from JSON when blank
   * @param kafka {@code connected} or {@code disconnected}
   */
  public record ClusterHealth(String name, String kafka) {
    JsonObject toJson() {
      JsonObject json = new JsonObject().put("kafka", kafka);
      if (name != null && !name.isBlank()) {
        json.put("name", name);
      }
      return json;
    }
  }

  /**
   * Creates a liveness response (HTTP server only).
   *
   * @return HealthCheckResponse with UP status
   */
  public static HealthCheckResponse liveness() {
    return new HealthCheckResponse(HealthStatus.UP, null, List.of());
  }

  /**
   * Creates a readiness response with Kafka status.
   *
   * @param kafkaConnected true if Kafka is connected
   * @return HealthCheckResponse with appropriate status
   */
  public static HealthCheckResponse readiness(boolean kafkaConnected) {
    String kafka = kafkaConnected ? "connected" : "disconnected";
    return readiness(List.of(new ClusterHealth(null, kafka)));
  }

  /**
   * Creates a readiness response from per-cluster Kafka status.
   * Overall {@code kafka} is connected when any cluster is connected.
   */
  public static HealthCheckResponse readiness(List<ClusterHealth> clusters) {
    List<ClusterHealth> copy = List.copyOf(clusters);
    boolean anyConnected = copy.stream().anyMatch(c -> "connected".equals(c.kafka()));
    HealthStatus status = anyConnected ? HealthStatus.UP : HealthStatus.DOWN;
    String kafka = anyConnected ? "connected" : "disconnected";
    return new HealthCheckResponse(status, kafka, copy);
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
    if (clusters != null && !clusters.isEmpty()) {
      JsonArray array = new JsonArray();
      for (ClusterHealth cluster : clusters) {
        array.add(cluster.toJson());
      }
      json.put("clusters", array);
    }
    return json;
  }
}
