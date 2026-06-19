package io.github.themoah.klag.metrics.freshness;

import io.github.themoah.klag.config.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for consumer commit freshness (staleness) tracking.
 *
 * @param enabled whether commit freshness tracking is enabled
 */
public record CommitFreshnessConfig(
  boolean enabled
) {

  private static final Logger log = LoggerFactory.getLogger(CommitFreshnessConfig.class);

  private static final boolean DEFAULT_ENABLED = true;

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>COMMIT_FRESHNESS_ENABLED - Enable/disable commit freshness tracking (default: true)</li>
   * </ul>
   */
  public static CommitFreshnessConfig fromEnvironment() {
    boolean enabled = Env.getBool("COMMIT_FRESHNESS_ENABLED", DEFAULT_ENABLED);

    CommitFreshnessConfig config = new CommitFreshnessConfig(enabled);
    log.info("Commit freshness config: enabled={}", enabled);

    return config;
  }
}
