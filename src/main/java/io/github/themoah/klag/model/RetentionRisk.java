package io.github.themoah.klag.model;

/**
 * Represents the retention risk percentage for a consumer group and topic.
 * Shows what percentage of available messages (retention window) the consumer lag represents.
 *
 * <p>Formula: {@code (lag / (logEndOffset - logStartOffset)) * 100}
 *
 * <p>Values:
 * <ul>
 *   <li>0% - Consumer is caught up</li>
 *   <li>100% - Data loss has occurred (consumer behind logStartOffset)</li>
 * </ul>
 *
 * @param consumerGroup the consumer group ID
 * @param topic the topic name
 * @param percent the retention risk percentage (lag / retention_window * 100)
 */
public record RetentionRisk(
  String consumerGroup,
  String topic,
  double percent
) {}
