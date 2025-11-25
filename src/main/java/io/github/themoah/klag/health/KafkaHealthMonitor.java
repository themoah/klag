package io.github.themoah.klag.health;

import io.github.themoah.klag.kafka.KafkaClientService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors Kafka connection health via periodic heartbeat.
 * Thread-safe status tracking using AtomicReference.
 */
public class KafkaHealthMonitor {

  private static final Logger log = LoggerFactory.getLogger(KafkaHealthMonitor.class);

  private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L;

  private final Vertx vertx;
  private final KafkaClientService kafkaClient;
  private final long heartbeatIntervalMs;
  private final AtomicReference<HealthStatus> kafkaStatus;

  private Long timerId;

  public KafkaHealthMonitor(Vertx vertx, KafkaClientService kafkaClient) {
    this(vertx, kafkaClient, DEFAULT_HEARTBEAT_INTERVAL_MS);
  }

  public KafkaHealthMonitor(Vertx vertx, KafkaClientService kafkaClient, long heartbeatIntervalMs) {
    this.vertx = vertx;
    this.kafkaClient = kafkaClient;
    this.heartbeatIntervalMs = heartbeatIntervalMs;
    this.kafkaStatus = new AtomicReference<>(HealthStatus.DOWN);
  }

  /**
   * Starts the health monitor with initial check and periodic heartbeat.
   *
   * @return Future that completes when initial health check finishes
   */
  public Future<Void> start() {
    log.info("Starting Kafka health monitor with heartbeat interval: {}ms", heartbeatIntervalMs);

    return performHealthCheck()
      .onComplete(ar -> {
        timerId = vertx.setPeriodic(heartbeatIntervalMs, id -> performHealthCheck());
        log.info("Kafka health monitor started, timer ID: {}", timerId);
      })
      .mapEmpty();
  }

  /**
   * Stops the health monitor and cancels periodic timer.
   *
   * @return Future that completes when stopped
   */
  public Future<Void> stop() {
    log.info("Stopping Kafka health monitor");
    if (timerId != null) {
      vertx.cancelTimer(timerId);
      timerId = null;
    }
    kafkaStatus.set(HealthStatus.DOWN);
    return Future.succeededFuture();
  }

  /**
   * Returns current Kafka connection status.
   *
   * @return current HealthStatus
   */
  public HealthStatus getKafkaStatus() {
    return kafkaStatus.get();
  }

  /**
   * Returns true if Kafka is connected.
   *
   * @return true if connected
   */
  public boolean isKafkaConnected() {
    return kafkaStatus.get() == HealthStatus.UP;
  }

  /**
   * Performs a health check by describing cluster (lightweight metadata operation).
   */
  private Future<Void> performHealthCheck() {
    log.debug("Performing Kafka health check");

    return kafkaClient.describeCluster()
      .onSuccess(clusterId -> {
        HealthStatus previous = kafkaStatus.getAndSet(HealthStatus.UP);
        if (previous == HealthStatus.DOWN) {
          log.info("Kafka connection restored, cluster ID: {}", clusterId);
        } else {
          log.debug("Kafka health check passed, cluster ID: {}", clusterId);
        }
      })
      .onFailure(err -> {
        HealthStatus previous = kafkaStatus.getAndSet(HealthStatus.DOWN);
        if (previous == HealthStatus.UP) {
          log.warn("Kafka connection lost: {}", err.getMessage());
        } else {
          log.debug("Kafka health check failed: {}", err.getMessage());
        }
      })
      .mapEmpty();
  }
}
