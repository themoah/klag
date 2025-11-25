package io.github.themoah.klag;

import io.github.themoah.klag.config.AppConfig;
import io.github.themoah.klag.health.HealthCheckHandler;
import io.github.themoah.klag.health.KafkaHealthMonitor;
import io.github.themoah.klag.kafka.KafkaClientConfig;
import io.github.themoah.klag.kafka.KafkaClientService;
import io.github.themoah.klag.kafka.KafkaClientServiceImpl;
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
  private HttpServer httpServer;

  @Override
  public void start(Promise<Void> startPromise) {
    log.info("Starting Klag MainVerticle");

    AppConfig appConfig = AppConfig.fromEnvironment();
    KafkaClientConfig kafkaConfig = loadKafkaConfig();

    kafkaClientService = new KafkaClientServiceImpl(vertx, kafkaConfig);
    healthMonitor = new KafkaHealthMonitor(vertx, kafkaClientService, appConfig.healthCheckIntervalMs());

    Router router = Router.router(vertx);
    HealthCheckHandler healthHandler = new HealthCheckHandler(healthMonitor);
    healthHandler.registerRoutes(router);

    router.route().handler(ctx -> {
      ctx.response()
        .setStatusCode(404)
        .putHeader("content-type", "application/json")
        .end("{\"error\": \"Not Found\"}");
    });

    healthMonitor.start()
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

    Future<Void> stopHttpServer = (httpServer != null)
      ? httpServer.close()
      : Future.succeededFuture();

    Future<Void> closeKafkaClient = (kafkaClientService != null)
      ? kafkaClientService.close()
      : Future.succeededFuture();

    stopHealthMonitor
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
}
