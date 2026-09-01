package io.github.themoah.klag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.health.HealthCheckResponse;
import io.github.themoah.klag.health.HealthStatus;
import io.github.themoah.klag.health.VersionInfoResponse;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for health check components.
 */
public class TestMainVerticle {

  @Test
  void healthStatus_values() {
    assertEquals("UP", HealthStatus.UP.getValue());
    assertEquals("DOWN", HealthStatus.DOWN.getValue());
  }

  @Test
  void healthCheckResponse_liveness() {
    HealthCheckResponse response = HealthCheckResponse.liveness();

    assertEquals(HealthStatus.UP, response.status());
    assertNull(response.kafka());

    JsonObject json = response.toJson();
    assertEquals("UP", json.getString("status"));
    assertFalse(json.containsKey("kafka"));
    assertFalse(json.containsKey("clusters"));
  }

  @Test
  void healthCheckResponse_readiness_connected() {
    HealthCheckResponse response = HealthCheckResponse.readiness(true);

    assertEquals(HealthStatus.UP, response.status());
    assertEquals("connected", response.kafka());

    JsonObject json = response.toJson();
    assertEquals("UP", json.getString("status"));
    assertEquals("connected", json.getString("kafka"));
    assertEquals(1, json.getJsonArray("clusters").size());
    assertEquals("connected", json.getJsonArray("clusters").getJsonObject(0).getString("kafka"));
    assertFalse(json.getJsonArray("clusters").getJsonObject(0).containsKey("name"));
  }

  @Test
  void healthCheckResponse_readiness_disconnected() {
    HealthCheckResponse response = HealthCheckResponse.readiness(false);

    assertEquals(HealthStatus.DOWN, response.status());
    assertEquals("disconnected", response.kafka());

    JsonObject json = response.toJson();
    assertEquals("DOWN", json.getString("status"));
    assertEquals("disconnected", json.getString("kafka"));
    assertEquals("disconnected", json.getJsonArray("clusters").getJsonObject(0).getString("kafka"));
  }

  @Test
  void healthCheckResponse_readiness_per_cluster() {
    HealthCheckResponse response = HealthCheckResponse.readiness(List.of(
      new HealthCheckResponse.ClusterHealth("msk-a", "connected"),
      new HealthCheckResponse.ClusterHealth("msk-b", "disconnected")));

    assertEquals(HealthStatus.UP, response.status());
    assertEquals("connected", response.kafka());

    JsonObject json = response.toJson();
    assertEquals(2, json.getJsonArray("clusters").size());
    assertEquals("msk-a", json.getJsonArray("clusters").getJsonObject(0).getString("name"));
    assertEquals("connected", json.getJsonArray("clusters").getJsonObject(0).getString("kafka"));
    assertEquals("msk-b", json.getJsonArray("clusters").getJsonObject(1).getString("name"));
    assertEquals("disconnected", json.getJsonArray("clusters").getJsonObject(1).getString("kafka"));
  }

  @Test
  void healthCheckResponse_json_format() {
    HealthCheckResponse liveness = HealthCheckResponse.liveness();
    assertEquals("{\"status\":\"UP\"}", liveness.toJson().encode());

    HealthCheckResponse readinessUp = HealthCheckResponse.readiness(true);
    assertTrue(readinessUp.toJson().encode().contains("\"status\":\"UP\""));
    assertTrue(readinessUp.toJson().encode().contains("\"kafka\":\"connected\""));
    assertTrue(readinessUp.toJson().encode().contains("\"clusters\""));

    HealthCheckResponse readinessDown = HealthCheckResponse.readiness(false);
    assertTrue(readinessDown.toJson().encode().contains("\"status\":\"DOWN\""));
    assertTrue(readinessDown.toJson().encode().contains("\"kafka\":\"disconnected\""));
  }

  @Test
  void versionInfoResponse_json_format() {
    VersionInfoResponse response = new VersionInfoResponse(
      "0.1.9",
      "4.5.22",
      "21");

    assertTrue(response.toJson().encode().contains("\"version\":\"0.1.9\""));
    assertTrue(response.toJson().encode().contains("\"vertxVersion\":\"4.5.22\""));
    assertTrue(response.toJson().encode().contains("\"javaVersion\":\"21\""));
  }
}
