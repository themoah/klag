package io.github.themoah.klag.health;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP handler for health check endpoints.
 */
public class HealthCheckHandler {

  private static final Logger log = LoggerFactory.getLogger(HealthCheckHandler.class);
  private static final String CONTENT_TYPE_JSON = "application/json";

  private final List<KafkaHealthMonitor> healthMonitors;

  public HealthCheckHandler(KafkaHealthMonitor healthMonitor) {
    this(List.of(healthMonitor));
  }

  public HealthCheckHandler(List<KafkaHealthMonitor> healthMonitors) {
    this.healthMonitors = List.copyOf(healthMonitors);
  }

  /**
   * Registers health check routes on the router.
   *
   * @param router the Vert.x router
   */
  public void registerRoutes(Router router) {
    router.get("/healthz").handler(this::handleLiveness);
    router.get("/readyz").handler(this::handleReadiness);
    log.info("Health check routes registered: /healthz, /readyz");
  }

  /**
   * Liveness probe - checks if HTTP server is responding.
   * Always returns 200 if the server is up.
   */
  private void handleLiveness(RoutingContext ctx) {
    HealthCheckResponse response = HealthCheckResponse.liveness();
    ctx.response()
      .putHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_JSON)
      .setStatusCode(200)
      .end(response.toJson().encode());
  }

  /**
   * Readiness probe — Kafka connection status.
   * Returns 200 if any configured cluster is up, 503 if every cluster is down,
   * so one unreachable cluster does not take the Prometheus scrape target down.
   */
  private void handleReadiness(RoutingContext ctx) {
    boolean connected = healthMonitors.stream().anyMatch(KafkaHealthMonitor::isKafkaConnected);
    List<HealthCheckResponse.ClusterHealth> clusters = healthMonitors.stream()
      .map(monitor -> new HealthCheckResponse.ClusterHealth(
        monitor.clusterName(),
        monitor.isKafkaConnected() ? "connected" : "disconnected"))
      .toList();
    HealthCheckResponse response = HealthCheckResponse.readiness(clusters);
    int statusCode = connected ? 200 : 503;

    ctx.response()
      .putHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_JSON)
      .setStatusCode(statusCode)
      .end(response.toJson().encode());
  }
}
