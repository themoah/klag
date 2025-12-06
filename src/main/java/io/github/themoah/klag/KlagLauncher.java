package io.github.themoah.klag;

import io.github.themoah.klag.config.VertxConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom launcher for Klag with virtual threads support.
 */
public class KlagLauncher {

  private static final Logger log = LoggerFactory.getLogger(KlagLauncher.class);

  public static void main(String[] args) {
    VertxOptions vertxOptions = VertxConfig.createVertxOptions();
    Vertx vertx = Vertx.vertx(vertxOptions);

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
