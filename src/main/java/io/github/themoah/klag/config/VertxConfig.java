package io.github.themoah.klag.config;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x configuration with optional virtual threads support.
 * Enable via VERTX_USE_VIRTUAL_THREADS=true environment variable.
 */
public class VertxConfig {

  private static final Logger log = LoggerFactory.getLogger(VertxConfig.class);
  private static final String ENV_USE_VIRTUAL_THREADS = "VERTX_USE_VIRTUAL_THREADS";

  public static VertxOptions createVertxOptions() {
    VertxOptions options = new VertxOptions();
    options.setPreferNativeTransport(true);
    return options;
  }

  public static DeploymentOptions createDeploymentOptions() {
    DeploymentOptions options = new DeploymentOptions();
    if (isVirtualThreadsEnabled()) {
      log.info("Virtual threads enabled for verticle deployment");
      options.setThreadingModel(ThreadingModel.VIRTUAL_THREAD);
    } else {
      log.info("Using default event-loop threading model");
    }
    return options;
  }

  public static boolean isVirtualThreadsEnabled() {
    String value = System.getenv(ENV_USE_VIRTUAL_THREADS);
    return "true".equalsIgnoreCase(value);
  }
}
