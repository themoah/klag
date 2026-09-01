package io.github.themoah.klag;

import io.github.themoah.klag.config.AppConfig;
import io.github.themoah.klag.config.Env;
import io.github.themoah.klag.health.HealthCheckHandler;
import io.github.themoah.klag.health.KafkaHealthMonitor;
import io.github.themoah.klag.health.VersionHandler;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.kafka.KafkaClientServiceImpl;
import io.github.themoah.klag.kafka.KafkaClusterSpec;
import io.github.themoah.klag.kafka.KafkaClusters;
import io.github.themoah.klag.mcp.McpConfig;
import io.github.themoah.klag.mcp.McpHandler;
import io.github.themoah.klag.mcp.McpTools;
import io.github.themoah.klag.metrics.snapshot.SnapshotStore;
import io.github.themoah.klag.metrics.MetricsCollector;
import io.github.themoah.klag.metrics.MetricsConfig;
import io.github.themoah.klag.metrics.MicrometerConfig;
import io.github.themoah.klag.metrics.MicrometerReporter;
import io.github.themoah.klag.metrics.PrometheusHandler;
import io.github.themoah.klag.metrics.hotpartition.HotPartitionConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main verticle for Klag - Kafka Lag Exporter.
 * Initializes Kafka client(s), health monitoring, and HTTP server.
 */
public class MainVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

  private final List<KafkaClientService> kafkaClients = new ArrayList<>();
  private final List<KafkaHealthMonitor> healthMonitors = new ArrayList<>();
  private final List<MetricsCollector> metricsCollectors = new ArrayList<>();
  private MeterRegistry meterRegistry;
  private HttpServer httpServer;

  @Override
  public void start(Promise<Void> startPromise) {
    log.info("Starting Klag version {}, Vert.x version {}, Java version {}",
      VersionHandler.getVersion(), VersionHandler.getVertxVersion(), VersionHandler.getJavaVersion());

    AppConfig appConfig = AppConfig.fromEnvironment();
    MetricsConfig metricsConfig = MetricsConfig.fromEnvironment();
    List<KafkaClusterSpec> clusters = KafkaClusters.load();

    for (KafkaClusterSpec spec : clusters) {
      KafkaClientService client = new KafkaClientServiceImpl(vertx, spec.clientConfig());
      kafkaClients.add(client);
      healthMonitors.add(new KafkaHealthMonitor(
        vertx, client, appConfig.healthCheckIntervalMs(),
        spec.hasClusterName() ? spec.name() : null));
      log.info("Configured Kafka cluster name={} bootstrap={}",
        spec.hasClusterName() ? spec.name() : "(none)",
        spec.clientConfig().getBootstrapServers());
    }

    Router router = Router.router(vertx);
    HealthCheckHandler healthHandler = new HealthCheckHandler(healthMonitors);
    healthHandler.registerRoutes(router);
    VersionHandler versionHandler = new VersionHandler();
    versionHandler.registerRoutes(router);

    createMetricsCollectors(metricsConfig, router, clusters);
    registerMcpEndpoint(router);

    router.route().handler(ctx -> {
      ctx.response()
        .setStatusCode(404)
        .putHeader("content-type", "application/json")
        .end("{\"error\": \"Not Found\"}");
    });

    joinAll(healthMonitors.stream()
        .map(monitor -> monitor.start().otherwiseEmpty())
        .toList())
      .compose(v -> startMetricsCollectors())
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

    joinAll(healthMonitors.stream().map(KafkaHealthMonitor::stop).toList())
      .compose(v -> joinAll(metricsCollectors.stream().map(MetricsCollector::stop).toList()))
      .compose(v -> {
        if (meterRegistry != null) {
          meterRegistry.close();
          meterRegistry = null;
        }
        return Future.succeededFuture();
      })
      .compose(v -> httpServer != null ? httpServer.close() : Future.succeededFuture())
      .compose(v -> joinAll(kafkaClients.stream().map(KafkaClientService::close).toList()))
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

  private void createMetricsCollectors(
      MetricsConfig config, Router router, List<KafkaClusterSpec> clusters) {
    if (!config.isEnabled()) {
      log.info("Metrics reporting is disabled");
      return;
    }

    meterRegistry = MicrometerConfig.createRegistry(config.reporterType());
    if (meterRegistry == null) {
      log.warn("Failed to create meter registry for type: {}", config.reporterType());
      return;
    }

    if (config.jvmMetricsEnabled()) {
      MicrometerConfig.bindJvmMetrics(meterRegistry);
      log.info("JVM metrics enabled");
    }

    if (meterRegistry instanceof PrometheusMeterRegistry prometheusRegistry) {
      PrometheusHandler prometheusHandler = new PrometheusHandler(prometheusRegistry);
      prometheusHandler.registerRoutes(router);
    }

    HotPartitionConfig hotPartitionConfig = HotPartitionConfig.fromEnvironment();
    boolean memberLabelsEnabled = Env.getBool("CONSUMER_MEMBER_LABELS_ENABLED", true);

    for (int i = 0; i < clusters.size(); i++) {
      KafkaClusterSpec spec = clusters.get(i);
      MicrometerReporter reporter = new MicrometerReporter(
        meterRegistry,
        memberLabelsEnabled,
        spec.hasClusterName() ? spec.name() : "",
        false
      );
      MetricsCollector collector = new MetricsCollector(
        vertx,
        kafkaClients.get(i),
        reporter,
        config.collectionIntervalMs(),
        spec.resolvedGroupFilter(config.consumerGroupFilter()),
        spec.resolvedGroupExclude(config.consumerGroupExclude()),
        hotPartitionConfig
      );
      collector.setLagTrendDeadband(config.lagTrendDeadband());
      metricsCollectors.add(collector);
    }
  }

  private void registerMcpEndpoint(Router router) {
    McpConfig mcpConfig = McpConfig.fromEnvironment();
    if (!mcpConfig.enabled()) {
      return;
    }

    SnapshotStore snapshotStore = new SnapshotStore();
    if (!metricsCollectors.isEmpty()) {
      // One snapshot store cannot merge N clusters yet; expose the first cluster only.
      metricsCollectors.get(0).setSnapshotStore(snapshotStore);
      if (metricsCollectors.size() > 1) {
        log.warn("MCP endpoint is bound to the first Kafka cluster only");
      }
    } else {
      log.warn("MCP endpoint enabled but metrics collection is disabled; "
        + "tools will report 'snapshot not ready' until metrics are enabled (METRICS_REPORTER)");
    }

    McpTools mcpTools = new McpTools(snapshotStore);
    new McpHandler(mcpConfig, mcpTools).registerRoutes(router);
  }

  private Future<Void> startMetricsCollectors() {
    return joinAll(metricsCollectors.stream().map(MetricsCollector::start).toList());
  }

  private static Future<Void> joinAll(List<Future<Void>> futures) {
    if (futures.isEmpty()) {
      return Future.succeededFuture();
    }
    return Future.all(futures).mapEmpty();
  }
}
