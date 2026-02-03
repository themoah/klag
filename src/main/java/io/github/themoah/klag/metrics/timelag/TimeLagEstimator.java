package io.github.themoah.klag.metrics.timelag;

import io.github.themoah.klag.model.LagVelocity;
import io.github.themoah.klag.model.TimeLagEstimate;
import io.github.themoah.klag.model.TimeToCloseEstimate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calculates time-based lag estimates using velocity data.
 *
 * <p>Provides two metrics:
 * <ul>
 *   <li>Time lag (ms) - estimated time to process current lag at current consumption rate</li>
 *   <li>Time to close (seconds) - estimated time until lag reaches zero (only when catching up)</li>
 * </ul>
 */
public class TimeLagEstimator {

  private static final Logger log = LoggerFactory.getLogger(TimeLagEstimator.class);

  private final TimeLagConfig config;
  private boolean hasLoggedReady = false;

  public TimeLagEstimator(TimeLagConfig config) {
    this.config = config;
  }

  /**
   * Checks if time lag estimation is enabled.
   */
  public boolean isEnabled() {
    return config.enabled();
  }

  /**
   * Calculates time lag estimates for all consumer-group/topic pairs.
   *
   * <p>Formula: time_lag_ms = (lag_messages / |velocity|) * 1000
   *
   * <p>Reports for all consumer-group/topic pairs where velocity data is available and non-zero.
   *
   * @param velocities list of calculated velocities
   * @param lagByGroupTopic map of consumer group to map of topic to total lag
   * @return list of time lag estimates
   */
  public List<TimeLagEstimate> calculateTimeLags(
      List<LagVelocity> velocities,
      Map<String, Map<String, Long>> lagByGroupTopic
  ) {
    List<TimeLagEstimate> estimates = new ArrayList<>();

    for (LagVelocity velocity : velocities) {
      String group = velocity.consumerGroup();
      String topic = velocity.topic();

      Map<String, Long> topicLags = lagByGroupTopic.get(group);
      if (topicLags == null) {
        continue;
      }

      Long lag = topicLags.get(topic);
      if (lag == null) {
        continue;
      }

      double absVelocity = Math.abs(velocity.velocity());
      if (absVelocity < 0.001) {
        // Skip if velocity is effectively zero to avoid division issues
        continue;
      }

      // Calculate time lag in milliseconds
      // time_lag_ms = (lag_messages / velocity) * 1000
      long timeLagMs = Math.round((lag / absVelocity) * 1000);

      estimates.add(new TimeLagEstimate(
        group,
        topic,
        lag,
        velocity.velocity(),
        timeLagMs,
        velocity.sampleCount()
      ));

      log.debug("Time lag estimate for {}:{}: {} ms (lag={}, velocity={:.2f})",
        group, topic, timeLagMs, lag, velocity.velocity());
    }

    if (!estimates.isEmpty() && !hasLoggedReady) {
      log.info("Time lag estimation ready - now reporting klag.consumer.lag.time_ms for {} consumer-group/topic pairs",
        estimates.size());
      hasLoggedReady = true;
    }

    return estimates;
  }

  /**
   * Calculates time-to-close estimates for consumer groups that are catching up.
   *
   * <p>Formula: time_to_close_seconds = lag_messages / |velocity|
   *
   * <p>Only reports when:
   * <ul>
   *   <li>Velocity is negative (consumer is catching up)</li>
   *   <li>Lag exceeds minimum threshold (default: 100 messages)</li>
   * </ul>
   *
   * @param velocities list of calculated velocities
   * @param lagByGroupTopic map of consumer group to map of topic to total lag
   * @return list of time-to-close estimates
   */
  public List<TimeToCloseEstimate> calculateTimeToClose(
      List<LagVelocity> velocities,
      Map<String, Map<String, Long>> lagByGroupTopic
  ) {
    List<TimeToCloseEstimate> estimates = new ArrayList<>();

    for (LagVelocity velocity : velocities) {
      // Only calculate time-to-close when catching up (velocity < 0)
      if (velocity.velocity() >= 0) {
        continue;
      }

      String group = velocity.consumerGroup();
      String topic = velocity.topic();

      Map<String, Long> topicLags = lagByGroupTopic.get(group);
      if (topicLags == null) {
        continue;
      }

      Long lag = topicLags.get(topic);
      if (lag == null || lag < config.minLagMessages()) {
        continue;
      }

      double absVelocity = Math.abs(velocity.velocity());
      if (absVelocity < 0.001) {
        continue;
      }

      // Calculate time to close in seconds
      // time_to_close_seconds = lag_messages / |velocity|
      long timeToCloseSeconds = Math.round(lag / absVelocity);

      estimates.add(new TimeToCloseEstimate(
        group,
        topic,
        lag,
        velocity.velocity(),
        timeToCloseSeconds,
        velocity.sampleCount()
      ));

      log.debug("Time to close estimate for {}:{}: {} seconds (lag={}, velocity={:.2f})",
        group, topic, timeToCloseSeconds, lag, velocity.velocity());
    }

    return estimates;
  }
}
