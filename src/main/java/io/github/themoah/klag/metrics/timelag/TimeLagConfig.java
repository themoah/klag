package io.github.themoah.klag.metrics.timelag;

import io.github.themoah.klag.config.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for time-based lag estimation.
 *
 * @param enabled whether time lag estimation is enabled
 * @param minLagMessages minimum lag messages required before reporting time estimates
 * @param interpolationBufferSize number of offset/timestamp points per partition for interpolation
 * @param staleProducerThresholdMs time in ms before a producer with no progress is considered stale
 */
public record TimeLagConfig(
  boolean enabled,
  long minLagMessages,
  int interpolationBufferSize,
  long staleProducerThresholdMs
) {

  private static final Logger log = LoggerFactory.getLogger(TimeLagConfig.class);

  private static final boolean DEFAULT_ENABLED = true;
  private static final long DEFAULT_MIN_LAG_MESSAGES = 100;
  private static final int DEFAULT_INTERPOLATION_BUFFER_SIZE = 60;
  private static final long DEFAULT_STALE_PRODUCER_THRESHOLD_MS = 180000;  // 3 minutes

  /**
   * Loads configuration from environment variables.
   *
   * <p>Supported environment variables:
   * <ul>
   *   <li>TIME_LAG_ENABLED - Enable/disable time lag estimation (default: true)</li>
   *   <li>TIME_LAG_MIN_MESSAGES - Minimum lag messages to report time estimates (default: 100)</li>
   *   <li>TIME_LAG_INTERPOLATION_BUFFER_SIZE - Number of offset/timestamp points per partition (default: 60)</li>
   *   <li>TIME_LAG_STALE_PRODUCER_THRESHOLD_MS - Time before producer considered stale (default: 180000)</li>
   * </ul>
   */
  public static TimeLagConfig fromEnvironment() {
    boolean enabled = Env.getBool("TIME_LAG_ENABLED", DEFAULT_ENABLED);
    long minLagMessages = Env.getLong("TIME_LAG_MIN_MESSAGES", DEFAULT_MIN_LAG_MESSAGES);
    int interpolationBufferSize = Env.getInt("TIME_LAG_INTERPOLATION_BUFFER_SIZE", DEFAULT_INTERPOLATION_BUFFER_SIZE);
    long staleProducerThresholdMs = Env.getLong("TIME_LAG_STALE_PRODUCER_THRESHOLD_MS", DEFAULT_STALE_PRODUCER_THRESHOLD_MS);

    TimeLagConfig config = new TimeLagConfig(enabled, minLagMessages, interpolationBufferSize, staleProducerThresholdMs);
    log.info("Time lag config: enabled={}, minLagMessages={}, interpolationBufferSize={}, staleProducerThresholdMs={}",
      enabled, minLagMessages, interpolationBufferSize, staleProducerThresholdMs);

    return config;
  }
}
