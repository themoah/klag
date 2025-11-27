package io.github.themoah.klag.model;

/**
 * Consumer group state information.
 *
 * @param groupId the consumer group ID
 * @param state the current state of the consumer group
 */
public record ConsumerGroupState(
  String groupId,
  State state
) {

  /**
   * Enumeration of possible consumer group states.
   * Mirrors org.apache.kafka.common.ConsumerGroupState.
   */
  public enum State {
    UNKNOWN,
    PREPARING_REBALANCE,
    COMPLETING_REBALANCE,
    STABLE,
    DEAD,
    EMPTY;

    /**
     * Converts from Kafka's ConsumerGroupState to this enum.
     *
     * @param kafkaState the Kafka consumer group state
     * @return the corresponding State enum value
     */
    public static State fromKafkaState(org.apache.kafka.common.ConsumerGroupState kafkaState) {
      if (kafkaState == null) {
        return UNKNOWN;
      }
      return switch (kafkaState) {
        case PREPARING_REBALANCE -> PREPARING_REBALANCE;
        case COMPLETING_REBALANCE -> COMPLETING_REBALANCE;
        case STABLE -> STABLE;
        case DEAD -> DEAD;
        case EMPTY -> EMPTY;
        default -> UNKNOWN;
      };
    }

    /**
     * Returns a lowercase representation suitable for metric labels.
     *
     * @return the state name in lowercase
     */
    public String toMetricValue() {
      return name().toLowerCase();
    }
  }
}
