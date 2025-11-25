package io.github.themoah.klag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.themoah.klag.health.HealthCheckResponse;
import io.github.themoah.klag.health.HealthStatus;
import io.vertx.core.json.JsonObject;
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
  }

  @Test
  void healthCheckResponse_readiness_connected() {
    HealthCheckResponse response = HealthCheckResponse.readiness(true);

    assertEquals(HealthStatus.UP, response.status());
    assertEquals("connected", response.kafka());

    JsonObject json = response.toJson();
    assertEquals("UP", json.getString("status"));
    assertEquals("connected", json.getString("kafka"));
  }

  @Test
  void healthCheckResponse_readiness_disconnected() {
    HealthCheckResponse response = HealthCheckResponse.readiness(false);

    assertEquals(HealthStatus.DOWN, response.status());
    assertEquals("disconnected", response.kafka());

    JsonObject json = response.toJson();
    assertEquals("DOWN", json.getString("status"));
    assertEquals("disconnected", json.getString("kafka"));
  }

  @Test
  void healthCheckResponse_json_format() {
    HealthCheckResponse liveness = HealthCheckResponse.liveness();
    assertEquals("{\"status\":\"UP\"}", liveness.toJson().encode());

    HealthCheckResponse readinessUp = HealthCheckResponse.readiness(true);
    assertTrue(readinessUp.toJson().encode().contains("\"status\":\"UP\""));
    assertTrue(readinessUp.toJson().encode().contains("\"kafka\":\"connected\""));

    HealthCheckResponse readinessDown = HealthCheckResponse.readiness(false);
    assertTrue(readinessDown.toJson().encode().contains("\"status\":\"DOWN\""));
    assertTrue(readinessDown.toJson().encode().contains("\"kafka\":\"disconnected\""));
  }
}
