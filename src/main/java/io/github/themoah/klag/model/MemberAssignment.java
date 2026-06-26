package io.github.themoah.klag.model;

/**
 * Identifies the consumer group member (instance) that currently owns a partition.
 *
 * <p>Sourced from Kafka's {@code DescribeConsumerGroups} member assignments. Used to tag
 * consumer-owned lag metrics with {@code member_host} / {@code consumer_id} / {@code client_id},
 * mirroring kafka-lag-exporter so a partition's lag can be traced to a specific instance.
 *
 * @param memberHost the member host (Kafka {@code MemberDescription.host})
 * @param consumerId the Kafka member id (Kafka {@code MemberDescription.consumerId})
 * @param clientId   the client id the consumer set (Kafka {@code MemberDescription.clientId})
 */
public record MemberAssignment(String memberHost, String consumerId, String clientId) {

  /** Used for partitions with no current owner (Empty/Dead group): emits empty-string labels. */
  public static final MemberAssignment UNASSIGNED = new MemberAssignment("", "", "");

  // Kafka may return null host/consumerId/clientId for some brokers/states; normalize to empty
  // string so these flow straight into Micrometer Tags (which reject null values) without NPEing.
  public MemberAssignment {
    memberHost = memberHost == null ? "" : memberHost;
    consumerId = consumerId == null ? "" : consumerId;
    clientId = clientId == null ? "" : clientId;
  }
}
