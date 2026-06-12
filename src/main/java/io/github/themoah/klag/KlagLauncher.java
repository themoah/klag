package io.github.themoah.klag;

import io.github.themoah.klag.config.VertxConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom launcher for Klag with virtual threads support.
 */
public class KlagLauncher {

  private static final Logger log = LoggerFactory.getLogger(KlagLauncher.class);

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  public static void main(String[] args) {
    VertxOptions vertxOptions = VertxConfig.createVertxOptions();
    Vertx vertx = Vertx.vertx(vertxOptions);

    // Unlike io.vertx.core.Launcher, a plain main() registers no shutdown hook. Without
    // one, SIGTERM (each Kubernetes pod stop / docker stop) exits the JVM without running
    // MainVerticle.stop(), dropping in-flight metric publishes and skipping the Kafka
    // admin client / HTTP server teardown.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("Shutdown signal received, closing Vert.x");
      try {
        vertx.close().toCompletionStage().toCompletableFuture()
          .get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.info("Shutdown complete");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        log.warn("Graceful shutdown did not complete cleanly: {}", e.toString());
      }
    }, "klag-shutdown"));

    DeploymentOptions deploymentOptions = VertxConfig.createDeploymentOptions();

    vertx.deployVerticle(new MainVerticle(), deploymentOptions)
      .onSuccess(id -> log.info("MainVerticle deployed with ID: {}", id))
      .onFailure(err -> {
        log.error("Failed to deploy MainVerticle", err);
        vertx.close();
        System.exit(1);
      });
  }
}
