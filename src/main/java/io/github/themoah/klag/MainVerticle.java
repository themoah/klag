package io.github.themoah.klag;

import io.github.themoah.klag.config.AppConfig;
import io.github.themoah.klag.health.HealthCheckHandler;
import io.github.themoah.klag.health.KafkaHealthMonitor;
import io.github.themoah.klag.kafka.KafkaClientConfig;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.kafka.KafkaClientServiceImpl;
import io.github.themoah.klag.metrics.MetricsCollector;
import io.github.themoah.klag.metrics.MetricsConfig;
import io.github.themoah.klag.metrics.MetricsReporter;
import io.github.themoah.klag.metrics.MicrometerConfig;
import io.github.themoah.klag.metrics.MicrometerReporter;
import io.github.themoah.klag.metrics.PrometheusHandler;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main verticle for Klag - Kafka Lag Exporter.
 * Initializes Kafka client, health monitoring, and HTTP server.
 */
public class MainVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

  private KafkaClientService kafkaClientService;
  private KafkaHealthMonitor healthMonitor;
  private MetricsCollector metricsCollector;
  private HttpServer httpServer;

  @Override
  public void start(Promise<Void> startPromise) {
    log.info("Starting Klag MainVerticle");

    AppConfig appConfig = AppConfig.fromEnvironment();
    MetricsConfig metricsConfig = MetricsConfig.fromEnvironment();
    KafkaClientConfig kafkaConfig = loadKafkaConfig();

    kafkaClientService = new KafkaClientServiceImpl(vertx, kafkaConfig);
    healthMonitor = new KafkaHealthMonitor(vertx, kafkaClientService, appConfig.healthCheckIntervalMs());

    Router router = Router.router(vertx);
    HealthCheckHandler healthHandler = new HealthCheckHandler(healthMonitor);
    healthHandler.registerRoutes(router);

    // Create metrics collector if enabled (also registers /metrics endpoint for Prometheus)
    metricsCollector = createMetricsCollector(metricsConfig, router);

    router.route().handler(ctx -> {
      ctx.response()
        .setStatusCode(404)
        .putHeader("content-type", "application/json")
        .end("{\"error\": \"Not Found\"}");
    });

    healthMonitor.start()
      .compose(v -> startMetricsCollector())
      .compose(v -> startHttpServer(router, appConfig.httpPort()))
      .onSuccess(server -> {
        httpServer = server;
        log.info("Klag started successfully on port {}", appConfig.httpPort());
        startPromise.complete();
      })
      .onFailure(err -> {
        log.error("Failed to start Klag", err);
        startPromise.fail(err);
      });
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    log.info("Stopping Klag MainVerticle");

    Future<Void> stopHealthMonitor = (healthMonitor != null)
      ? healthMonitor.stop()
      : Future.succeededFuture();

    Future<Void> stopMetricsCollector = (metricsCollector != null)
      ? metricsCollector.stop()
      : Future.succeededFuture();

    Future<Void> stopHttpServer = (httpServer != null)
      ? httpServer.close()
      : Future.succeededFuture();

    Future<Void> closeKafkaClient = (kafkaClientService != null)
      ? kafkaClientService.close()
      : Future.succeededFuture();

    stopHealthMonitor
      .compose(v -> stopMetricsCollector)
      .compose(v -> stopHttpServer)
      .compose(v -> closeKafkaClient)
      .onSuccess(v -> {
        log.info("Klag stopped successfully");
        stopPromise.complete();
      })
      .onFailure(err -> {
        log.error("Error during Klag shutdown", err);
        stopPromise.fail(err);
      });
  }

  private Future<HttpServer> startHttpServer(Router router, int port) {
    return vertx.createHttpServer()
      .requestHandler(router)
      .listen(port)
      .onSuccess(server -> log.info("HTTP server started on port {}", port))
      .onFailure(err -> log.error("Failed to start HTTP server", err));
  }

  private KafkaClientConfig loadKafkaConfig() {
    try {
      return KafkaClientConfig.fromClasspath();
    } catch (Exception e) {
      log.info("No classpath config found, loading from environment: {}", e.getMessage());
      return KafkaClientConfig.fromEnvironment();
    }
  }

  private MetricsCollector createMetricsCollector(MetricsConfig config, Router router) {
    if (!config.isEnabled()) {
      log.info("Metrics reporting is disabled");
      return null;
    }

    MeterRegistry registry = MicrometerConfig.createRegistry(config.reporterType());
    if (registry == null) {
      log.warn("Failed to create meter registry for type: {}", config.reporterType());
      return null;
    }

    // Bind JVM metrics if enabled
    if (config.jvmMetricsEnabled()) {
      MicrometerConfig.bindJvmMetrics(registry);
      log.info("JVM metrics enabled");
    }

    // Register Prometheus /metrics endpoint if using Prometheus reporter
    if (registry instanceof PrometheusMeterRegistry prometheusRegistry) {
      PrometheusHandler prometheusHandler = new PrometheusHandler(prometheusRegistry);
      prometheusHandler.registerRoutes(router);
    }

    // Load hot partition config
    HotPartitionConfig hotPartitionConfig = HotPartitionConfig.fromEnvironment();

    MetricsReporter reporter = new MicrometerReporter(registry);
    return new MetricsCollector(
      vertx,
      kafkaClientService,
      reporter,
      config.collectionIntervalMs(),
      config.consumerGroupFilter(),
      hotPartitionConfig
    );
  }

  private Future<Void> startMetricsCollector() {
    if (metricsCollector == null) {
      return Future.succeededFuture();
    }
    return metricsCollector.start();
  }
}
