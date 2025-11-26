package io.github.themoah.klag.metrics;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP handler for exposing Prometheus metrics endpoint.
 */
public class PrometheusHandler {

  private static final Logger log = LoggerFactory.getLogger(PrometheusHandler.class);
  private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

  private final PrometheusMeterRegistry registry;

  public PrometheusHandler(PrometheusMeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Registers the /metrics route on the router.
   */
  public void registerRoutes(Router router) {
    router.get("/metrics").handler(this::handleMetrics);
    log.info("Registered Prometheus metrics endpoint at /metrics");
  }

  private void handleMetrics(RoutingContext ctx) {
    String metrics = registry.scrape();
    ctx.response()
      .putHeader("content-type", PROMETHEUS_CONTENT_TYPE)
      .end(metrics);
  }
}
