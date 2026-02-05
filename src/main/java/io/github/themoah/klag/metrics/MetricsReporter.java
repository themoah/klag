package io.github.themoah.klag.metrics;

import io.github.themoah.klag.model.ConsumerGroupLag;
import io.github.themoah.klag.model.RetentionRisk;
import io.vertx.core.Future;
import java.util.List;
import java.util.Set;

/**
 * Interface for reporting Kafka lag metrics to external systems.
 */
public interface MetricsReporter {

  /**
   * Reports consumer group lag metrics.
   *
   * @param lagData list of consumer group lag data
   * @return Future that completes when metrics are recorded
   */
  Future<Void> reportLag(List<ConsumerGroupLag> lagData);

  /**
   * Reports retention risk percentage metrics (DLP).
   *
   * @param risks list of retention risk data
   * @param activeKeys set to populate with active gauge keys (can be null)
   */
  default void reportRetentionPercent(List<RetentionRisk> risks, Set<String> activeKeys) {
    // Default no-op implementation for non-Micrometer reporters
  }

  /**
   * Starts the reporter.
   *
   * @return Future that completes when started
   */
  Future<Void> start();

  /**
   * Closes the reporter and releases resources.
   *
   * @return Future that completes when closed
   */
  Future<Void> close();
}
